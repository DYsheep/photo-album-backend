package com.photoalbum.dto;

import lombok.Data;

import java.util.List;

/**
 * 合集请求/响应 DTO
 */
@Data
public class CollectionDTO {

    /** 合集 ID（更新/响应时用） */
    private Long id;

    /** 合集名称 */
    private String name;

    /** 合集描述 */
    private String description;

    /** 封面照片 ID */
    private Long coverPhotoId;

    /** 排序序号 */
    private Integer sortOrder;

    /** 是否发布（1=发布, 0=草稿） */
    private Integer isPublished;

    /** 是否私密（0=公开, 1=私密） */
    private Integer isPrivate;

    /** 合集内照片数量（响应用） */
    private Integer photoCount;

    /** 封面图 URL（响应用） */
    private String coverUrl;

    /** 合集包含的照片列表（详情响应用） */
    private List<PhotoDTO> photos;
}
