package com.photoalbum.controller;

import com.photoalbum.common.BusinessException;
import com.photoalbum.common.Result;
import com.photoalbum.dto.UserUpsertDTO;
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
            m.put("canViewPrivate", u.getCanViewPrivate() != null ? u.getCanViewPrivate() : 0);
            m.put("createdAt", u.getCreatedAt());
            result.add(m);
        }
        return Result.ok(result);
    }

    /** 创建 */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody Map<String, String> body) {
        try {
            User u = userService.createUser(buildUpsert(body, true));
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
            userService.updateUser(id, buildUpsert(body, false));
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

    /**
     * 以该账号视角预览可见范围（用于核对授权配置，避免"配完不知道生效没有"）
     * GET /api/admin/users/{id}/preview
     */
    @GetMapping("/{id}/preview")
    public Result<Map<String, Object>> preview(@PathVariable Long id) {
        try {
            return Result.ok(userService.previewVisibility(id));
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
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

    private UserUpsertDTO buildUpsert(Map<String, String> body, boolean creating) {
        UserUpsertDTO dto = new UserUpsertDTO();
        dto.setUsername(body.get("username"));
        dto.setPassword(body.get("password"));
        dto.setNickname(body.get("nickname"));
        dto.setRole(body.get("role"));
        dto.setCanUpload(parseIntOrNull(body.get("canUpload")));
        dto.setCanManage(parseIntOrNull(body.get("canManage")));
        dto.setCanViewPrivate(parseIntOrNull(body.get("canViewPrivate")));
        if (!creating) {
            // 更新时不带用户名（不可改）
            dto.setUsername(null);
        }
        return dto;
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
