package com.photoalbum.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/**
 * 照片-标签关联（替代逗号分隔字符串做标签管理、统计与标签级授权）
 */
@Data
@TableName("t_photo_tag")
public class PhotoTag {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long photoId;

    private Long tagId;
}
