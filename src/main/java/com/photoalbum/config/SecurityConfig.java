package com.photoalbum.config;

import com.photoalbum.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置
 *
 * 权限口径：
 *   · 路径级规则（本类）负责粗粒度边界；
 *   · 方法级 @PreAuthorize 负责具体操作的细粒度校验（权限标记由 UserAuthorities 统一派生）；
 *   两者都是声明式的，避免"新增接口忘了手工调用权限方法"这类遗漏。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
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
                // 放行：分享链接的公开访问（GET 查询 + 卡片封面；创建分享必须登录，见 ShareController）
                .requestMatchers(HttpMethod.GET, "/api/share/**").permitAll()
                // 后台仪表盘统计只给登录用户（M07）。
                // 注意：必须排在下面的 /api/photos/* 通配之前，否则会被 permitAll 吞掉——
                // 此前只有注释没有规则，实际仍对匿名开放（匿名可读照片数、存储用量、器材分布）。
                .requestMatchers(HttpMethod.GET, "/api/photos/stats").authenticated()
                // 放行：公开读取接口（照片列表、详情、分类列表、标签、合集、地图）
                // 注意：/api/photos/stats 已从本清单移除（修复 M07）——它只被后台仪表盘调用，
                // 匿名可读会泄露站点规模、存储用量、器材与年份分布等运营数据，现改为需登录。
                .requestMatchers(
                    org.springframework.http.HttpMethod.GET,
                    "/api/photos",
                    "/api/photos/*",
                    "/api/photos/*/adjacent",
                    "/api/photos/tags",
                    "/api/photos/gps",
                    "/api/categories",
                    "/api/collections",
                    "/api/collections/*"
                ).permitAll()
                // 放行：点赞接口（任何游客可点）
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/photos/*/like").permitAll()
                // 用户与授权管理仅限管理员（与 UserAuthorities.USER_MANAGE 对应）
                .requestMatchers("/api/admin/users/**").hasAuthority("user:manage")
                // 合集列表：任何登录账号可访问，服务层对非管理者按"协作者"关系过滤（对象级管理权）
                .requestMatchers(HttpMethod.GET, "/api/admin/collections").authenticated()
                // 其余后台管理接口：管理员或具备管理权限的账号（canManage）
                .requestMatchers("/api/admin/**").hasAuthority("admin:access")
                // 其余 /api/** 接口均需认证（任何已登录用户）
                .requestMatchers("/api/**").authenticated()
                // 分享页 SSR（后端渲染 og 卡片元数据，供社交平台抓取）
                .requestMatchers(HttpMethod.GET, "/share/**").permitAll()
                // Spring Boot 错误分发入口
                .requestMatchers("/error").permitAll()
                // 其他路径一律拒绝：此前是 permitAll，将来任何新增端点（如 actuator）
                // 都会默认对外裸奔。真实静态资源由 Nginx 托管，不经过本应用。
                .anyRequest().denyAll()
            )

            // 未登录 / 令牌过期 → 401，已登录但权限不足 → 403
            // Spring Security 默认未登录也返回 403（Http403ForbiddenEntryPoint），
            // 前端无法区分"会话过期"与"权限不足"：令牌过期的用户会一直看到
            // "Request failed with status code 403"，页面看起来还处于登录状态。
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) ->
                        writeJsonError(response, 401, "未登录或登录已过期，请重新登录"))
                .accessDeniedHandler((request, response, accessDeniedException) ->
                        writeJsonError(response, 403, "无权限执行该操作"))
            )

            // 注册 JWT 过滤器（在 UsernamePasswordFilter 之前）
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 以统一 Result 结构返回鉴权失败信息
     *
     * 消息为固定文案（不含用户输入），无需 JSON 转义。
     */
    private void writeJsonError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":" + status + ",\"message\":\"" + message + "\",\"data\":null}");
    }

    /**
     * BCrypt 密码编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
