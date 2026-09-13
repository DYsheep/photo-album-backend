package com.photoalbum.common;

import java.util.Locale;
import java.util.Set;

/**
 * 账号角色定义
 *
 * 角色语义（本类是全站唯一权威定义）：
 *   · admin  站点所有者，全部权限
 *   · viewer 可被授予私密内容可见性的访客（须配合 t_user_permission 授权，默认看不到任何私密内容）
 *   · user   仅可见公开内容
 *
 * 安全说明：角色字符串在写入与判定两处必须做同一套归一化处理，
 * 避免出现"通过接口写入 Admin，鉴权按大小写不敏感放行、业务层按大小写敏感拒绝"的隐性提权。
 */
public final class UserRoles {

    public static final String ADMIN = "admin";
    public static final String VIEWER = "viewer";
    public static final String USER = "user";

    /** 允许存储的角色集合 */
    private static final Set<String> ALLOWED = Set.of(ADMIN, VIEWER, USER);

    private UserRoles() {
    }

    /**
     * 归一化角色值：去空白、转小写；空值回退为普通用户
     */
    public static String normalize(String role) {
        if (role == null) {
            return USER;
        }
        String normalized = role.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? USER : normalized;
    }

    /** 是否为合法角色（按归一化后的值判断） */
    public static boolean isValid(String role) {
        return role != null && ALLOWED.contains(role.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 校验角色值，非法则抛出业务异常
     *
     * @return 归一化后的角色值
     */
    public static String requireValid(String role) {
        if (role == null || role.isBlank()) {
            return USER;
        }
        String normalized = normalize(role);
        if (!ALLOWED.contains(normalized)) {
            throw new BusinessException(400, "角色取值非法，仅支持 admin/viewer/user");
        }
        return normalized;
    }

    public static boolean isAdmin(String role) {
        return ADMIN.equals(normalize(role));
    }

    public static boolean isViewer(String role) {
        return VIEWER.equals(normalize(role));
    }
}
