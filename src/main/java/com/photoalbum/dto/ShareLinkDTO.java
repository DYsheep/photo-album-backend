package com.photoalbum.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分享链接请求/响应 DTO
 */
@Data
public class ShareLinkDTO {

    /** 分享对象类型：photo / collection（前端据此分派渲染） */
    private String targetType;

    /** 合集分享：合集 ID / 名称 / 描述 */
    private Long collectionId;
    private String collectionName;
    private String collectionDescription;

    /** 合集分享：是否包含私密照片 */
    private Integer includePrivate;

    /** 是否需要访问口令（不返回哈希本身） */
    private Boolean requiresAccessCode;

    /** 合集分享：照片列表（照片分享时为空） */
    private java.util.List<PhotoDTO> photos;

    private Long id;

    private String code;

    private Long photoId;

    private LocalDateTime createdAt;

    /** 到期时间（null = 永久有效） */
    private LocalDateTime expiresAt;

    /** 是否已过期（响应中直接给出，便于前端展示状态） */
    private Boolean expired;

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
