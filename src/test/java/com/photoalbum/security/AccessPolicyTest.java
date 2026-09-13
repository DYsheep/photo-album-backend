package com.photoalbum.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.photoalbum.entity.Photo;
import com.photoalbum.entity.PhotoCollection;
import com.photoalbum.entity.PhotoCollectionPhoto;
import com.photoalbum.entity.User;
import com.photoalbum.entity.UserPermission;
import com.photoalbum.mapper.PhotoCollectionPhotoMapper;
import com.photoalbum.mapper.UserPermissionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * AccessPolicy 单元测试
 *
 * 覆盖默认拒绝模型：管理员全量、普通用户仅公开、viewer 需显式授权（无授权即不可见）、
 * 白名单（照片/合集级联/global）与黑名单减法。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccessPolicy 可见性策略")
class AccessPolicyTest {

    @Mock
    private UserPermissionMapper permMapper;

    @Mock
    private PhotoCollectionPhotoMapper collectionPhotoMapper;

    @InjectMocks
    private AccessPolicy policy;

    // ============================================================
    // 角色维度
    // ============================================================

    @Nested
    @DisplayName("角色维度")
    class RoleTests {

        @Test
        @DisplayName("管理员：不受限制，全部私密可见")
        void adminSeesEverything() {
            User admin = user("admin", 0, 0);

            assertThat(policy.photoScope(admin).all()).isTrue();
            assertThat(policy.canViewPhoto(admin, photo(1L, 1))).isTrue();
            assertThat(policy.canViewCollection(admin, collection(9L, 1, 0))).isTrue();        }

        @Test
        @DisplayName("普通用户（role=user）：任何私密内容都不可见")
        void normalUserSeesPublicOnly() {
            User normal = user("user", 1, 1);

            AccessPolicy.PrivateScope scope = policy.photoScope(normal);

            assertThat(scope.all()).isFalse();
            assertThat(scope.allowed()).isEmpty();
            assertThat(policy.canViewPhoto(normal, photo(1L, 1))).isFalse();
            assertThat(policy.canViewPhoto(normal, photo(1L, 0))).isTrue();
        }

        @Test
        @DisplayName("未登录访客：任何私密内容都不可见")
        void anonymousSeesPublicOnly() {
            assertThat(policy.canViewPhoto(null, photo(1L, 1))).isFalse();
            assertThat(policy.canViewPhoto(null, photo(1L, 0))).isTrue();
        }
    }

    // ============================================================
    // viewer 授权维度
    // ============================================================

    @Nested
    @DisplayName("viewer 授权维度")
    class ViewerGrantTests {

        @Test
        @DisplayName("viewer 无任何授权条目：默认拒绝（此前的行为是可看全部私密）")
        void viewerWithoutGrantsIsDeniedByDefault() {
            when(permMapper.selectList(any())).thenReturn(Collections.emptyList());
            User viewer = user("viewer", 0, 0);

            AccessPolicy.PrivateScope scope = policy.photoScope(viewer);

            assertThat(scope.all()).isFalse();
            assertThat(scope.allowed()).isEmpty();
            assertThat(policy.canViewPhoto(viewer, photo(5L, 1))).isFalse();
        }

        @Test
        @DisplayName("viewer 照片白名单：仅授权照片可见")
        void viewerWithPhotoWhitelist() {
            when(permMapper.selectList(any())).thenReturn(List.of(grant("W", "photo", 5L)));
            User viewer = user("viewer", 0, 0);

            assertThat(policy.canViewPhoto(viewer, photo(5L, 1))).isTrue();
            assertThat(policy.canViewPhoto(viewer, photo(6L, 1))).isFalse();
            assertThat(policy.canViewPhoto(viewer, photo(6L, 0))).isTrue();
        }

        @Test
        @DisplayName("viewer 合集白名单：级联到合集内照片")
        void viewerWithCollectionWhitelistCascades() {
            when(permMapper.selectList(any())).thenReturn(List.of(grant("W", "collection", 9L)));
            when(collectionPhotoMapper.selectList(any())).thenReturn(List.of(relation(9L, 5L), relation(9L, 6L)));
            User viewer = user("viewer", 0, 0);

            assertThat(policy.canViewPhoto(viewer, photo(5L, 1))).isTrue();
            assertThat(policy.canViewPhoto(viewer, photo(6L, 1))).isTrue();
            assertThat(policy.canViewPhoto(viewer, photo(7L, 1))).isFalse();
            assertThat(policy.canViewCollection(viewer, collection(9L, 1, 1))).isTrue();
            assertThat(policy.canViewCollection(viewer, collection(10L, 1, 1))).isFalse();
        }

        @Test
        @DisplayName("viewer global 白名单：全部私密可见")
        void viewerWithGlobalGrantSeesAllPrivate() {
            when(permMapper.selectList(any())).thenReturn(List.of(grant("W", "global", 0L)));
            User viewer = user("viewer", 0, 0);

            assertThat(policy.photoScope(viewer).all()).isTrue();
            assertThat(policy.canViewPhoto(viewer, photo(123L, 1))).isTrue();
        }

        @Test
        @DisplayName("viewer global 白名单 + 黑名单：排除项不可见")
        void viewerWithGlobalGrantAndBlacklist() {
            when(permMapper.selectList(any())).thenReturn(Arrays.asList(
                    grant("W", "global", 0L), grant("B", "photo", 7L)));
            User viewer = user("viewer", 0, 0);

            AccessPolicy.PrivateScope scope = policy.photoScope(viewer);

            assertThat(scope.all()).isTrue();
            assertThat(scope.denied()).containsExactly(7L);
            assertThat(policy.canViewPhoto(viewer, photo(7L, 1))).isFalse();
            assertThat(policy.canViewPhoto(viewer, photo(8L, 1))).isTrue();
        }

        @Test
        @DisplayName("黑名单在白名单范围内做减法（同一对象同时授权与排除时以排除为准）")
        void blacklistSubtractsFromWhitelist() {
            when(permMapper.selectList(any())).thenReturn(Arrays.asList(
                    grant("W", "photo", 5L), grant("B", "photo", 5L)));
            User viewer = user("viewer", 0, 0);

            assertThat(policy.canViewPhoto(viewer, photo(5L, 1))).isFalse();
        }
    }

    // ============================================================
    // 查询条件注入
    // ============================================================

    @Nested
    @DisplayName("查询条件注入")
    class FilterTests {

        @Test
        @DisplayName("管理员：不追加任何限制条件")
        void adminFilterIsEmpty() {
            LambdaQueryWrapper<Photo> wrapper = new LambdaQueryWrapper<>();
            policy.applyPhotoFilter(wrapper, user("admin", 0, 0));

            assertThat(wrapper.getSqlSegment()).isEmpty();
        }

        @Test
        @DisplayName("无授权 viewer：查询条件被限定为仅公开照片")
        void viewerFilterRestrictsToPublic() {
            when(permMapper.selectList(any())).thenReturn(Collections.emptyList());
            LambdaQueryWrapper<Photo> wrapper = new LambdaQueryWrapper<>();
            policy.applyPhotoFilter(wrapper, user("viewer", 0, 0));

            assertThat(wrapper.getSqlSegment()).contains("is_private");
        }

        @Test
        @DisplayName("合集查询条件：无授权访客仅可见公开合集")
        void collectionFilterRestrictsToPublic() {
            LambdaQueryWrapper<PhotoCollection> wrapper = new LambdaQueryWrapper<>();
            policy.applyCollectionFilter(wrapper, null);

            assertThat(wrapper.getSqlSegment()).contains("is_private");
        }
    }

    // ============================================================
    // 合集整体访问（未发布草稿）
    // ============================================================

    @Nested
    @DisplayName("合集整体访问")
    class CollectionAccessTests {

        @Test
        @DisplayName("未发布草稿：访客不可访问，具备管理权限的账号可访问")
        void draftCollectionOnlyForManagers() {
            PhotoCollection draft = collection(3L, 0, 0);

            assertThat(policy.canAccessCollection(null, draft)).isFalse();
            assertThat(policy.canAccessCollection(user("admin", 0, 0), draft)).isTrue();
            assertThat(policy.canAccessCollection(user("user", 0, 1), draft)).isTrue();
        }

        @Test
        @DisplayName("已发布私密合集：无授权访客不可访问")
        void privateCollectionRequiresGrant() {
            when(permMapper.selectList(any())).thenReturn(Collections.emptyList());
            PhotoCollection privateCol = collection(4L, 1, 1);

            assertThat(policy.canAccessCollection(user("viewer", 0, 0), privateCol)).isFalse();
            assertThat(policy.canAccessCollection(null, privateCol)).isFalse();
        }
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private User user(String role, Integer canUpload, Integer canManage) {
        User user = new User();
        user.setId(100L);
        user.setUsername("tester");
        user.setRole(role);
        user.setCanUpload(canUpload);
        user.setCanManage(canManage);
        return user;
    }

    private UserPermission grant(String permType, String targetType, Long targetId) {
        UserPermission permission = new UserPermission();
        permission.setUserId(100L);
        permission.setPermType(permType);
        permission.setTargetType(targetType);
        permission.setTargetId(targetId);
        return permission;
    }

    private Photo photo(Long id, int isPrivate) {
        Photo photo = new Photo();
        photo.setId(id);
        photo.setIsPrivate(isPrivate);
        return photo;
    }

    private PhotoCollection collection(Long id, int isPrivate, int isPublished) {
        PhotoCollection collection = new PhotoCollection();
        collection.setId(id);
        collection.setIsPrivate(isPrivate);
        collection.setIsPublished(isPublished);
        return collection;
    }

    private PhotoCollectionPhoto relation(Long collectionId, Long photoId) {
        PhotoCollectionPhoto relation = new PhotoCollectionPhoto();
        relation.setCollectionId(collectionId);
        relation.setPhotoId(photoId);
        return relation;
    }
}
