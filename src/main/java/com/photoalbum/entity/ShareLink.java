package com.photoalbum.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分享链接实体
 *
 * 时效语义：expiresAt 为 null 表示永久有效；到期后接口按"链接不存在或已失效"处理。
 * 访问时还会动态校验照片当前可见性（照片为私密时，链接对无权限访问者不可用）。
 */
@Data
@TableName("t_share_link")
public class ShareLink {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;

    private Long photoId;

    /** 分享对象类型：photo / collection */
    private String targetType;

    /** 分享对象 ID（照片或合集） */
    private Long targetId;

    /** 合集分享是否包含私密照片（0=仅公开 1=包含） */
    private Integer includePrivate;

    /** 访问口令（BCrypt 哈希；NULL 表示无需口令） */
    private String accessCode;

    /** 到期时间（null = 永久有效） */
    private LocalDateTime expiresAt;

    /** 创建人用户 ID（合集分享可见性判定依据） */
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
