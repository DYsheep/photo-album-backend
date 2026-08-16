package com.photoalbum.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 合集-照片关联实体
 */
@Data
@TableName("t_collection_photos")
public class PhotoCollectionPhoto {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long collectionId;

    private Long photoId;

    private Integer sortOrder;
}
