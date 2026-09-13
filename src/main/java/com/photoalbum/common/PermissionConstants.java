package com.photoalbum.common;

/**
 * 授权条目取值定义（t_user_permission）
 *
 * 语义（默认拒绝模型）：
 *   · 白名单（W）定义"可见范围"：global = 全部私密内容；photo/collection/category = 指定对象
 *     （collection 级联到合集内照片，category 级联到该分类下的照片）
 *   · 黑名单（B）在上述范围内做排除（收敛权限用）
 *   · 既无白名单也无 global 条目时，任何私密内容都不可见
 *
 * 扩展新授权维度时只需：新增 target_type 常量 → isValidTargetType 放行 → AccessPolicy 增加对应分支，
 * 角色与权限标记无需改动。
 */
public final class PermissionConstants {

    /** 白名单：定义可见范围 */
    public static final String TYPE_WHITELIST = "W";

    /** 黑名单：在可见范围内排除 */
    public static final String TYPE_BLACKLIST = "B";

    /** 授权对象：照片 */
    public static final String TARGET_PHOTO = "photo";

    /** 授权对象：合集（级联到合集内照片） */
    public static final String TARGET_COLLECTION = "collection";

    /** 授权对象：分类（级联到该分类下的照片） */
    public static final String TARGET_CATEGORY = "category";

    /** 授权对象：标签（级联到带该标签的照片） */
    public static final String TARGET_TAG = "tag";

    /** 授权对象：全部私密内容 */
    public static final String TARGET_GLOBAL = "global";

    /**
     * global 条目的 target_id 占位值
     * 使用 0 而非 NULL，使 target_id 可保持 NOT NULL 并参与唯一约束，从数据库层杜绝重复授权
     */
    public static final long TARGET_GLOBAL_ID = 0L;

    private PermissionConstants() {
    }

    public static boolean isValidPermType(String permType) {
        return TYPE_WHITELIST.equals(permType) || TYPE_BLACKLIST.equals(permType);
    }

    public static boolean isValidTargetType(String targetType) {
        return TARGET_PHOTO.equals(targetType)
                || TARGET_COLLECTION.equals(targetType)
                || TARGET_CATEGORY.equals(targetType)
                || TARGET_TAG.equals(targetType)
                || TARGET_GLOBAL.equals(targetType);
    }

    /**
     * 校验授权条目取值，非法即抛出业务异常
     */
    public static void requireValid(String permType, String targetType) {
        if (!isValidPermType(permType)) {
            throw new BusinessException(400, "授权类型非法，仅支持 W(白名单)/B(黑名单)");
        }
        if (!isValidTargetType(targetType)) {
            throw new BusinessException(400, "授权对象类型非法，仅支持 photo/collection/category/global");
        }
        if (TARGET_GLOBAL.equals(targetType) && !TYPE_WHITELIST.equals(permType)) {
            throw new BusinessException(400, "global 仅支持白名单（全部私密内容）");
        }
    }
}
