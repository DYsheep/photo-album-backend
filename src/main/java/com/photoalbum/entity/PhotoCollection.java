package com.photoalbum.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 照片合集实体
 */
@Data
@TableName("t_collection")
public class PhotoCollection {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    private Long coverPhotoId;

    private Integer sortOrder;

    private Integer isPublished;

    private Integer isPrivate;       // 是否私密（0=公开 1=私密）

    private Long createdBy;   // 创建人（数据范围 OWN 判定）

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
