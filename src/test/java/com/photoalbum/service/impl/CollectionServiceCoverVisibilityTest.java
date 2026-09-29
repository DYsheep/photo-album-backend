package com.photoalbum.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.photoalbum.dto.CollectionDTO;
import com.photoalbum.entity.Photo;
import com.photoalbum.entity.PhotoCollection;
import com.photoalbum.mapper.CategoryMapper;
import com.photoalbum.mapper.CollectionMemberMapper;
import com.photoalbum.mapper.PhotoCollectionMapper;
import com.photoalbum.mapper.PhotoCollectionPhotoMapper;
import com.photoalbum.mapper.PhotoMapper;
import com.photoalbum.mapper.UserMapper;
import com.photoalbum.security.AccessPolicy;
import com.photoalbum.service.PhotoUrlResolver;
import com.qcloud.cos.COSClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CollectionServiceImpl 封面可见性单元测试（对应安全审查报告 S01）
 *
 * 缺陷回顾：合集封面地址由 PhotoUrlResolver 签发，而解析器对私密照片会生成短期预签名地址。
 * 原实现只取“封面照片或合集内排序第一张”的序号、不判可见性，
 * 于是匿名访问者请求公开合集列表（GET /api/collections）即可拿到私密照片的可用地址。
 *
 * 本测试固定修复后的三条约束：
 *   1. 封面照片对调用者不可见时，coverUrl 与 coverPhotoId 都不得出现在响应里；
 *   2. 此时回退候选只能是“合集内对调用者可见”的照片；
 *   3. 封面可见时行为不变（管理员场景不受影响）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CollectionService 封面可见性（S01 修复验证）")
class CollectionServiceCoverVisibilityTest {

    private static final String COS_DOMAIN = "https://cos.example.com";

    @Mock
    private PhotoCollectionMapper collectionMapper;
    @Mock
    private PhotoCollectionPhotoMapper collectionPhotoMapper;
    @Mock
    private PhotoMapper photoMapper;
    @Mock
    private CategoryMapper categoryMapper;
    @Mock
    private AccessPolicy accessPolicy;
    @Mock
    private CollectionMemberMapper memberMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private COSClient cosClient;

    @InjectMocks
    private CollectionServiceImpl collectionService;

    @BeforeEach
    void setUp() {
        // 注入真实解析器：内部只做字符串处理；关闭对象级 ACL 后私密照片也走直链，
        // 这样“地址是否被输出”这一断言只取决于可见性判定，与签名逻辑无关。
        PhotoUrlResolver resolver = new PhotoUrlResolver(cosClient);
        ReflectionTestUtils.setField(resolver, "cosBucket", "test-bucket");
        ReflectionTestUtils.setField(resolver, "cosDomain", COS_DOMAIN);
        ReflectionTestUtils.setField(resolver, "accessUrlPrefix", "/files/");
        ReflectionTestUtils.setField(resolver, "presignedTtlMinutes", 60L);
        ReflectionTestUtils.setField(resolver, "privateAclEnabled", false);
        ReflectionTestUtils.setField(collectionService, "photoUrlResolver", resolver);
        ReflectionTestUtils.setField(collectionService, "accessUrlPrefix", "/files/");
    }

    private static PhotoCollection collectionWithCover(Long id, Long coverPhotoId) {
        PhotoCollection collection = new PhotoCollection();
        collection.setId(id);
        collection.setName("测试合集");
        collection.setDescription("");
        collection.setCoverPhotoId(coverPhotoId);
        collection.setSortOrder(0);
        collection.setIsPublished(1);
        collection.setIsPrivate(0);
        return collection;
    }

    private static Photo photo(Long id, int isPrivate, String key) {
        Photo photo = new Photo();
        photo.setId(id);
        photo.setTitle("照片" + id);
        photo.setIsPrivate(isPrivate);
        photo.setUrl(COS_DOMAIN + "/" + key);
        photo.setThumbnailUrl(null);
        return photo;
    }

    @Nested
    @DisplayName("封面照片不可见时（S01 核心场景）")
    class InvisibleCoverTests {

        @Test
        @DisplayName("私密闭面不被输出：coverUrl 与 coverPhotoId 均为 null，且不落入兜底分支")
        void privateCoverIsNotExposed() {
            PhotoCollection collection = collectionWithCover(5L, 9L);
            Photo privateCover = photo(9L, 1, "2026/05/secret.jpg");

            when(collectionMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(collection));
            when(photoMapper.selectById(9L)).thenReturn(privateCover);
            // 匿名调用者（当前用户为 null）看不到私密照片
            when(accessPolicy.canViewPhoto(isNull(), eq(privateCover))).thenReturn(false);
            when(photoMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
            when(photoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            List<CollectionDTO> result = collectionService.getPublishedCollections();

            assertThat(result).hasSize(1);
            CollectionDTO dto = result.get(0);
            assertThat(dto.getCoverUrl()).as("私密照片的地址不得出现在公开响应中").isNull();
            assertThat(dto.getCoverPhotoId()).as("私密照片的 ID 同样不得暴露").isNull();
            assertThat(dto.getPhotoCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("封面不可见时回退到合集内可见照片，而不是合集内排序第一张")
        void fallsBackToVisiblePhotoOnly() {
            PhotoCollection collection = collectionWithCover(5L, 9L);
            Photo privateCover = photo(9L, 1, "2026/05/secret.jpg");
            Photo visible = photo(11L, 0, "2026/05/public.jpg");

            when(collectionMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(collection));
            when(photoMapper.selectById(9L)).thenReturn(privateCover);
            when(accessPolicy.canViewPhoto(isNull(), eq(privateCover))).thenReturn(false);
            when(photoMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
            // 兜底查询自身已带可见性条件（applyPhotoFilter），返回的必然是一张可见照片
            when(photoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(visible);

            List<CollectionDTO> result = collectionService.getPublishedCollections();

            assertThat(result.get(0).getCoverUrl())
                    .isEqualTo(COS_DOMAIN + "/2026/05/public.jpg");
            assertThat(result.get(0).getCoverUrl()).doesNotContain("secret.jpg");
            assertThat(result.get(0).getCoverPhotoId()).isNull();
        }

        @Test
        @DisplayName("合集内无可见照片时封面留空，由前端回退站点默认图")
        void noVisiblePhotoLeavesCoverEmpty() {
            PhotoCollection collection = collectionWithCover(5L, null);
            Photo privateOnly = photo(9L, 1, "2026/05/secret.jpg");

            when(collectionMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(collection));
            when(photoMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
            when(photoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            List<CollectionDTO> result = collectionService.getPublishedCollections();

            assertThat(result.get(0).getCoverUrl()).isNull();
            assertThat(result.get(0).getCoverPhotoId()).isNull();
            // 未配置封面时不应为取封面而按 ID 查询照片
            verify(photoMapper, never()).selectById(any());
            assertThat(privateOnly.getIsPrivate()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("封面可见时（管理员/有权账号，行为不变）")
    class VisibleCoverTests {

        @Test
        @DisplayName("可见封面正常输出 URL 与 ID")
        void visibleCoverIsExposed() {
            PhotoCollection collection = collectionWithCover(5L, 9L);
            Photo cover = photo(9L, 1, "2026/05/visible.jpg");

            when(collectionMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(collection));
            when(photoMapper.selectById(9L)).thenReturn(cover);
            when(accessPolicy.canViewPhoto(isNull(), eq(cover))).thenReturn(true);
            when(photoMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

            List<CollectionDTO> result = collectionService.getPublishedCollections();

            assertThat(result.get(0).getCoverUrl()).isEqualTo(COS_DOMAIN + "/2026/05/visible.jpg");
            assertThat(result.get(0).getCoverPhotoId()).isEqualTo(9L);
        }

        @Test
        @DisplayName("显式封面不存在时不抛异常，改为走兜底查询")
        void danglingCoverPhotoIdFallsBack() {
            PhotoCollection collection = collectionWithCover(5L, 999L);

            when(collectionMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(collection));
            when(photoMapper.selectById(999L)).thenReturn(null);
            when(photoMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
            when(photoMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            List<CollectionDTO> result = collectionService.getPublishedCollections();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getCoverUrl()).isNull();
        }
    }

    @Nested
    @DisplayName("合集列表为空")
    class EmptyListTests {

        @Test
        @DisplayName("无合集时返回空列表且不查询照片")
        void emptyCollectionList() {
            when(collectionMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(Collections.emptyList());

            assertThat(collectionService.getPublishedCollections()).isEmpty();
            verify(photoMapper, never()).selectCount(any(LambdaQueryWrapper.class));
        }
    }
}
