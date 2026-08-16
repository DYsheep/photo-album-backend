package com.photoalbum.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 分类请求/响应 DTO
 */
@Data
public class CategoryDTO {

    private Long id;

    @NotBlank(message = "分类名称不能为空")
    private String name;

    private String description;
    private Integer sortOrder;
}
