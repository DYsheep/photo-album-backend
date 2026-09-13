package com.photoalbum.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JWT 工具类
 *
 * 安全说明：签名密钥必须由环境变量 JWT_SECRET 注入，且不得使用任何曾在仓库中出现的示例值。
 * 未注入时不再回退到固定默认值，而是生成一次性随机密钥（服务重启后既有令牌失效），
 * 以避免"密钥可预测 → 任意伪造管理员令牌"的风险。
 */
@Slf4j
@Component
public class JwtUtil {

    /** HS256 要求密钥不少于 256 位（32 字节） */
    private static final int MIN_SECRET_BYTES = 32;

    /** 一次性随机密钥长度（字节） */
    private static final int RANDOM_KEY_BYTES = 64;

    /** 禁止使用的已公开/示例密钥（命中即拒绝启动） */
    private static final Set<String> FORBIDDEN_SECRETS = Set.of(
            "dev-only-secret-not-for-production-use",
            "changeme", "secret", "jwt-secret", "123456"
    );

    @Value("${jwt.secret:}")
    private String secret;

    @Value("${jwt.expiration}")
    private Long expiration;

    /** 运行时实际使用的签名密钥（启动时确定，不随请求变化） */
    private volatile SecretKey signingKey;

    /**
     * 启动时校验并确定签名密钥。
     * 命中禁用值或长度不足时直接拒绝启动（避免带病上线）。
     */
    @PostConstruct
    public void init() {
        if (signingKey != null) {
            return;
        }
        String value = secret == null ? "" : secret.trim();

        // 1. 未配置：生成一次性随机密钥，保证不可预测
        if (value.isEmpty()) {
            byte[] random = new byte[RANDOM_KEY_BYTES];
            new SecureRandom().nextBytes(random);
            this.signingKey = Keys.hmacShaKeyFor(random);
            log.warn("未配置 JWT_SECRET，已生成一次性随机签名密钥；服务重启后所有登录状态将失效。"
                    + "生产环境请通过环境变量 JWT_SECRET 注入固定随机密钥。");
            return;
        }

        // 2. 命中已公开的示例密钥：拒绝启动
        if (FORBIDDEN_SECRETS.contains(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    "jwt.secret 使用了已公开的示例值，可被用于伪造任意用户（含管理员）令牌，"
                            + "请通过环境变量 JWT_SECRET 配置不少于 " + MIN_SECRET_BYTES + " 字节的随机密钥");
        }

        // 3. 长度不足：拒绝启动（HS256 要求 256 位）
        byte[] keyBytes = value.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret 长度不足：HS256 至少需要 " + MIN_SECRET_BYTES + " 字节，当前为 "
                            + keyBytes.length + " 字节，请重新生成更长的随机密钥");
        }

        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        log.info("JWT 签名密钥加载完成（来源：配置/环境变量，长度 {} 字节）", keyBytes.length);
    }

    /**
     * 生成签名密钥（延迟初始化，兼容未经过 Spring 生命周期的场景）
     */
    private SecretKey getSigningKey() {
        SecretKey key = this.signingKey;
        if (key == null) {
            synchronized (this) {
                if (this.signingKey == null) {
                    init();
                }
                key = this.signingKey;
            }
        }
        return key;
    }

    /**
     * 生成 Token
     *
     * @param username     用户名
     * @param userId       用户ID
     * @param tokenVersion 令牌版本（改密/登出后自增，用于吊销旧令牌）
     * @return JWT Token
     */
    public String generateToken(String username, Long userId, Integer tokenVersion) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("ver", tokenVersion == null ? 0 : tokenVersion);

        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .claims(claims)
                .subject(username)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 读取令牌内的版本号（历史令牌无该声明时视为 0）
     */
    public int getTokenVersion(String token) {
        Object value = parseToken(token).get("ver");
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    /**
     * 从 Token 中解析 Claims
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 获取用户名
     */
    public String getUsername(String token) {
        return parseToken(token).getSubject();
    }

    /**
     * 获取用户ID
     */
    public Long getUserId(String token) {
        Object userId = parseToken(token).get("userId");
        if (userId instanceof Integer) {
            return ((Integer) userId).longValue();
        }
        return (Long) userId;
    }

    /**
     * 验证 Token 是否有效
     */
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断 Token 是否过期
     */
    public boolean isTokenExpired(String token) {
        try {
            Date expiration = parseToken(token).getExpiration();
            return expiration.before(new Date());
        } catch (Exception e) {
            return true;
        }
    }
}
