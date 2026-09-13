package com.photoalbum.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.photoalbum.common.PermissionConstants;
import com.photoalbum.common.UserRoles;
import com.photoalbum.entity.Photo;
import com.photoalbum.entity.PhotoCollection;
import com.photoalbum.entity.PhotoCollectionPhoto;
import com.photoalbum.entity.User;
import com.photoalbum.entity.UserPermission;
import com.photoalbum.mapper.PhotoCollectionPhotoMapper;
import com.photoalbum.mapper.UserPermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 访问策略：私密内容可见性的唯一实现
 *
 * 判定模型（默认拒绝）：
 *   1. admin：全部可见；
 *   2. 不具备"私密查看能力"的账号：任何私密内容都不可见；
 *   3. 具备能力的账号：由 t_user_permission 决定 ——
 *        · 白名单（W）定义可见范围：global=全部私密；photo/collection/category=指定对象
 *          （collection 级联到合集内照片，category 级联到该分类下的照片）
 *        · 黑名单（B）在上述范围内做排除
 *        · 无任何白名单条目 = 看不到任何私密内容
 *
 * 性能模型：
 *   · 授权条目按"请求"缓存，同一请求内多次判定只查一次授权表；
 *   · 合集/分类级授权在 SQL 中以子查询（EXISTS / IN 子查询）直接关联授权表表达，
 *     不把合集内照片 ID 捞到应用层再拼大 IN，授权规模增长时查询长度不随之膨胀；
 *   · 合集详情等"已知所属合集"的场景使用 canViewPhotoInCollection，逐张判定零查询。
 */
@Component
@RequiredArgsConstructor
public class AccessPolicy {

    /** 请求级缓存键前缀 */
    private static final String CACHE_KEY_PREFIX = "photo-album:access-scope:";

    private final UserPermissionMapper permMapper;
    private final PhotoCollectionPhotoMapper collectionPhotoMapper;
    private final com.photoalbum.mapper.CollectionMemberMapper collectionMemberMapper;
    private final com.photoalbum.mapper.TagMapper tagMapper;

    /**
     * 私密可见范围
     *
     * @param all          是否可见全部私密内容（管理员或 global 授权）
     * @param allowed*     白名单：定义可见范围（标签项同时保留 ID 与名称：ID 用于 SQL 子查询，名称用于内存精确匹配）
     * @param excluded*    黑名单：在可见范围内排除（all=true 时即"除这些以外全部可见"）
     */
    public record PrivateScope(boolean all,
                               Set<Long> allowedPhotoIds, Set<Long> allowedCollectionIds,
                               Set<Long> allowedCategoryIds, Set<Long> allowedTagIds, Set<String> allowedTagNames,
                               Set<Long> excludedPhotoIds, Set<Long> excludedCollectionIds,
                               Set<Long> excludedCategoryIds, Set<Long> excludedTagIds, Set<String> excludedTagNames) {

        public static PrivateScope none() {
            return new PrivateScope(false, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                    Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        }

        public static PrivateScope unrestricted() {
            return new PrivateScope(true, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                    Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        }

        public boolean hasGrants() {
            return !allowedPhotoIds.isEmpty() || !allowedCollectionIds.isEmpty()
                    || !allowedCategoryIds.isEmpty() || !allowedTagIds.isEmpty();
        }

        public boolean hasExclusions() {
            return !excludedPhotoIds.isEmpty() || !excludedCollectionIds.isEmpty()
                    || !excludedCategoryIds.isEmpty() || !excludedTagIds.isEmpty();
        }
    }

    // ========== 范围计算 ==========

    /** 照片维度的私密可见范围（合集与分类授权按各自维度保留，避免展开成巨量照片 ID） */
    public PrivateScope photoScope(User user) {
        PrivateScope cached = readCache("photo", user);
        if (cached != null) {
            return cached;
        }
        PrivateScope scope = computePhotoScope(user);
        writeCache("photo", user, scope);
        return scope;
    }

    /** 合集维度的私密可见范围（只认合集级授权与 global） */
    public PrivateScope collectionScope(User user) {
        PrivateScope cached = readCache("collection", user);
        if (cached != null) {
            return cached;
        }
        PrivateScope scope = computeCollectionScope(user);
        writeCache("collection", user, scope);
        return scope;
    }

    private PrivateScope computePhotoScope(User user) {
        if (UserRoles.isAdmin(user == null ? null : user.getRole())) {
            return PrivateScope.unrestricted();
        }
        if (!UserAuthorities.mayViewPrivate(user) || user == null) {
            return PrivateScope.none();
        }
        List<UserPermission> grants = grantsOf(user.getId());
        if (grants.isEmpty()) {
            return PrivateScope.none();
        }
        Set<Long> allowedTagIds = targetIds(grants, PermissionConstants.TYPE_WHITELIST, PermissionConstants.TARGET_TAG);
        Set<Long> excludedTagIds = targetIds(grants, PermissionConstants.TYPE_BLACKLIST, PermissionConstants.TARGET_TAG);
        return new PrivateScope(
                hasGlobalWhitelist(grants),
                targetIds(grants, PermissionConstants.TYPE_WHITELIST, PermissionConstants.TARGET_PHOTO),
                targetIds(grants, PermissionConstants.TYPE_WHITELIST, PermissionConstants.TARGET_COLLECTION),
                targetIds(grants, PermissionConstants.TYPE_WHITELIST, PermissionConstants.TARGET_CATEGORY),
                allowedTagIds,
                namesOfTags(allowedTagIds),
                targetIds(grants, PermissionConstants.TYPE_BLACKLIST, PermissionConstants.TARGET_PHOTO),
                targetIds(grants, PermissionConstants.TYPE_BLACKLIST, PermissionConstants.TARGET_COLLECTION),
                targetIds(grants, PermissionConstants.TYPE_BLACKLIST, PermissionConstants.TARGET_CATEGORY),
                excludedTagIds,
                namesOfTags(excludedTagIds));
    }

    private PrivateScope computeCollectionScope(User user) {
        if (UserRoles.isAdmin(user == null ? null : user.getRole())) {
            return PrivateScope.unrestricted();
        }
        if (!UserAuthorities.mayViewPrivate(user) || user == null) {
            return PrivateScope.none();
        }
        List<UserPermission> grants = grantsOf(user.getId());
        if (grants.isEmpty()) {
            return PrivateScope.none();
        }
        return new PrivateScope(
                hasGlobalWhitelist(grants),
                Set.of(),
                targetIds(grants, PermissionConstants.TYPE_WHITELIST, PermissionConstants.TARGET_COLLECTION),
                Set.of(), Set.of(), Set.of(),
                Set.of(),
                targetIds(grants, PermissionConstants.TYPE_BLACKLIST, PermissionConstants.TARGET_COLLECTION),
                Set.of(), Set.of(), Set.of());
    }

    /** 标签 ID → 标签名（用于内存精确匹配，避免每次判定都查库） */
    private Set<String> namesOfTags(Set<Long> tagIds) {
        if (tagIds.isEmpty()) {
            return Set.of();
        }
        return tagMapper.selectBatchIds(tagIds).stream()
                .map(com.photoalbum.entity.Tag::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ========== 查询条件注入 ==========

    /**
     * 把照片可见性条件追加到查询条件上（所有照片查询必须调用）
     *
     * 生成的私密条件形如：
     *   is_private = 0
     *   OR (is_private = 1 AND ( id IN (照片白名单…)
     *         OR category_id IN (SELECT target_id FROM t_user_permission WHERE …'category')
     *         OR EXISTS (SELECT 1 FROM t_collection_photos cp JOIN t_user_permission up …) ))
     */
    public void applyPhotoFilter(LambdaQueryWrapper<Photo> wrapper, User user) {
        PrivateScope scope = photoScope(user);
        if (scope.all() && !scope.hasExclusions()) {
            return;
        }
        String privateCondition = buildPrivateCondition(scope, user);
        if (privateCondition == null) {
            wrapper.eq(Photo::getIsPrivate, 0);
            return;
        }
        wrapper.and(w -> w.eq(Photo::getIsPrivate, 0).or().apply(privateCondition));
    }

    /** 把合集可见性条件追加到查询条件上（公开合集查询必须调用） */
    public void applyCollectionFilter(LambdaQueryWrapper<PhotoCollection> wrapper, User user) {
        PrivateScope scope = collectionScope(user);
        if (scope.all()) {
            if (scope.excludedCollectionIds().isEmpty()) {
                return;
            }
            wrapper.and(w -> w.eq(PhotoCollection::getIsPrivate, 0)
                    .or(w2 -> w2.eq(PhotoCollection::getIsPrivate, 1)
                            .notIn(PhotoCollection::getId, scope.excludedCollectionIds())));
            return;
        }
        if (!scope.hasGrants()) {
            wrapper.eq(PhotoCollection::getIsPrivate, 0);
            return;
        }
        wrapper.and(w -> {
            w.eq(PhotoCollection::getIsPrivate, 0).or().in(PhotoCollection::getId, scope.allowedCollectionIds());
            // 黑名单在白名单范围内做减法（AND 优先级高于 OR，等价于 is_private=0 OR (白名单 AND 非黑名单)）
            if (!scope.excludedCollectionIds().isEmpty()) {
                w.notIn(PhotoCollection::getId, scope.excludedCollectionIds());
            }
        });
    }

    /**
     * 构造私密照片的可见条件（不含最外层的 is_private = 0）
     *
     * 说明：SQL 中内联的 userId 与照片 ID 均来自数据库（Long 类型），不接受请求参数，不存在注入面。
     *
     * @return null 表示"无任何私密可见"（调用方应只查公开）
     */
    private String buildPrivateCondition(PrivateScope scope, User user) {
        if (scope.all()) {
            if (!scope.hasExclusions()) {
                return null;
            }
            List<String> exclusions = exclusionSql(scope, user);
            return exclusions.isEmpty() ? null
                    : "is_private = 1 AND " + String.join(" AND ", exclusions);
        }
        if (!scope.hasGrants()) {
            return null;
        }
        List<String> grants = new ArrayList<>();
        if (!scope.allowedPhotoIds().isEmpty()) {
            grants.add("id IN (" + joinIds(scope.allowedPhotoIds()) + ")");
        }
        if (!scope.allowedCategoryIds().isEmpty()) {
            grants.add("category_id IN (SELECT target_id FROM t_user_permission WHERE user_id = " + user.getId()
                    + " AND perm_type = '" + PermissionConstants.TYPE_WHITELIST + "' AND target_type = '"
                    + PermissionConstants.TARGET_CATEGORY + "')");
        }
        if (!scope.allowedCollectionIds().isEmpty()) {
            grants.add(collectionSubQuery(user.getId(), PermissionConstants.TYPE_WHITELIST));
        }
        if (!scope.allowedTagIds().isEmpty()) {
            grants.add(tagSubQuery(user.getId(), PermissionConstants.TYPE_WHITELIST));
        }
        String condition = "is_private = 1 AND (" + String.join(" OR ", grants) + ")";
        List<String> exclusions = exclusionSql(scope, user);
        if (!exclusions.isEmpty()) {
            condition += " AND " + String.join(" AND ", exclusions);
        }
        return condition;
    }

    /** 黑名单排除条件（在可见范围内做减法） */
    private List<String> exclusionSql(PrivateScope scope, User user) {
        List<String> exclusions = new ArrayList<>();
        if (!scope.excludedPhotoIds().isEmpty()) {
            exclusions.add("id NOT IN (" + joinIds(scope.excludedPhotoIds()) + ")");
        }
        if (!scope.excludedCategoryIds().isEmpty()) {
            // category_id 可能为 NULL，NOT IN 对 NULL 返回 UNKNOWN 会把无分类的照片一并排除，需显式放行
            exclusions.add("(category_id IS NULL OR category_id NOT IN (SELECT target_id FROM t_user_permission WHERE user_id = "
                    + user.getId() + " AND perm_type = '" + PermissionConstants.TYPE_BLACKLIST + "' AND target_type = '"
                    + PermissionConstants.TARGET_CATEGORY + "'))");
        }
        if (!scope.excludedCollectionIds().isEmpty()) {
            exclusions.add("NOT " + collectionSubQuery(user.getId(), PermissionConstants.TYPE_BLACKLIST));
        }
        if (!scope.excludedTagIds().isEmpty()) {
            exclusions.add("NOT " + tagSubQuery(user.getId(), PermissionConstants.TYPE_BLACKLIST));
        }
        return exclusions;
    }

    /**
     * 标签级授权的子查询（关联表 + 授权表；标签名不内联进 SQL，避免转义问题）
     */
    private String tagSubQuery(Long userId, String permType) {
        return "EXISTS (SELECT 1 FROM t_photo_tag pt"
                + " JOIN t_user_permission up ON up.target_id = pt.tag_id"
                + " AND up.user_id = " + userId
                + " AND up.perm_type = '" + permType + "'"
                + " AND up.target_type = '" + PermissionConstants.TARGET_TAG + "'"
                + " WHERE pt.photo_id = t_photo.id)";
    }

    /**
     * 合集级授权的子查询（直接关联授权表，不展开照片 ID）
     */
    private String collectionSubQuery(Long userId, String permType) {
        return "EXISTS (SELECT 1 FROM t_collection_photos cp"
                + " JOIN t_user_permission up ON up.target_id = cp.collection_id"
                + " AND up.user_id = " + userId
                + " AND up.perm_type = '" + permType + "'"
                + " AND up.target_type = '" + PermissionConstants.TARGET_COLLECTION + "'"
                + " WHERE cp.photo_id = t_photo.id)";
    }

    private String joinIds(Set<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    // ========== 单条判定 ==========

    /** 单张照片是否可见（合集归属未知时，最多一次小查询） */
    public boolean canViewPhoto(User user, Photo photo) {
        if (photo == null) {
            return false;
        }
        if (photo.getIsPrivate() == null || photo.getIsPrivate() == 0) {
            return true;
        }
        PrivateScope scope = photoScope(user);
        if (!scope.all() && !scope.hasGrants()) {
            return false;
        }
        if (isExcluded(scope, photo, null)) {
            return false;
        }
        if (scope.all()) {
            return true;
        }
        if (isGranted(scope, photo, null)) {
            return true;
        }
        // 合集级授权：查询该照片是否命中授权的合集（集合本身很小，仅在有合集级授权时才查询）
        if (!scope.allowedCollectionIds().isEmpty()
                && inAnyCollection(photo.getId(), scope.allowedCollectionIds())) {
            return true;
        }
        return false;
    }

    /**
     * 单张照片是否可见（已知其所属合集）
     *
     * 合集详情等场景使用：逐张判定全程内存完成，不产生数据库查询。
     */
    public boolean canViewPhotoInCollection(User user, Photo photo, Long collectionId) {
        if (photo == null) {
            return false;
        }
        if (photo.getIsPrivate() == null || photo.getIsPrivate() == 0) {
            return true;
        }
        PrivateScope scope = photoScope(user);
        if (!scope.all() && !scope.hasGrants()) {
            return false;
        }
        if (isExcluded(scope, photo, collectionId)) {
            return false;
        }
        return scope.all() || isGranted(scope, photo, collectionId);
    }

    /** 单个合集是否可见（仅看私密标记） */
    public boolean canViewCollection(User user, PhotoCollection collection) {
        if (collection == null) {
            return false;
        }
        if (collection.getIsPrivate() == null || collection.getIsPrivate() == 0) {
            return true;
        }
        PrivateScope scope = collectionScope(user);
        if (scope.all()) {
            return !scope.excludedCollectionIds().contains(collection.getId());
        }
        return scope.allowedCollectionIds().contains(collection.getId());
    }

    /**
     * 合集整体能否被访问（同时考虑未发布草稿）
     *
     * 可见者：具备管理权限的账号（后台合集管理）、该合集的协作者（可维护自己负责的合集），
     * 以及通过可见性判定的访客（仅限已发布合集）。
     */
    public boolean canAccessCollection(User user, PhotoCollection collection) {
        if (collection == null) {
            return false;
        }
        if (UserAuthorities.hasManageAccess(user)) {
            return true;
        }
        if (user != null && user.getId() != null && collection.getId() != null
                && collectionMemberMapper.selectCount(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.photoalbum.entity.CollectionMember>()
                                .eq(com.photoalbum.entity.CollectionMember::getCollectionId, collection.getId())
                                .eq(com.photoalbum.entity.CollectionMember::getUserId, user.getId())) > 0) {
            return true;
        }
        if (collection.getIsPublished() == null || collection.getIsPublished() != 1) {
            return false;
        }
        return canViewCollection(user, collection);
    }

    // ========== 内部工具 ==========

    /** 是否命中授权（照片级 / 分类级 / 标签级 / 已知合集级） */
    private boolean isGranted(PrivateScope scope, Photo photo, Long collectionId) {
        if (photo.getId() != null && scope.allowedPhotoIds().contains(photo.getId())) {
            return true;
        }
        if (photo.getCategoryId() != null && scope.allowedCategoryIds().contains(photo.getCategoryId())) {
            return true;
        }
        if (matchesTag(photo.getTags(), scope.allowedTagNames())) {
            return true;
        }
        return collectionId != null && scope.allowedCollectionIds().contains(collectionId);
    }

    /** 是否命中排除项 */
    private boolean isExcluded(PrivateScope scope, Photo photo, Long collectionId) {
        if (photo.getId() != null && scope.excludedPhotoIds().contains(photo.getId())) {
            return true;
        }
        if (photo.getCategoryId() != null && scope.excludedCategoryIds().contains(photo.getCategoryId())) {
            return true;
        }
        if (matchesTag(photo.getTags(), scope.excludedTagNames())) {
            return true;
        }
        if (collectionId != null) {
            return scope.excludedCollectionIds().contains(collectionId);
        }
        return !scope.excludedCollectionIds().isEmpty()
                && inAnyCollection(photo.getId(), scope.excludedCollectionIds());
    }

    /**
     * 照片标签串是否命中给定标签名（按逗号精确匹配，避免子串误判，例如"山"不应命中"山水"）
     */
    private boolean matchesTag(String tagsCsv, Set<String> names) {
        if (tagsCsv == null || tagsCsv.isBlank() || names.isEmpty()) {
            return false;
        }
        for (String part : tagsCsv.split(",")) {
            String name = part.trim();
            if (!name.isEmpty() && names.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean inAnyCollection(Long photoId, Set<Long> collectionIds) {
        if (photoId == null || collectionIds.isEmpty()) {
            return false;
        }
        return collectionPhotoMapper.selectCount(new LambdaQueryWrapper<PhotoCollectionPhoto>()
                .eq(PhotoCollectionPhoto::getPhotoId, photoId)
                .in(PhotoCollectionPhoto::getCollectionId, collectionIds)) > 0;
    }

    private List<UserPermission> grantsOf(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return permMapper.selectList(
                new LambdaQueryWrapper<UserPermission>().eq(UserPermission::getUserId, userId));
    }

    private boolean hasGlobalWhitelist(List<UserPermission> grants) {
        return grants.stream().anyMatch(p ->
                PermissionConstants.TARGET_GLOBAL.equals(p.getTargetType())
                        && PermissionConstants.TYPE_WHITELIST.equals(p.getPermType()));
    }

    /** 取指定授权类型的 target_id 集合 */
    private Set<Long> targetIds(List<UserPermission> grants, String permType, String targetType) {
        Set<Long> ids = new LinkedHashSet<>();
        for (UserPermission grant : grants) {
            if (permType.equals(grant.getPermType())
                    && targetType.equals(grant.getTargetType())
                    && grant.getTargetId() != null) {
                ids.add(grant.getTargetId());
            }
        }
        return ids;
    }

    // ========== 请求级缓存 ==========

    private PrivateScope readCache(String dimension, User user) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null || user == null || user.getId() == null) {
            return null;
        }
        Object value = attributes.getAttribute(cacheKey(dimension, user.getId()), RequestAttributes.SCOPE_REQUEST);
        return value instanceof PrivateScope scope ? scope : null;
    }

    private void writeCache(String dimension, User user, PrivateScope scope) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null || user == null || user.getId() == null) {
            return;
        }
        attributes.setAttribute(cacheKey(dimension, user.getId()), scope, RequestAttributes.SCOPE_REQUEST);
    }

    private String cacheKey(String dimension, Long userId) {
        return CACHE_KEY_PREFIX + dimension + ":" + userId;
    }

    /** 供测试与诊断使用：清空当前请求的缓存 */
    public void evictCache() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return;
        }
        attributes.removeAttribute(CACHE_KEY_PREFIX + "photo:" + "*", RequestAttributes.SCOPE_REQUEST);
        attributes.removeAttribute(CACHE_KEY_PREFIX + "collection:" + "*", RequestAttributes.SCOPE_REQUEST);
    }
}
