package com.photoalbum.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户权限条目（白名单 / 黑名单）
 *
 * 语义（默认拒绝模型）：
 *   · 白名单（W）定义可见范围：global=全部私密内容；photo/collection=指定对象（合集级联到内部照片）
 *   · 黑名单（B）在可见范围内做排除
 *   · 无任何白名单条目时，看不到任何私密内容
 */
@Data
@TableName("t_user_permission")
public class UserPermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** W=白名单(定义可见范围), B=黑名单(在范围内排除) */
    private String permType;

    /** photo / collection / global */
    private String targetType;

    private Long targetId;

    /** 授权时间（DB 默认当前时间） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 授权操作人（管理员用户 ID），用于审计 */
    private Long createdBy;
}
