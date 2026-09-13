package com.photoalbum.controller;

import com.photoalbum.common.RateLimiter;
import com.photoalbum.common.Result;
import com.photoalbum.dto.LoginDTO;
import com.photoalbum.entity.User;
import com.photoalbum.security.ClientIpResolver;
import com.photoalbum.security.CurrentUserSupport;
import com.photoalbum.security.JwtUtil;
import com.photoalbum.service.AuditService;
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
    private final ClientIpResolver clientIpResolver;
    private final AuditService auditService;

    /** 登录限流参数：同一来源每分钟 5 次，超限锁定 15 分钟 */
    private static final int LOGIN_MAX_ATTEMPTS = 5;
    private static final long LOGIN_WINDOW_MS = 60_000L;
    private static final long LOGIN_LOCK_MS = 900_000L;

    /**
     * 账号维度限流参数：同一账号 15 分钟内失败 20 次即锁定 15 分钟
     *
     * 仅按来源地址限流可被"多个 IP 各试几次"的分布式撞库绕过，因此增加账号维度；
     * 阈值刻意放宽（20 次）以避免攻击者用少量失败请求把正常账号锁死。
     */
    private static final int LOGIN_ACCOUNT_MAX_ATTEMPTS = 20;
    private static final long LOGIN_ACCOUNT_WINDOW_MS = 900_000L;
    private static final long LOGIN_ACCOUNT_LOCK_MS = 900_000L;

    /**
     * 登录
     */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginDTO loginDTO,
                                              HttpServletRequest request) {
        // 限流：来源维度（防单点爆破）与账号维度（防分布式撞库）双重判定
        String ip = clientIpResolver.resolve(request);
        String ipKey = "login:" + ip;
        String accountKey = "login-user:" + loginDTO.getUsername().trim().toLowerCase(java.util.Locale.ROOT);
        boolean allowByIp = RateLimiter.tryAcquire(ipKey, LOGIN_MAX_ATTEMPTS, LOGIN_WINDOW_MS, LOGIN_LOCK_MS);
        boolean allowByAccount = RateLimiter.tryAcquire(accountKey,
                LOGIN_ACCOUNT_MAX_ATTEMPTS, LOGIN_ACCOUNT_WINDOW_MS, LOGIN_ACCOUNT_LOCK_MS);
        if (!allowByIp || !allowByAccount) {
            auditService.recordAuth(AuditService.ACTION_LOGIN_FAIL, loginDTO.getUsername(), null,
                    "尝试次数过多已被限流（" + (allowByIp ? "账号维度" : "来源维度") + "）");
            return Result.fail(429, "尝试次数过多，请15分钟后再试");
        }

        // 1. 查询用户
        User user = userService.findByUsername(loginDTO.getUsername());
        if (user == null) {
            return Result.fail("用户名或密码错误");
        }

        // 2. 校验密码
        if (!passwordEncoder.matches(loginDTO.getPassword(), user.getPassword())) {
            auditService.recordAuth(AuditService.ACTION_LOGIN_FAIL, user.getUsername(), user.getId(), "口令错误");
            return Result.fail("用户名或密码错误");
        }

        // 登录成功，清除限流记录
        RateLimiter.clear(ipKey);
        RateLimiter.clear(accountKey);
        auditService.recordAuth(AuditService.ACTION_LOGIN_SUCCESS, user.getUsername(), user.getId(), "登录成功");

        // 3. 生成 JWT Token（带令牌版本，改密/登出后旧令牌立即失效）
        String token = jwtUtil.generateToken(user.getUsername(), user.getId(), user.getTokenVersion());

        // 4. 返回 Token 和用户信息（不含密码）
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userInfo", buildUserInfo(user));

        return Result.ok(data);
    }

    /**
     * 退出登录
     *
     * 前端清除本地令牌的同时，服务端自增该账号的令牌版本，使已签发的令牌立即失效
     * （注意：该账号在其他设备上的登录状态也会一并失效，这正是"令牌可吊销"的语义）。
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        User current = CurrentUserSupport.getCurrentUser();
        if (current != null) {
            userService.revokeTokens(current.getId());
        }
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
        info.put("canViewPrivate", user.getCanViewPrivate() != null ? user.getCanViewPrivate() : 0);
        // 是否为合集协作者：决定前端是否展示"合集管理"入口（对象级管理权）
        info.put("isCollectionMember", userService.isCollectionMember(user.getId()));
        return info;
    }
}
