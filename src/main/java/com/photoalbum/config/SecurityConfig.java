package com.photoalbum.config;

import com.photoalbum.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 禁用 CSRF（JWT 无状态，不需要）
            .csrf(AbstractHttpConfigurer::disable)

            // 配置请求授权规则
            .authorizeHttpRequests(auth -> auth
                // 放行：Knife4j 接口文档页面
                .requestMatchers("/doc.html", "/webjars/**", "/v3/api-docs/**", "/swagger-resources/**").permitAll()
                // 放行：登录接口（POST only）+ 静态资源（GET only）
                .requestMatchers("/api/auth/login", "/api/auth/logout").permitAll()
                .requestMatchers(HttpMethod.GET, "/files/**").permitAll()
                // 放行：分享链接公开访问
                .requestMatchers("/api/share/**").permitAll()
                // 放行：公开读取接口（照片列表、详情、分类列表、标签、统计、合集、地图）
                .requestMatchers(
                    org.springframework.http.HttpMethod.GET,
                    "/api/photos",
                    "/api/photos/*",
                    "/api/photos/*/adjacent",
                    "/api/photos/tags",
                    "/api/photos/stats",
                    "/api/photos/gps",
                    "/api/categories",
                    "/api/collections",
                    "/api/collections/*"
                ).permitAll()
                // 放行：点赞接口（任何游客可点）
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/photos/*/like").permitAll()
                // 管理后台接口仅限 admin 角色
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // 其余 /api/** 接口均需认证（任何已登录用户）
                .requestMatchers("/api/**").authenticated()
                // 其他路径放行
                .anyRequest().permitAll()
            )

            // 注册 JWT 过滤器（在 UsernamePasswordFilter 之前）
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * BCrypt 密码编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
