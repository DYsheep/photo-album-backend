package com.photoalbum.controller;

import com.photoalbum.common.BusinessException;
import com.photoalbum.common.Result;
import com.photoalbum.entity.User;
import com.photoalbum.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 用户与授权管理（仅管理员：user:manage）
 *
 * 路径级规则（/api/admin/users/**）与方法级注解双重约束，避免路径调整后失去保护。
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('user:manage')")
public class AdminUserController {

    private final UserService userService;

    /** 列表 */
    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        List<User> users = userService.findAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (User u : users) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("nickname", u.getNickname());
            m.put("role", u.getRole());
            m.put("canUpload", u.getCanUpload() != null ? u.getCanUpload() : 0);
            m.put("canManage", u.getCanManage() != null ? u.getCanManage() : 0);
            m.put("createdAt", u.getCreatedAt());
            result.add(m);
        }
        return Result.ok(result);
    }

    /** 创建 */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody Map<String, String> body) {
        try {
            User u = userService.createUser(
                    body.get("username"), body.get("password"),
                    body.get("nickname"), body.get("role"),
                    parseIntOrNull(body.get("canUpload")),
                    parseIntOrNull(body.get("canManage")));
            Map<String, Object> m = new HashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("role", u.getRole());
            return Result.ok("用户已创建", m);
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    /** 更新 */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        try {
            userService.updateUser(id, body.get("nickname"), body.get("role"), body.get("password"),
                    parseIntOrNull(body.get("canUpload")), parseIntOrNull(body.get("canManage")));
            return Result.ok();
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    /** 删除 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        try {
            userService.deleteUser(id);
            return Result.ok();
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    /** 获取权限白/黑名单 */
    @GetMapping("/{id}/permissions")
    public Result<List<Map<String, Object>>> permissions(@PathVariable Long id) {
        return Result.ok(userService.getUserPermissions(id));
    }

    /** 添加权限条目 */
    @PostMapping("/{id}/permissions")
    public Result<Void> addPermission(@PathVariable Long id, @RequestBody Map<String, String> body) {
        try {
            userService.addPermission(id, body.get("permType"), body.get("targetType"),
                    parseLongOrNull(body.get("targetId")));
            return Result.ok();
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    /** 删除权限条目 */
    @DeleteMapping("/{id}/permissions/{permId}")
    public Result<Void> removePermission(@PathVariable Long id, @PathVariable Long permId) {
        userService.removePermission(permId);
        return Result.ok();
    }

    private Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private Long parseLongOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
    }
}
