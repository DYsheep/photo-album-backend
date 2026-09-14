package com.photoalbum.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 关系元组（数据权限）
 *
 * 主体 × 关系 × 对象：relation=allow 允许，relation=deny 禁止（禁止优先于允许）；
 * 对象为 global 时表示全部私密内容；合集/分类/标签可级联到其内部照片。
 */
@Data
@TableName("t_auth_tuple")
public class AuthTuple {

    public static final String RELATION_ALLOW = "allow";
    public static final String RELATION_DENY = "deny";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String subjectType;
    private Long subjectId;
    private String relation;
    private String objectType;
    private Long objectId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private Long createdBy;
}
