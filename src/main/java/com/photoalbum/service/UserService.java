package com.photoalbum.service;

import com.photoalbum.dto.UserUpsertDTO;
import com.photoalbum.entity.User;
import java.util.List;
import java.util.Map;

public interface UserService {

    User findByUsername(String username);
    User findById(Long id);

    /** 获取所有用户列表（管理员） */
    List<User> findAll();

    /** 创建用户，返回新用户 */
    User createUser(UserUpsertDTO dto);

    /** 更新用户信息（含角色、能力位、口令） */
    User updateUser(Long id, UserUpsertDTO dto);

    /** 删除用户 */
    void deleteUser(Long id);

    /** 吊销该账号已签发的令牌（自增令牌版本，改密与登出时调用） */
    void revokeTokens(Long userId);

    /** 是否为某个合集的协作者（用于前端展示合集管理入口） */
    boolean isCollectionMember(Long userId);

    /**
     * 以指定账号视角统计可见范围（管理员核对授权配置用）
     *
     * @return 含 totalPhotos / publicPhotos / privatePhotos / visiblePrivatePhotos 等键
     */
    Map<String, Object> previewVisibility(Long userId);

    /** 获取用户权限白/黑名单 */
    List<Map<String, Object>> getUserPermissions(Long userId);

    /** 添加权限条目 */
    void addPermission(Long userId, String permType, String targetType, Long targetId);

    /** 删除权限条目 */
    void removePermission(Long permId);
}
