package com.photoalbum.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 年度海报的 AI 素材缓存（背景图 + 一句文案）
 *
 * 一年一行，避免同一年的背景被重复生成（图像模型按张计费）。
 */
@Data
@TableName("t_poster_ai")
public class PosterAi {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Integer year;
    private String backgroundUrl;
    private String backgroundPrompt;
    private String copyText;
    private String imageModel;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
