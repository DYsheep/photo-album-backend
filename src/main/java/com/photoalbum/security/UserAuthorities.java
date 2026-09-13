package com.photoalbum.security;

import com.photoalbum.common.UserRoles;
import com.photoalbum.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 权限标记（authority）派生
 *
 * 这是"角色 + 功能位 → 权限标记"的唯一转换入口，认证过滤器与测试共用同一套规则，
 * 避免同一个人在不同位置被判定为不同权限（此前 role 大小写在两处被不同处理即属此类问题）。
 */
public final class UserAuthorities {

    /** 上传照片 */
    public static final String PHOTO_UPLOAD = "photo:upload";

    /** 内容管理：照片改删、分类、标签、合集、分享 */
    public static final String PHOTO_MANAGE = "photo:manage";

    /** 具备私密内容的可见资格（是否能看到具体某张，仍取决于 t_user_permission 授权） */
    public static final String PHOTO_VIEW_PRIVATE = "photo:view_private";

    /** 后台管理接口访问权（管理员或具备管理权限） */
    public static final String ADMIN_ACCESS = "admin:access";

    /** 用户与授权管理（仅管理员） */
    public static final String USER_MANAGE = "user:manage";

    private UserAuthorities() {
    }

    /** 派生权限标记集合 */
    public static Collection<GrantedAuthority> of(User user) {
        Set<String> names = namesOf(user);
        List<GrantedAuthority> authorities = new ArrayList<>(names.size());
        for (String name : names) {
            authorities.add(new SimpleGrantedAuthority(name));
        }
        return authorities;
    }

    /** 派生权限标记名称集合（未登录用户不持有任何权限标记） */
    public static Set<String> namesOf(User user) {
        Set<String> names = new LinkedHashSet<>();
        if (user == null) {
            return names;
        }
        String role = UserRoles.normalize(user.getRole());
        names.add("ROLE_" + role.toUpperCase(Locale.ROOT));

        if (UserRoles.ADMIN.equals(role)) {
            names.add(PHOTO_UPLOAD);
            names.add(PHOTO_MANAGE);
            names.add(PHOTO_VIEW_PRIVATE);
            names.add(ADMIN_ACCESS);
            names.add(USER_MANAGE);
            return names;
        }

        if (isEnabled(user.getCanUpload())) {
            names.add(PHOTO_UPLOAD);
        }
        if (isEnabled(user.getCanManage())) {
            names.add(PHOTO_MANAGE);
            names.add(ADMIN_ACCESS);
        }
        // 私密查看能力来自能力位，不再与角色名绑定：
        // 角色退回为纯标识，新增角色类型无需改动鉴权代码
        if (isEnabled(user.getCanViewPrivate())) {
            names.add(PHOTO_VIEW_PRIVATE);
        }
        return names;
    }

    /** 是否具备私密内容的可见资格 */
    public static boolean mayViewPrivate(User user) {
        return namesOf(user).contains(PHOTO_VIEW_PRIVATE);
    }

    /** 是否具备管理权限（管理员或 canManage=1） */
    public static boolean hasManageAccess(User user) {
        return namesOf(user).contains(PHOTO_MANAGE);
    }

    private static boolean isEnabled(Integer flag) {
        return flag != null && flag == 1;
    }
}
