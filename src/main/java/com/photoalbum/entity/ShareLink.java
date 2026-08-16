package com.photoalbum.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分享链接实体
 */
@Data
@TableName("t_share_link")
public class ShareLink {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;

    private Long photoId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
