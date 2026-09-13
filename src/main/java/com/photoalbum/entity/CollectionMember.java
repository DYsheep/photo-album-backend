package com.photoalbum.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 合集协作者（对象级管理权）
 *
 * 语义：被指派的账号可维护"这一个合集"（改名、封面、增删合集内照片），而不具备全站管理权。
 * 这是把管理权从"全站能力位"细化到"具体对象"的最小实现；后续如需只读成员，
 * 可在 member_role 上扩展（当前仅 editor）。
 */
@Data
@TableName("t_collection_member")
public class CollectionMember {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long collectionId;

    private Long userId;

    /** 成员角色：editor=可维护该合集 */
    private String memberRole;

    /** 指派操作人用户 ID */
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
