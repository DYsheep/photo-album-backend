package com.photoalbum.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.photoalbum.common.BusinessException;
import com.photoalbum.common.PermissionConstants;
import com.photoalbum.common.UserRoles;
import com.photoalbum.dto.UserUpsertDTO;
import com.photoalbum.entity.CollectionMember;
import com.photoalbum.entity.Photo;
import com.photoalbum.entity.User;
import com.photoalbum.entity.AuthTuple;
import com.photoalbum.mapper.CategoryMapper;
import com.photoalbum.mapper.CollectionMemberMapper;
import com.photoalbum.mapper.PhotoCollectionMapper;
import com.photoalbum.mapper.PhotoMapper;
import com.photoalbum.mapper.UserMapper;
import com.photoalbum.mapper.AuthTupleMapper;
import com.photoalbum.security.AccessPolicy;
import com.photoalbum.security.CurrentUserSupport;
import com.photoalbum.service.AuditService;
import com.photoalbum.service.UserService;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;

@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final AuthTupleMapper tupleMapper;
    private final PhotoMapper photoMapper;
    private final PhotoCollectionMapper collectionMapper;
    private final CategoryMapper categoryMapper;
    private final CollectionMemberMapper memberMapper;
    private final AuditService auditService;
    private final AccessPolicy accessPolicy;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserMapper userMapper, AuthTupleMapper tupleMapper,
                           PhotoMapper photoMapper, PhotoCollectionMapper collectionMapper,
                           CategoryMapper categoryMapper, CollectionMemberMapper memberMapper,
                           AuditService auditService, AccessPolicy accessPolicy,
                           @Lazy PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.tupleMapper = tupleMapper;
        this.photoMapper = photoMapper;
        this.collectionMapper = collectionMapper;
        this.categoryMapper = categoryMapper;
        this.memberMapper = memberMapper;
        this.auditService = auditService;
        this.accessPolicy = accessPolicy;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public User findByUsername(String username) {
        return userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        );
    }

    @Override
    public User findById(Long id) {
        return userMapper.selectById(id);
    }

    @Override
    public List<User> findAll() {
        return userMapper.selectList(null);
    }

    @Override
    @Transactional
    public User createUser(UserUpsertDTO dto) {
        if (!StringUtils.hasText(dto.getUsername()) || !StringUtils.hasText(dto.getPassword())) {
            throw new BusinessException(400, "用户名与口令不能为空");
        }
        if (findByUsername(dto.getUsername()) != null) {
            throw new BusinessException(400, "用户名已存在");
        }
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname() != null ? dto.getNickname() : dto.getUsername());
        // 角色统一归一化并校验（此前可写入任意字符串，导致鉴权与业务判定口径不一致）
        user.setRole(UserRoles.requireValid(dto.getRole()));
        user.setCanUpload(flag(dto.getCanUpload()));
        user.setCanManage(flag(dto.getCanManage()));
        user.setCanViewPrivate(flag(dto.getCanViewPrivate()));
        user.setTokenVersion(0);
        userMapper.insert(user);

        auditService.record(AuditService.ACTION_USER_CREATE, AuditService.TARGET_USER, user.getId(),
                String.format("创建账号 %s（角色 %s，上传 %d，管理 %d，可见私密 %d）",
                        user.getUsername(), user.getRole(), user.getCanUpload(),
                        user.getCanManage(), user.getCanViewPrivate()));
        return user;
    }

    @Override
    @Transactional
    public User updateUser(Long id, UserUpsertDTO dto) {
        User user = userMapper.selectById(id);
        if (user == null) throw new BusinessException(404, "用户不存在");

        String normalizedRole = dto.getRole() == null ? null : UserRoles.requireValid(dto.getRole());
        String currentRole = UserRoles.normalize(user.getRole());
        // 禁止把最后一个管理员降级
        if (normalizedRole != null && !UserRoles.ADMIN.equals(normalizedRole)
                && UserRoles.ADMIN.equals(currentRole) && countAdmins() <= 1) {
            throw new BusinessException(400, "不能移除最后一个管理员的权限");
        }

        // 变更项（用于审计，口令只记录"已重置"，不记录内容）
        List<String> changes = new ArrayList<>();
        if (dto.getNickname() != null && !dto.getNickname().equals(user.getNickname())) {
            changes.add("昵称");
        }
        if (normalizedRole != null && !normalizedRole.equals(currentRole)) {
            changes.add("角色 " + currentRole + "→" + normalizedRole);
        }
        if (dto.getCanUpload() != null && !dto.getCanUpload().equals(user.getCanUpload())) {
            changes.add("上传能力→" + dto.getCanUpload());
        }
        if (dto.getCanManage() != null && !dto.getCanManage().equals(user.getCanManage())) {
            changes.add("管理能力→" + dto.getCanManage());
        }
        if (dto.getCanViewPrivate() != null && !dto.getCanViewPrivate().equals(user.getCanViewPrivate())) {
            changes.add("查看私密能力→" + dto.getCanViewPrivate());
        }
        boolean passwordReset = StringUtils.hasText(dto.getPassword());
        if (passwordReset) {
            changes.add("重置口令（同时吊销其已签发令牌）");
        }

        if (dto.getNickname() != null) user.setNickname(dto.getNickname());
        if (normalizedRole != null) user.setRole(normalizedRole);
        if (dto.getCanUpload() != null) user.setCanUpload(dto.getCanUpload());
        if (dto.getCanManage() != null) user.setCanManage(dto.getCanManage());
        if (dto.getCanViewPrivate() != null) user.setCanViewPrivate(dto.getCanViewPrivate());
        if (passwordReset) {
            user.setPassword(passwordEncoder.encode(dto.getPassword()));
            // 改密即吊销：旧令牌立即失效（tokenVersion 自增）
            user.setTokenVersion(nextTokenVersion(user));
        }
        userMapper.updateById(user);

        if (!changes.isEmpty()) {
            auditService.record(AuditService.ACTION_USER_UPDATE, AuditService.TARGET_USER, id,
                    String.format("修改账号 %s：%s", user.getUsername(), String.join("，", changes)));
        }
        return user;
    }

    @Override
    @Transactional
    public void deleteUser(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) throw new BusinessException(404, "用户不存在");
        if (UserRoles.ADMIN.equals(UserRoles.normalize(user.getRole())) && countAdmins() <= 1) {
            throw new BusinessException(400, "不能删除最后一个管理员");
        }
        // 同时删除该用户的权限条目
        tupleMapper.delete(new LambdaQueryWrapper<AuthTuple>()
                .eq(AuthTuple::getSubjectType, "user")
                .eq(AuthTuple::getSubjectId, id));
        userMapper.deleteById(id);
        auditService.record(AuditService.ACTION_USER_DELETE, AuditService.TARGET_USER, id,
                String.format("删除账号 %s（角色 %s），同时清除其全部授权条目",
                        user.getUsername(), UserRoles.normalize(user.getRole())));
    }

    @Override
    @Transactional
    public void revokeTokens(Long userId) {        User user = userMapper.selectById(userId);
        if (user == null) {
            return;
        }
        User update = new User();
        update.setId(userId);
        update.setTokenVersion(nextTokenVersion(user));
        userMapper.updateById(update);
        // 常规登出不写审计日志，避免日志被登出记录刷满；改密引发的吊销已计入账号修改审计
    }

    private Integer nextTokenVersion(User user) {
        return (user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1;
    }

    @Override
    public boolean isCollectionMember(Long userId) {
        if (userId == null) {
            return false;
        }
        Long count = memberMapper.selectCount(new LambdaQueryWrapper<CollectionMember>()
                .eq(CollectionMember::getUserId, userId));
        return count != null && count > 0;
    }

    /**
     * 以指定账号视角统计可见范围
     *
     * 复用同一套可见性策略（AccessPolicy），因此结果与实际访问时完全一致；
     * 可见私密数 = 可见总数 - 公开总数（策略条件始终包含"公开可见"这一项）。
     */
    @Override
    public Map<String, Object> previewVisibility(Long userId) {
        User target = userMapper.selectById(userId);
        if (target == null) {
            throw new BusinessException(404, "用户不存在");
        }
        long publicPhotos = photoMapper.selectCount(
                new LambdaQueryWrapper<Photo>().eq(Photo::getIsPrivate, 0));
        long privatePhotos = photoMapper.selectCount(
                new LambdaQueryWrapper<Photo>().eq(Photo::getIsPrivate, 1));

        LambdaQueryWrapper<Photo> visibleWrapper = new LambdaQueryWrapper<>();
        accessPolicy.applyPhotoFilter(visibleWrapper, target);
        long visiblePhotos = photoMapper.selectCount(visibleWrapper);
        long visiblePrivate = Math.max(visiblePhotos - publicPhotos, 0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", target.getId());
        result.put("username", target.getUsername());
        result.put("role", UserRoles.normalize(target.getRole()));
        result.put("canViewPrivate", target.getCanViewPrivate() != null ? target.getCanViewPrivate() : 0);
        result.put("totalPhotos", publicPhotos + privatePhotos);
        result.put("publicPhotos", publicPhotos);
        result.put("privatePhotos", privatePhotos);
        result.put("visiblePrivatePhotos", visiblePrivate);
        result.put("visiblePhotos", visiblePhotos);
        return result;
    }

    private Integer flag(Integer value) {
        return value != null && value == 1 ? 1 : 0;
    }

    private long countAdmins() {
        return userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getRole, UserRoles.ADMIN));
    }

    @Override
    public List<Map<String, Object>> getUserPermissions(Long userId) {
        List<AuthTuple> tuples = tupleMapper.selectList(new LambdaQueryWrapper<AuthTuple>()
                .eq(AuthTuple::getSubjectType, "user")
                .eq(AuthTuple::getSubjectId, userId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (AuthTuple tuple : tuples) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", tuple.getId());
            // 对外保持原有 W/B 形状，前端与既有调用方无需改动
            m.put("permType", AuthTuple.RELATION_ALLOW.equals(tuple.getRelation()) ? "W" : "B");
            m.put("targetType", tuple.getObjectType());
            m.put("targetId", tuple.getObjectId());
            m.put("createdAt", tuple.getCreatedAt());
            m.put("createdBy", tuple.getCreatedBy());
            result.add(m);
        }
        return result;
    }

    @Override
    @Transactional
    public void addPermission(Long userId, String permType, String targetType, Long targetId) {
        // 取值合法性（W/B 与对象类型枚举），避免写入脏值导致判定失效
        PermissionConstants.requireValid(permType, targetType);
        if (userMapper.selectById(userId) == null) {
            throw new BusinessException(404, "用户不存在");
        }

        long resolvedTargetId = PermissionConstants.TARGET_GLOBAL_ID;
        if (!PermissionConstants.TARGET_GLOBAL.equals(targetType)) {
            if (targetId == null) {
                throw new BusinessException(400, "授权对象 ID 不能为空");
            }
            if (!targetExists(targetType, targetId)) {
                throw new BusinessException(404, "授权对象不存在");
            }
            resolvedTargetId = targetId;
        }

        String relation = PermissionConstants.TYPE_WHITELIST.equals(permType)
                ? AuthTuple.RELATION_ALLOW : AuthTuple.RELATION_DENY;

        long duplicated = tupleMapper.selectCount(new LambdaQueryWrapper<AuthTuple>()
                .eq(AuthTuple::getSubjectType, "user")
                .eq(AuthTuple::getSubjectId, userId)
                .eq(AuthTuple::getRelation, relation)
                .eq(AuthTuple::getObjectType, targetType)
                .eq(AuthTuple::getObjectId, resolvedTargetId));
        if (duplicated > 0) {
            throw new BusinessException(400, "该授权条目已存在");
        }

        AuthTuple tuple = new AuthTuple();
        tuple.setSubjectType("user");
        tuple.setSubjectId(userId);
        tuple.setRelation(relation);
        tuple.setObjectType(targetType);
        tuple.setObjectId(resolvedTargetId);
        User operator = CurrentUserSupport.getCurrentUser();
        tuple.setCreatedBy(operator != null ? operator.getId() : null);
        tupleMapper.insert(tuple);

        auditService.record(AuditService.ACTION_GRANT_ADD, AuditService.TARGET_PERMISSION, tuple.getId(),
                String.format("为账号 %s 新增授权：%s", describeUser(userId),
                        describePerm(permType, targetType, resolvedTargetId)));
    }

    @Override
    @Transactional
    public void removePermission(Long permId) {
        // 先取出被删除的条目内容，保证审计可追溯
        AuthTuple tuple = tupleMapper.selectById(permId);
        tupleMapper.deleteById(permId);
        if (tuple != null) {
            auditService.record(AuditService.ACTION_GRANT_REMOVE, AuditService.TARGET_PERMISSION, permId,
                    String.format("移除账号 %s 的授权：%s", describeUser(tuple.getSubjectId()),
                            describePerm(AuthTuple.RELATION_ALLOW.equals(tuple.getRelation()) ? "W" : "B",
                                    tuple.getObjectType(), tuple.getObjectId())));
        }
    }

    /** 授权对象是否存在（按维度校验，避免对不存在的对象授权） */
    private boolean targetExists(String targetType, Long targetId) {
        return switch (targetType) {
            case PermissionConstants.TARGET_PHOTO -> photoMapper.selectById(targetId) != null;
            case PermissionConstants.TARGET_COLLECTION -> collectionMapper.selectById(targetId) != null;
            case PermissionConstants.TARGET_CATEGORY -> categoryMapper.selectById(targetId) != null;
            default -> false;
        };
    }

    /** 授权条目的人类可读描述 */
    private String describePerm(String permType, String targetType, Long targetId) {
        String typeLabel = PermissionConstants.TYPE_WHITELIST.equals(permType) ? "白名单" : "黑名单";
        if (PermissionConstants.TARGET_GLOBAL.equals(targetType)) {
            return typeLabel + "（全部私密内容）";
        }
        String targetLabel = switch (targetType) {
            case PermissionConstants.TARGET_PHOTO -> "照片";
            case PermissionConstants.TARGET_COLLECTION -> "合集";
            case PermissionConstants.TARGET_CATEGORY -> "分类";
            default -> targetType;
        };
        return typeLabel + "（" + targetLabel + " #" + targetId + "）";
    }

    /** 账号标识（用户名#ID），便于审计记录直接阅读 */
    private String describeUser(Long userId) {
        User user = userId == null ? null : userMapper.selectById(userId);
        return user != null ? user.getUsername() + "#" + userId : "#" + userId;
    }
}
