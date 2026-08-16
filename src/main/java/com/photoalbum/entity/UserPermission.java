package com.photoalbum.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/**
 * 用户权限条目（白名单 / 黑名单）
 */
@Data
@TableName("t_user_permission")
public class UserPermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** W=白名单(仅允许), B=黑名单(排除) */
    private String permType;

    /** photo / collection */
    private String targetType;

    private Long targetId;
}
