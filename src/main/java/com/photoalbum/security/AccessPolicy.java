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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 访问策略：私密内容可见性的唯一实现
 *
 * 此前同一套规则被复制到照片列表、合集列表、合集内照片、标签、统计等多处，口径已经出现不一致
 * （例如"合集白名单"在照片列表会级联到照片、在合集详情不会）。本类收敛为唯一实现，所有查询与单条判定
 * 都必须经过这里。
 *
 * 判定模型（默认拒绝）：
 *   1. admin：全部可见；
 *   2. 非 viewer 角色（user）：任何私密内容都不可见；
 *   3. viewer：由 t_user_permission 决定 ——
 *        · 白名单（W）定义可见范围：global=全部私密；photo/collection=指定对象（合集级联到内部照片）
 *        · 黑名单（B）在上述范围内做排除
 *        · 无任何白名单条目 = 看不到任何私密内容（旧行为是"看不到限制=看到全部"，已按最小权限原则反转）
 */
@Component
@RequiredArgsConstructor
public class AccessPolicy {

    private final UserPermissionMapper permMapper;
    private final PhotoCollectionPhotoMapper collectionPhotoMapper;

    /**
     * 私密可见范围
     *
     * @param all     是否可见全部私密内容
     * @param allowed all=false 时生效：可见的照片/合集 ID 集合
     * @param denied  all=true 时生效：需要排除的照片/合集 ID 集合
     */
    public record PrivateScope(boolean all, Set<Long> allowed, Set<Long> denied) {

        /** 任何私密内容都不可见 */
        public static PrivateScope none() {
            return new PrivateScope(false, Set.of(), Set.of());
        }

        /** 全部私密内容可见（管理员或 global 授权） */
        public static PrivateScope unrestricted() {
            return new PrivateScope(true, Set.of(), Set.of());
        }
    }

    // ========== 范围计算 ==========

    /** 照片维度的私密可见范围（合集授权级联到合集内照片） */
    public PrivateScope photoScope(User user) {
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
        if (hasGlobalWhitelist(grants)) {
            return new PrivateScope(true, Set.of(), photoIds(grants, PermissionConstants.TYPE_BLACKLIST));
        }
        Set<Long> allowed = photoIds(grants, PermissionConstants.TYPE_WHITELIST);
        allowed.removeAll(photoIds(grants, PermissionConstants.TYPE_BLACKLIST));
        return new PrivateScope(false, allowed, Set.of());
    }

    /** 合集维度的私密可见范围（只认合集级授权与 global） */
    public PrivateScope collectionScope(User user) {
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
        if (hasGlobalWhitelist(grants)) {
            return new PrivateScope(true, Set.of(), collectionIds(grants, PermissionConstants.TYPE_BLACKLIST));
        }
        Set<Long> allowed = collectionIds(grants, PermissionConstants.TYPE_WHITELIST);
        allowed.removeAll(collectionIds(grants, PermissionConstants.TYPE_BLACKLIST));
        return new PrivateScope(false, allowed, Set.of());
    }

    // ========== 查询条件注入 ==========

    /** 把照片可见性条件追加到查询条件上（所有照片查询必须调用） */
    public void applyPhotoFilter(LambdaQueryWrapper<Photo> wrapper, User user) {
        PrivateScope scope = photoScope(user);
        if (scope.all()) {
            if (scope.denied().isEmpty()) {
                return;
            }
            wrapper.and(w -> w.eq(Photo::getIsPrivate, 0)
                    .or(w2 -> w2.eq(Photo::getIsPrivate, 1).notIn(Photo::getId, scope.denied())));
            return;
        }
        if (scope.allowed().isEmpty()) {
            wrapper.eq(Photo::getIsPrivate, 0);
            return;
        }
        wrapper.and(w -> w.eq(Photo::getIsPrivate, 0).or().in(Photo::getId, scope.allowed()));
    }

    /** 把合集可见性条件追加到查询条件上（公开合集查询必须调用） */
    public void applyCollectionFilter(LambdaQueryWrapper<PhotoCollection> wrapper, User user) {
        PrivateScope scope = collectionScope(user);
        if (scope.all()) {
            if (scope.denied().isEmpty()) {
                return;
            }
            wrapper.and(w -> w.eq(PhotoCollection::getIsPrivate, 0)
                    .or(w2 -> w2.eq(PhotoCollection::getIsPrivate, 1).notIn(PhotoCollection::getId, scope.denied())));
            return;
        }
        if (scope.allowed().isEmpty()) {
            wrapper.eq(PhotoCollection::getIsPrivate, 0);
            return;
        }
        wrapper.and(w -> w.eq(PhotoCollection::getIsPrivate, 0).or().in(PhotoCollection::getId, scope.allowed()));
    }

    // ========== 单条判定 ==========

    /** 单张照片是否可见 */
    public boolean canViewPhoto(User user, Photo photo) {
        if (photo == null) {
            return false;
        }
        if (photo.getIsPrivate() == null || photo.getIsPrivate() == 0) {
            return true;
        }
        PrivateScope scope = photoScope(user);
        if (scope.all()) {
            return !scope.denied().contains(photo.getId());
        }
        return scope.allowed().contains(photo.getId());
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
            return !scope.denied().contains(collection.getId());
        }
        return scope.allowed().contains(collection.getId());
    }

    /**
     * 合集整体能否被访问（同时考虑未发布草稿）
     *
     * 具备管理权限的账号（后台合集管理）可见草稿；其余访客仅可见已发布且可见性通过的合集。
     */
    public boolean canAccessCollection(User user, PhotoCollection collection) {
        if (collection == null) {
            return false;
        }
        if (UserAuthorities.hasManageAccess(user)) {
            return true;
        }
        if (collection.getIsPublished() == null || collection.getIsPublished() != 1) {
            return false;
        }
        return canViewCollection(user, collection);
    }

    // ========== 内部工具 ==========

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

    /** 照片级授权 ID（含合集级授权的级联展开） */
    private Set<Long> photoIds(List<UserPermission> grants, String permType) {
        Set<Long> photoIds = new LinkedHashSet<>();
        Set<Long> collectionIds = new LinkedHashSet<>();
        for (UserPermission grant : grants) {
            if (!permType.equals(grant.getPermType()) || grant.getTargetId() == null) {
                continue;
            }
            if (PermissionConstants.TARGET_PHOTO.equals(grant.getTargetType())) {
                photoIds.add(grant.getTargetId());
            } else if (PermissionConstants.TARGET_COLLECTION.equals(grant.getTargetType())) {
                collectionIds.add(grant.getTargetId());
            }
        }
        photoIds.addAll(photoIdsInCollections(collectionIds));
        return photoIds;
    }

    /** 合集级授权 ID */
    private Set<Long> collectionIds(List<UserPermission> grants, String permType) {
        Set<Long> ids = new LinkedHashSet<>();
        for (UserPermission grant : grants) {
            if (permType.equals(grant.getPermType())
                    && PermissionConstants.TARGET_COLLECTION.equals(grant.getTargetType())
                    && grant.getTargetId() != null) {
                ids.add(grant.getTargetId());
            }
        }
        return ids;
    }

    private Set<Long> photoIdsInCollections(Set<Long> collectionIds) {
        if (collectionIds.isEmpty()) {
            return Set.of();
        }
        return collectionPhotoMapper.selectList(
                        new LambdaQueryWrapper<PhotoCollectionPhoto>()
                                .in(PhotoCollectionPhoto::getCollectionId, collectionIds))
                .stream()
                .map(PhotoCollectionPhoto::getPhotoId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
