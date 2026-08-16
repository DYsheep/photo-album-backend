package com.photoalbum.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分享链接请求/响应 DTO
 */
@Data
public class ShareLinkDTO {

    private Long id;

    private String code;

    private Long photoId;

    private LocalDateTime createdAt;

    /** 完整分享链接，如 https://域名/share/XXXX */
    private String shareUrl;

    /** 关联照片标题 */
    private String photoTitle;

    /** 关联照片访问 URL */
    private String photoUrl;

    // ===== 照片 EXIF 信息（公开分享页用） =====
    private String title;
    private String description;
    private String cameraModel;
    private String aperture;
    private String shutterSpeed;
    private String iso;
    private String focalLength;
    private String dateTaken;
}
