package com.photoalbum.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体
 */
@Data
@TableName("t_user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;
    private String password;
    private String nickname;
    private String avatar;
    private String role;        // admin / viewer / user
    private Integer canUpload;  // 0=不可上传 1=可上传
    private Integer canManage;  // 0=不可管理 1=可管理（创建合集/编辑删除照片）

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
