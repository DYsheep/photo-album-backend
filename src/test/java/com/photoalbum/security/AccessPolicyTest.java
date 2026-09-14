package com.photoalbum.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.photoalbum.entity.Photo;
import com.photoalbum.entity.PhotoCollection;
import com.photoalbum.entity.PhotoCollectionPhoto;
import com.photoalbum.entity.User;
import com.photoalbum.entity.AuthTuple;
import com.photoalbum.mapper.PhotoCollectionPhotoMapper;
import com.photoalbum.mapper.AuthTupleMapper;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    private AuthTupleMapper tupleMapper;

    @Mock
    private PhotoCollectionPhotoMapper collectionPhotoMapper;

    /** 合集协作者关系（对象级管理权） */
    @Mock
    private com.photoalbum.mapper.CollectionMemberMapper collectionMemberMapper;

    /** 标签表（把标签级授权翻译成标签名，供内存精确匹配） */
    @Mock
    private com.photoalbum.mapper.TagMapper tagMapper;

    /** 角色模板（数据范围） */
    @Mock
    private com.photoalbum.mapper.RoleTemplateMapper roleTemplateMapper;

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
            assertThat(scope.allowedPhotoIds()).isEmpty();
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
            when(tupleMapper.selectList(any())).thenReturn(Collections.emptyList());
            User viewer = viewer();

            AccessPolicy.PrivateScope scope = policy.photoScope(viewer);

            assertThat(scope.all()).isFalse();
            assertThat(scope.allowedPhotoIds()).isEmpty();
            assertThat(policy.canViewPhoto(viewer, photo(5L, 1))).isFalse();
        }

        @Test
        @DisplayName("viewer 照片白名单：仅授权照片可见")
        void viewerWithPhotoWhitelist() {
            when(tupleMapper.selectList(any())).thenReturn(List.of(grant("W", "photo", 5L)));
            User viewer = viewer();

            assertThat(policy.canViewPhoto(viewer, photo(5L, 1))).isTrue();
            assertThat(policy.canViewPhoto(viewer, photo(6L, 1))).isFalse();
            assertThat(policy.canViewPhoto(viewer, photo(6L, 0))).isTrue();
        }

        @Test
        @DisplayName("viewer 合集白名单：级联到合集内照片（合集详情场景逐张判定零查询）")
        void viewerWithCollectionWhitelistCascades() {
            when(tupleMapper.selectList(any())).thenReturn(List.of(grant("W", "collection", 9L)));
            User viewer = viewer();

            assertThat(policy.canViewPhotoInCollection(viewer, photo(5L, 1), 9L)).isTrue();
            assertThat(policy.canViewPhotoInCollection(viewer, photo(6L, 1), 9L)).isTrue();
            assertThat(policy.canViewPhotoInCollection(viewer, photo(7L, 1), 10L)).isFalse();
            assertThat(policy.canViewCollection(viewer, collection(9L, 1, 1))).isTrue();
            assertThat(policy.canViewCollection(viewer, collection(10L, 1, 1))).isFalse();
            verify(collectionPhotoMapper, never()).selectList(any());
        }

        @Test
        @DisplayName("未知合集归属时：一次小查询确认照片是否属于被授权合集")
        void shouldResolveCollectionMembershipWhenUnknown() {
            when(tupleMapper.selectList(any())).thenReturn(List.of(grant("W", "collection", 9L)));
            when(collectionPhotoMapper.selectCount(any())).thenReturn(1L);

            assertThat(policy.canViewPhoto(viewer(), photo(5L, 1))).isTrue();
        }

        @Test
        @DisplayName("viewer global 白名单：全部私密可见")
        void viewerWithGlobalGrantSeesAllPrivate() {
            when(tupleMapper.selectList(any())).thenReturn(List.of(grant("W", "global", 0L)));
            User viewer = viewer();

            assertThat(policy.photoScope(viewer).all()).isTrue();
            assertThat(policy.canViewPhoto(viewer, photo(123L, 1))).isTrue();
        }

        @Test
        @DisplayName("viewer global 白名单 + 黑名单：排除项不可见")
        void viewerWithGlobalGrantAndBlacklist() {
            when(tupleMapper.selectList(any())).thenReturn(Arrays.asList(
                    grant("W", "global", 0L), grant("B", "photo", 7L)));
            User viewer = viewer();

            AccessPolicy.PrivateScope scope = policy.photoScope(viewer);

            assertThat(scope.all()).isTrue();
            assertThat(scope.excludedPhotoIds()).containsExactly(7L);
            assertThat(policy.canViewPhoto(viewer, photo(7L, 1))).isFalse();
            assertThat(policy.canViewPhoto(viewer, photo(8L, 1))).isTrue();
        }

        @Test
        @DisplayName("黑名单在白名单范围内做减法（同一对象同时授权与排除时以排除为准）")
        void blacklistSubtractsFromWhitelist() {
            when(tupleMapper.selectList(any())).thenReturn(Arrays.asList(
                    grant("W", "photo", 5L), grant("B", "photo", 5L)));
            User viewer = viewer();

            assertThat(policy.canViewPhoto(viewer, photo(5L, 1))).isFalse();
        }

        @Test
        @DisplayName("分类白名单：该分类下的照片可见（服务端内存判定，无需展开照片 ID）")
        void viewerWithCategoryWhitelist() {
            when(tupleMapper.selectList(any())).thenReturn(List.of(grant("W", "category", 3L)));
            User viewer = viewer();

            Photo inCategory = photo(5L, 1);
            inCategory.setCategoryId(3L);
            Photo otherCategory = photo(6L, 1);
            otherCategory.setCategoryId(4L);

            assertThat(policy.canViewPhoto(viewer, inCategory)).isTrue();
            assertThat(policy.canViewPhoto(viewer, otherCategory)).isFalse();
        }

        @Test
        @DisplayName("合集内逐张判定：已知所属合集时不产生额外查询（合集详情批量场景）")
        void inCollectionCheckNeedsNoQuery() {
            when(tupleMapper.selectList(any())).thenReturn(List.of(grant("W", "collection", 9L)));
            User viewer = viewer();
            Photo privatePhoto = photo(5L, 1);

            assertThat(policy.canViewPhotoInCollection(viewer, privatePhoto, 9L)).isTrue();
            assertThat(policy.canViewPhotoInCollection(viewer, privatePhoto, 10L)).isFalse();

            // 全程未查询合集内照片关系表（对比 canViewPhoto 的场景）
            verify(collectionPhotoMapper, never()).selectCount(any());
        }

        @Test
        @DisplayName("未被授予私密查看能力位的账号：即使有授权条目也看不到私密内容")
        void capabilityIsRequired() {
            User noCapability = user("viewer", 0, 0, 0);

            assertThat(policy.photoScope(noCapability).all()).isFalse();
            assertThat(policy.canViewPhoto(noCapability, photo(5L, 1))).isFalse();
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
            when(tupleMapper.selectList(any())).thenReturn(Collections.emptyList());
            LambdaQueryWrapper<Photo> wrapper = new LambdaQueryWrapper<>();
            policy.applyPhotoFilter(wrapper, viewer());

            assertThat(wrapper.getSqlSegment()).contains("is_private");
        }

        @Test
        @DisplayName("合集查询条件：无授权访客仅可见公开合集")
        void collectionFilterRestrictsToPublic() {
            LambdaQueryWrapper<PhotoCollection> wrapper = new LambdaQueryWrapper<>();
            policy.applyCollectionFilter(wrapper, null);

            assertThat(wrapper.getSqlSegment()).contains("is_private");
        }

        @Test
        @DisplayName("合集级授权以子查询表达，不把合集内照片 ID 展开成大 IN")
        void collectionGrantUsesSubQuery() {
            when(tupleMapper.selectList(any())).thenReturn(List.of(grant("W", "collection", 9L)));
            LambdaQueryWrapper<Photo> wrapper = new LambdaQueryWrapper<>();
            policy.applyPhotoFilter(wrapper, viewer());

            String sql = wrapper.getSqlSegment();
            assertThat(sql).contains("EXISTS");
            assertThat(sql).contains("t_auth_tuple");
            verify(collectionPhotoMapper, never()).selectList(any());
        }

        @Test
        @DisplayName("分类级授权以子查询表达")
        void categoryGrantUsesSubQuery() {
            when(tupleMapper.selectList(any())).thenReturn(List.of(grant("W", "category", 3L)));
            LambdaQueryWrapper<Photo> wrapper = new LambdaQueryWrapper<>();
            policy.applyPhotoFilter(wrapper, viewer());

            assertThat(wrapper.getSqlSegment()).contains("category_id IN (SELECT");
        }
    }

    // ============================================================
    // 合集整体访问（未发布草稿）
    // ============================================================

    @Nested
    @DisplayName("合集整体访问")
    class CollectionAccessTests {

        @Test
        @DisplayName("未发布草稿：仅管理员与资源范围内账号可访问")
        void draftCollectionOnlyForManagers() {
            PhotoCollection draft = collection(3L, 0, 0);

            assertThat(policy.canAccessCollection(null, draft)).isFalse();
            assertThat(policy.canAccessCollection(user("admin", 0, 0), draft)).isTrue();
            // 决策点 1B：具备管理能力但既非创建人、也未被授予/指派时，草稿不可见
            assertThat(policy.canAccessCollection(user("user", 0, 1), draft)).isFalse();
        }

        @Test
        @DisplayName("已发布私密合集：无授权访客不可访问")
        void privateCollectionRequiresGrant() {
            when(tupleMapper.selectList(any())).thenReturn(Collections.emptyList());
            PhotoCollection privateCol = collection(4L, 1, 1);

            assertThat(policy.canAccessCollection(viewer(), privateCol)).isFalse();
            assertThat(policy.canAccessCollection(null, privateCol)).isFalse();
        }
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private User user(String role, Integer canUpload, Integer canManage) {
        return user(role, canUpload, canManage, 0);
    }

    private User user(String role, Integer canUpload, Integer canManage, Integer canViewPrivate) {
        User user = new User();
        user.setId(100L);
        user.setUsername("tester");
        user.setRole(role);
        user.setCanUpload(canUpload);
        user.setCanManage(canManage);
        user.setCanViewPrivate(canViewPrivate);
        return user;
    }

    /** 具备"私密查看"能力位的账号（角色仅为标识） */
    private User viewer() {
        return user("viewer", 0, 0, 1);
    }

    private AuthTuple grant(String permType, String targetType, Long targetId) {
        AuthTuple p = new AuthTuple();
        p.setSubjectType("user");
        p.setSubjectId(100L);
        p.setRelation("W".equals(permType) ? AuthTuple.RELATION_ALLOW : AuthTuple.RELATION_DENY);
        p.setObjectType(targetType);
        p.setObjectId(targetId);
        return p;
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

    @Nested
    @DisplayName("授权子查询列名回归（元组化后不得再引用旧表/旧列）")
    class SubQueryColumnRegressionTests {

        @Test
        @DisplayName("带 allow/deny 条目的访客：生成的 SQL 只引用 t_auth_tuple 的新列名")
        void generatedSqlUsesTupleColumnsOnly() {
            when(tupleMapper.selectList(any())).thenReturn(List.of(
                    grant("W", "collection", 8L),
                    grant("B", "collection", 5L),
                    grant("B", "category", 2L)));

            LambdaQueryWrapper<Photo> wrapper = new LambdaQueryWrapper<>();
            policy.applyPhotoFilter(wrapper, viewer());
            String sql = wrapper.getSqlSegment();

            assertThat(sql).contains("t_auth_tuple");
            assertThat(sql).contains("subject_id");
            assertThat(sql).doesNotContain("t_user_permission");
            assertThat(sql).doesNotContain("user_id");
            assertThat(sql).doesNotContain("perm_type");
            assertThat(sql).doesNotContain("target_type");
            assertThat(sql).doesNotContain("target_id");
        }
    }
}
