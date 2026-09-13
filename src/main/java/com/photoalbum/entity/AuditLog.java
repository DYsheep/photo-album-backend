package com.photoalbum.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作审计日志（授权与用户管理相关的关键操作）
 *
 * 记录"谁在什么时候对谁做了什么"，用于追溯授权变更与账号管理动作。
 * 该表只增不改：审计记录不随业务数据删除而删除。
 */
@Data
@TableName("t_audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人用户 ID（系统自动动作时为 null） */
    private Long actorId;

    /** 操作人用户名（冗余保存，避免用户删除后无法追溯） */
    private String actorName;

    /** 动作标识，如 GRANT_ADD / GRANT_REMOVE / USER_CREATE / USER_UPDATE / USER_DELETE */
    private String action;

    /** 操作对象类型：permission / user */
    private String targetType;

    /** 操作对象 ID */
    private Long targetId;

    /** 操作详情（人类可读，不含口令等敏感信息） */
    private String detail;

    /** 来源 IP */
    private String ip;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
