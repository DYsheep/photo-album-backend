package com.photoalbum.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.photoalbum.entity.AuditLog;
import com.photoalbum.entity.User;
import com.photoalbum.mapper.AuditLogMapper;
import com.photoalbum.security.ClientIpResolver;
import com.photoalbum.security.CurrentUserSupport;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * 操作审计服务
 *
 * 设计要点：
 *   1. 审计写入失败绝不抛出异常（审计不能因为自身故障阻断业务操作）；
 *   2. 操作人与其用户名一并冗余保存，用户被删除后仍可追溯；
 *   3. 详情字段禁止写入口令等敏感信息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    /** 动作：新增授权条目 */
    public static final String ACTION_GRANT_ADD = "GRANT_ADD";
    /** 动作：删除授权条目 */
    public static final String ACTION_GRANT_REMOVE = "GRANT_REMOVE";
    /** 动作：创建用户 */
    public static final String ACTION_USER_CREATE = "USER_CREATE";
    /** 动作：修改用户（昵称/角色/功能位/口令） */
    public static final String ACTION_USER_UPDATE = "USER_UPDATE";
    /** 动作：删除用户 */
    public static final String ACTION_USER_DELETE = "USER_DELETE";
    /** 动作：登录成功 */
    public static final String ACTION_LOGIN_SUCCESS = "LOGIN_SUCCESS";
    /** 动作：登录失败（口令错误或触发限流） */
    public static final String ACTION_LOGIN_FAIL = "LOGIN_FAIL";

    /** 对象类型：授权条目 */
    public static final String TARGET_PERMISSION = "permission";
    /** 对象类型：用户 */
    public static final String TARGET_USER = "user";
    /** 对象类型：认证事件 */
    public static final String TARGET_AUTH = "auth";

    private final AuditLogMapper auditLogMapper;
    private final ClientIpResolver clientIpResolver;

    /**
     * 记录一条管理操作审计（操作人取当前登录用户）
     */
    public void record(String action, String targetType, Long targetId, String detail) {
        try {
            User actor = CurrentUserSupport.getCurrentUser();
            save(actor != null ? actor.getId() : null,
                    actor != null && actor.getUsername() != null ? actor.getUsername() : "system",
                    action, targetType, targetId, detail);
        } catch (Exception e) {
            log.warn("审计日志写入失败（不影响业务）: action={}, detail={}, err={}", action, detail, e.getMessage());
        }
    }

    /**
     * 记录一条认证事件审计（登录成功/失败）
     *
     * 登录失败时尚无登录态，操作主体是"被尝试的账号"，因此由调用方显式传入。
     */
    public void recordAuth(String action, String username, Long actorId, String detail) {
        try {
            save(actorId, username != null && !username.isBlank() ? username : "unknown",
                    action, TARGET_AUTH, actorId, detail);
        } catch (Exception e) {
            log.warn("认证审计写入失败（不影响登录流程）: action={}, username={}, err={}",
                    action, username, e.getMessage());
        }
    }

    private void save(Long actorId, String actorName, String action,
                      String targetType, Long targetId, String detail) {
        AuditLog entry = new AuditLog();
        entry.setActorId(actorId);
        entry.setActorName(actorName);
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setDetail(detail);
        entry.setIp(resolveIp());
        auditLogMapper.insert(entry);
    }

    /** 最近的操作记录（按时间倒序，最多 200 条） */
    public List<AuditLog> recent(int size) {
        int limit = Math.min(Math.max(size, 1), 200);
        return auditLogMapper.selectList(new LambdaQueryWrapper<AuditLog>()
                .orderByDesc(AuditLog::getCreatedAt)
                .orderByDesc(AuditLog::getId)
                .last("LIMIT " + limit));
    }

    private String resolveIp() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return null;
            }
            HttpServletRequest request = attributes.getRequest();
            return clientIpResolver.resolve(request);
        } catch (Exception e) {
            return null;
        }
    }
}
