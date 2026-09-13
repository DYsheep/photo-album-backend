package com.photoalbum.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体
 *
 * 权限模型：角色（role）仅作标识，实际能力由能力位（canUpload / canManage / canViewPrivate）决定。
 * tokenVersion 用于令牌吊销：修改口令或退出登录时自增，使已签发的令牌立即失效。
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
    private String role;              // 角色标识：admin / viewer / user（仅作标识与展示）
    private Integer canUpload;        // 0=不可上传 1=可上传
    private Integer canManage;        // 0=不可管理 1=可管理（创建合集/编辑删除照片）
    private Integer canViewPrivate;   // 0=不可见私密 1=可见被授权的私密内容（需配合 t_user_permission）
    private Integer tokenVersion;     // 令牌版本，改密/登出时自增以吊销旧令牌

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
