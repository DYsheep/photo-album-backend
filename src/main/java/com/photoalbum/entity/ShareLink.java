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

    /** 到期时间（null = 永久有效） */
    private LocalDateTime expiresAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
