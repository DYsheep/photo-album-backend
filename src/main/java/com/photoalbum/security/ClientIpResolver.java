package com.photoalbum.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 客户端来源地址解析（登录限流与审计日志共用同一实现）
 *
 * 安全说明：Nginx 以"追加"方式写入 X-Forwarded-For 时，该头最左侧字段由客户端提供、可任意伪造，
 * 因此必须取最右侧（由本机代理写入的）地址；取最左值会使攻击者通过每次伪造不同的头绕过限流，
 * 也会让审计日志记录到伪造来源。
 * 服务未部署在反向代理之后时，将 app.security.trust-proxy 设为 false，直接采用连接对端地址。
 */
@Component
public class ClientIpResolver {

    /** 是否信任反向代理写入的来源地址头 */
    @Value("${app.security.trust-proxy:true}")
    private boolean trustProxy;

    /** 解析客户端 IP（优先取代理链最右侧地址） */
    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        if (trustProxy) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String[] parts = forwarded.split(",");
                for (int i = parts.length - 1; i >= 0; i--) {
                    String candidate = parts[i].trim();
                    if (!candidate.isEmpty() && !"unknown".equalsIgnoreCase(candidate)) {
                        return candidate;
                    }
                }
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank() && !"unknown".equalsIgnoreCase(realIp)) {
                return realIp.trim();
            }
        }
        return request.getRemoteAddr();
    }
}
