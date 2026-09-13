package com.photoalbum.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.photoalbum.common.BusinessException;
import com.photoalbum.common.PermissionConstants;
import com.photoalbum.common.UserRoles;
import com.photoalbum.entity.User;
import com.photoalbum.entity.UserPermission;
import com.photoalbum.mapper.PhotoCollectionMapper;
import com.photoalbum.mapper.PhotoMapper;
import com.photoalbum.mapper.UserMapper;
import com.photoalbum.mapper.UserPermissionMapper;
import com.photoalbum.security.CurrentUserSupport;
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
    private final UserPermissionMapper permMapper;
    private final PhotoMapper photoMapper;
    private final PhotoCollectionMapper collectionMapper;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserMapper userMapper, UserPermissionMapper permMapper,
                           PhotoMapper photoMapper, PhotoCollectionMapper collectionMapper,
                           @Lazy PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.permMapper = permMapper;
        this.photoMapper = photoMapper;
        this.collectionMapper = collectionMapper;
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
    public User createUser(String username, String password, String nickname, String role, Integer canUpload, Integer canManage) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new BusinessException(400, "用户名与口令不能为空");
        }
        if (findByUsername(username) != null) {
            throw new BusinessException(400, "用户名已存在");
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(nickname != null ? nickname : username);
        // 角色统一归一化并校验（此前可写入任意字符串，导致鉴权与业务判定口径不一致）
        user.setRole(UserRoles.requireValid(role));
        user.setCanUpload(canUpload != null ? canUpload : 0);
        user.setCanManage(canManage != null ? canManage : 0);
        userMapper.insert(user);
        return user;
    }

    @Override
    @Transactional
    public User updateUser(Long id, String nickname, String role, String password, Integer canUpload, Integer canManage) {
        User user = userMapper.selectById(id);
        if (user == null) throw new BusinessException(404, "用户不存在");
        String normalizedRole = role == null ? null : UserRoles.requireValid(role);
        // 禁止把最后一个管理员降级
        if (normalizedRole != null && !UserRoles.ADMIN.equals(normalizedRole)
                && UserRoles.ADMIN.equals(UserRoles.normalize(user.getRole())) && countAdmins() <= 1) {
            throw new BusinessException(400, "不能移除最后一个管理员的权限");
        }
        if (nickname != null) user.setNickname(nickname);
        if (normalizedRole != null) user.setRole(normalizedRole);
        if (canUpload != null) user.setCanUpload(canUpload);
        if (canManage != null) user.setCanManage(canManage);
        if (StringUtils.hasText(password)) {
            user.setPassword(passwordEncoder.encode(password));
        }
        userMapper.updateById(user);
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
        permMapper.delete(new LambdaQueryWrapper<UserPermission>().eq(UserPermission::getUserId, id));
        userMapper.deleteById(id);
    }

    private long countAdmins() {
        return userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getRole, UserRoles.ADMIN));
    }

    @Override
    public List<Map<String, Object>> getUserPermissions(Long userId) {
        List<UserPermission> perms = permMapper.selectList(
                new LambdaQueryWrapper<UserPermission>().eq(UserPermission::getUserId, userId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (UserPermission p : perms) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", p.getId());
            m.put("permType", p.getPermType());
            m.put("targetType", p.getTargetType());
            m.put("targetId", p.getTargetId());
            m.put("createdAt", p.getCreatedAt());
            m.put("createdBy", p.getCreatedBy());
            result.add(m);
        }
        return result;
    }

    @Override
    @Transactional
    public void addPermission(Long userId, String permType, String targetType, Long targetId) {
        // 取值合法性（枚举校验，避免写入 W/w、photo/xxx 之类脏值导致判定失效）
        PermissionConstants.requireValid(permType, targetType);
        if (userMapper.selectById(userId) == null) {
            throw new BusinessException(404, "用户不存在");
        }

        long resolvedTargetId = PermissionConstants.TARGET_GLOBAL_ID;
        if (!PermissionConstants.TARGET_GLOBAL.equals(targetType)) {
            if (targetId == null) {
                throw new BusinessException(400, "授权对象 ID 不能为空");
            }
            boolean exists = PermissionConstants.TARGET_PHOTO.equals(targetType)
                    ? photoMapper.selectById(targetId) != null
                    : collectionMapper.selectById(targetId) != null;
            if (!exists) {
                throw new BusinessException(404, "授权对象不存在");
            }
            resolvedTargetId = targetId;
        }

        // 同一对象不重复授权
        long duplicated = permMapper.selectCount(new LambdaQueryWrapper<UserPermission>()
                .eq(UserPermission::getUserId, userId)
                .eq(UserPermission::getPermType, permType)
                .eq(UserPermission::getTargetType, targetType)
                .eq(UserPermission::getTargetId, resolvedTargetId));
        if (duplicated > 0) {
            throw new BusinessException(400, "该授权条目已存在");
        }

        UserPermission permission = new UserPermission();
        permission.setUserId(userId);
        permission.setPermType(permType);
        permission.setTargetType(targetType);
        permission.setTargetId(resolvedTargetId);
        User operator = CurrentUserSupport.getCurrentUser();
        permission.setCreatedBy(operator != null ? operator.getId() : null);
        permMapper.insert(permission);
    }

    @Override
    @Transactional
    public void removePermission(Long permId) {
        permMapper.deleteById(permId);
    }
}
