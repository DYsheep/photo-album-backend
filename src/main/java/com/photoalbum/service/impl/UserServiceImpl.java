package com.photoalbum.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.photoalbum.common.BusinessException;
import com.photoalbum.entity.User;
import com.photoalbum.entity.UserPermission;
import com.photoalbum.mapper.UserMapper;
import com.photoalbum.mapper.UserPermissionMapper;
import com.photoalbum.service.UserService;
import lombok.RequiredArgsConstructor;
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
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserMapper userMapper, UserPermissionMapper permMapper,
                           @Lazy PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.permMapper = permMapper;
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
        if (findByUsername(username) != null) {
            throw new BusinessException(400, "用户名已存在");
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(nickname != null ? nickname : username);
        user.setRole(role != null ? role : "user");
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
        // 禁止把最后一个管理员降级
        if (role != null && !"admin".equals(role) && "admin".equals(user.getRole()) && countAdmins() <= 1) {
            throw new BusinessException(400, "不能移除最后一个管理员的权限");
        }
        if (nickname != null) user.setNickname(nickname);
        if (role != null) user.setRole(role);
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
        if ("admin".equals(user.getRole()) && countAdmins() <= 1) {
            throw new BusinessException(400, "不能删除最后一个管理员");
        }
        // 同时删除该用户的权限条目
        permMapper.delete(new LambdaQueryWrapper<UserPermission>().eq(UserPermission::getUserId, id));
        userMapper.deleteById(id);
    }

    private long countAdmins() {
        return userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getRole, "admin"));
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
            result.add(m);
        }
        return result;
    }

    @Override
    @Transactional
    public void addPermission(Long userId, String permType, String targetType, Long targetId) {
        UserPermission p = new UserPermission();
        p.setUserId(userId);
        p.setPermType(permType);
        p.setTargetType(targetType);
        p.setTargetId(targetId);
        permMapper.insert(p);
    }

    @Override
    @Transactional
    public void removePermission(Long permId) {
        permMapper.deleteById(permId);
    }
}
