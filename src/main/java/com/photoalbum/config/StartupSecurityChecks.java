package com.photoalbum.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Locale;

/**
 * 启动期安全自检（P1-9）
 *
 * 只拦截"用了默认值就会以高危姿态上线"的配置，且**仅在生产 profile 下生效**：
 * 本地开发常用 root + 空口令，强行拦截会让开发环境起不来。
 *
 * 触发条件（满足其一即启用严格校验）：
 *   · 激活的 profile 含 prod / production
 *   · 显式设置 app.security.strict-startup=true（可让预发环境也纳入校验）
 *
 * 校验项：
 *   1. spring.datasource.password 不能为空 —— 默认值 ${DB_PASSWORD:} 为空串，
 *      漏配环境变量时应用会静默以 root 空口令连库，并由 Flyway 执行 DDL；
 *      该账号能直接改 t_user.role，等于整库失守。
 *   2. jwt.secret 不能为空 —— 空值时 JwtUtil 会生成一次性随机密钥，
 *      多实例/滚动发布下表现为随机 401，容易被运维用"填个短字符串"绕过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupSecurityChecks implements InitializingBean {

    private final Environment environment;

    @Override
    public void afterPropertiesSet() {
        if (!strict()) {
            return;
        }
        requireNonBlank("spring.datasource.password", "DB_PASSWORD",
                "生产环境禁止使用空数据库口令（默认值即空串），请通过环境变量 DB_PASSWORD 注入");
        requireNonBlank("jwt.secret", "JWT_SECRET",
                "生产环境必须显式注入 JWT 密钥，否则每次重启会生成一次性随机密钥，多实例间令牌互不通用");

        String dbUser = environment.getProperty("spring.datasource.username", "");
        if ("root".equalsIgnoreCase(dbUser)) {
            log.warn("生产环境正在以 root 连接数据库，建议改用最小权限账号（仅需目标库的 DML/DDL 权限）");
        }
        log.info("启动期安全自检通过（profiles={}）", Arrays.toString(environment.getActiveProfiles()));
    }

    /** 是否启用严格校验 */
    private boolean strict() {
        boolean strictFlag = environment.getProperty("app.security.strict-startup", Boolean.class, false);
        if (strictFlag) {
            return true;
        }
        for (String profile : environment.getActiveProfiles()) {
            String normalized = profile.toLowerCase(Locale.ROOT);
            if ("prod".equals(normalized) || "production".equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private void requireNonBlank(String property, String envName, String message) {
        String value = environment.getProperty(property, "");
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(String.format(
                    "%s（当前 %s 为空）。若确需在无代理/无口令环境下启动，请显式设置 app.security.strict-startup=false 并记录风险。",
                    message, property));
        }
    }
}
