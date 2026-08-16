package com.photoalbum.dto;

import lombok.Data;

import java.util.List;

/**
 * 照片请求/响应 DTO
 */
@Data
public class PhotoDTO {

    private Long id;

    private String title;
    private String description;
    private Long categoryId;
    private String url;             // 图片路径（响应用）
    private String thumbnailUrl;    // 缩略图路径（响应用）
    private String fileName;        // 原始文件名（响应用）
    private Long fileSize;          // 文件大小（响应用）
    private String tags;            // 标签
    private Integer isPrivate;       // 是否私密
    private Integer viewCount;      // 浏览量
    private Integer likeCount;      // 点赞数
    private String exifInfo;        // EXIF 信息（JSON，响应用）
    private String cameraModel;     // 相机型号
    private String aperture;        // 光圈值，如 f/2.8
    private String shutterSpeed;    // 快门速度，如 1/125s
    private String iso;             // ISO 感光度
    private String focalLength;     // 焦距，如 50mm
    private String dateTaken;       // 拍摄时间（EXIF 原始值）
    private Double gpsLatitude;     // GPS 纬度
    private Double gpsLongitude;    // GPS 经度
    private String categoryName;    // 分类名称（响应用，关联查询填充）

    // ===== 分页查询参数 =====
    private Integer pageNum = 1;
    private Integer pageSize = 10;
    private String keyword;         // 搜索关键词（标题/描述）
    private Long categoryIdFilter;  // 按分类筛选

    // ===== 批量删除 =====
    private List<Long> ids;

    // ===== 批量编辑 =====
    private String appendTags;      // 批量追加标签
}
