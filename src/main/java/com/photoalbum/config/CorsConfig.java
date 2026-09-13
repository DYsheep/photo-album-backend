package com.photoalbum.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * CORS 跨域配置
 *
 * 安全要求（修复 V06）：
 *   1. 来源改为白名单，不使用 "*" 通配；
 *   2. 关闭"允许携带凭证"——本系统令牌通过 Authorization 头传递，不使用 Cookie，
 *      而"任意来源 + 允许携带凭证"的组合会让任意第三方站点可携带凭证读取接口响应。
 * 同源请求（生产环境 Nginx 同时托管前端与 /api）不受该白名单影响。
 */
@Slf4j
@Configuration
public class CorsConfig {

    /** 允许跨域访问的来源白名单（逗号分隔，不含通配符） */
    @Value("${app.cors.allowed-origins:}")
    private String allowedOrigins;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        if (origins.isEmpty()) {
            // 未配置白名单时按"不允许任何跨域来源"处理（默认拒绝，避免误放开）
            log.warn("app.cors.allowed-origins 未配置，跨域请求将被拒绝；如需外部站点调用接口请显式配置白名单");
        } else {
            origins.forEach(config::addAllowedOrigin);
        }

        // 允许的请求头
        config.addAllowedHeader("*");
        // 允许的方法
        config.addAllowedMethod("*");
        // 不使用 Cookie 会话，关闭凭证携带
        config.setAllowCredentials(false);
        // 预检缓存时间
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
