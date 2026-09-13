package com.photoalbum.dto;

import lombok.Data;

/**
 * 用户创建/修改请求
 *
 * 采用 DTO 而非逐参数传递：新增能力位（如后续的"可发布合集"）时只需在此增加字段，
 * 不必改动服务接口签名与调用方。
 */
@Data
public class UserUpsertDTO {

    private String username;

    /** 留空表示不修改口令（创建时必填） */
    private String password;

    private String nickname;

    /** 角色标识：admin / viewer / user */
    private String role;

    /** 能力位：可上传照片 */
    private Integer canUpload;

    /** 能力位：可管理内容（照片改删、分类、标签、合集、分享） */
    private Integer canManage;

    /** 能力位：可查看被授权的私密内容（需配合 t_user_permission 逐项授权） */
    private Integer canViewPrivate;
}
