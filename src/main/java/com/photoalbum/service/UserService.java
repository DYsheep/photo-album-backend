package com.photoalbum.service;

import com.photoalbum.entity.User;
import java.util.List;
import java.util.Map;

public interface UserService {

    User findByUsername(String username);
    User findById(Long id);

    /** 获取所有用户列表（管理员） */
    List<User> findAll();

    /** 创建用户，返回新用户 */
    User createUser(String username, String password, String nickname, String role, Integer canUpload, Integer canManage);

    /** 更新用户信息 */
    User updateUser(Long id, String nickname, String role, String password, Integer canUpload, Integer canManage);

    /** 删除用户 */
    void deleteUser(Long id);

    /** 获取用户权限白/黑名单 */
    List<Map<String, Object>> getUserPermissions(Long userId);

    /** 添加权限条目 */
    void addPermission(Long userId, String permType, String targetType, Long targetId);

    /** 删除权限条目 */
    void removePermission(Long permId);
}
