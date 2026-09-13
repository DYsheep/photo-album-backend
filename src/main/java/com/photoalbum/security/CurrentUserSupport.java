package com.photoalbum.security;

import com.photoalbum.entity.User;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户读取工具（唯一实现）
 *
 * 权限判定本身不在这里：角色语义见 UserRoles，权限标记见 UserAuthorities，
 * 资源可见性见 AccessPolicy。本类只负责从安全上下文取当前用户。
 */
public final class CurrentUserSupport {

    private CurrentUserSupport() {
    }

    /** 获取当前登录用户，未登录返回 null */
    public static User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        return principal instanceof User ? (User) principal : null;
    }
}
