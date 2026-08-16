package com.photoalbum.controller;

import com.photoalbum.common.LoginRateLimiter;
import com.photoalbum.common.Result;
import com.photoalbum.dto.LoginDTO;
import com.photoalbum.entity.User;
import com.photoalbum.security.JwtUtil;
import com.photoalbum.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证控制器
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    /**
     * 登录
     */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginDTO loginDTO,
                                              HttpServletRequest request) {
        // 限流：每个 IP 每分钟最多 5 次尝试
        String ip = getClientIp(request);
        if (!LoginRateLimiter.tryAcquire(ip)) {
            return Result.fail(429, "尝试次数过多，请15分钟后再试");
        }

        // 1. 查询用户
        User user = userService.findByUsername(loginDTO.getUsername());
        if (user == null) {
            return Result.fail("用户名或密码错误");
        }

        // 2. 校验密码
        if (!passwordEncoder.matches(loginDTO.getPassword(), user.getPassword())) {
            return Result.fail("用户名或密码错误");
        }

        // 登录成功，清除限流记录
        LoginRateLimiter.clear(ip);

        // 3. 生成 JWT Token
        String token = jwtUtil.generateToken(user.getUsername(), user.getId());

        // 4. 返回 Token 和用户信息（不含密码）
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userInfo", buildUserInfo(user));

        return Result.ok(data);
    }

    /**
     * 退出登录（前端删除 token 即可，后端仅做兼容）
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        return Result.ok();
    }

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/userinfo")
    public Result<Map<String, Object>> getUserInfo(
            @org.springframework.security.core.annotation.AuthenticationPrincipal User currentUser) {
        if (currentUser == null) {
            return Result.fail(401, "未登录");
        }
        return Result.ok(buildUserInfo(currentUser));
    }

    /**
     * 构建返回给前端的用户信息（脱敏）
     */
    private Map<String, Object> buildUserInfo(User user) {
        Map<String, Object> info = new HashMap<>();
        info.put("id", user.getId());
        info.put("username", user.getUsername());
        info.put("nickname", user.getNickname());
        info.put("avatar", user.getAvatar());
        info.put("role", user.getRole());
        info.put("canUpload", user.getCanUpload() != null ? user.getCanUpload() : 0);
        info.put("canManage", user.getCanManage() != null ? user.getCanManage() : 0);
        return info;
    }

    /** 获取客户端真实 IP（考虑代理） */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // X-Forwarded-For 可能包含多个 IP，取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
