package com.photoalbum.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 照片实体
 */
@Data
@TableName("t_photo")
public class Photo {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;
    private String description;
    private Long categoryId;       // 关联分类 ID
    private String url;             // 图片存储路径（相对）
    private String thumbnailUrl;    // 缩略图路径（相对）
    private String fileName;        // 原始文件名
    private Long fileSize;          // 文件大小(bytes)
    private String tags;            // 标签（逗号分隔）
    private Integer isPrivate;       // 是否私密（0=公开 1=私密）
    private Integer viewCount;      // 浏览量
    private Integer likeCount;      // 点赞数
    private String exifInfo;        // EXIF 信息（JSON 完整数据）
    private String cameraModel;     // 相机型号
    private String aperture;        // 光圈值，如 f/2.8
    private String shutterSpeed;    // 快门速度，如 1/125s
    private String iso;             // ISO 感光度
    private String focalLength;     // 焦距，如 50mm
    private String dateTaken;       // 拍摄时间（EXIF 原始值）
    private Double gpsLatitude;     // GPS 纬度
    private Double gpsLongitude;    // GPS 经度

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
