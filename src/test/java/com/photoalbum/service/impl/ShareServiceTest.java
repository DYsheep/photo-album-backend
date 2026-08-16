package com.photoalbum.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.photoalbum.common.BusinessException;
import com.photoalbum.dto.ShareLinkDTO;
import com.photoalbum.entity.Photo;
import com.photoalbum.entity.ShareLink;
import com.photoalbum.mapper.PhotoMapper;
import com.photoalbum.mapper.ShareLinkMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ShareService 单元测试
 * 使用 Mockito mock Mapper 层，测试 Service 层业务逻辑
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShareService 单元测试")
class ShareServiceTest {

    @Mock
    private ShareLinkMapper shareLinkMapper;

    @Mock
    private PhotoMapper photoMapper;

    @InjectMocks
    private ShareServiceImpl shareService;

    private static final String SHARE_BASE_URL = "http://localhost:5173";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(shareService, "shareBaseUrl", SHARE_BASE_URL);
    }

    // ============================================================
    // createShareLink() 测试
    // ============================================================

    @Nested
    @DisplayName("createShareLink() - 创建分享链接")
    class CreateShareLinkTests {

        @Test
        @DisplayName("正常创建分享链接，验证 code 非空且为 8 位字母数字组合")
        void testCreateShareLink() {
            // Arrange
            Long photoId = 1L;
            Photo photo = buildPhoto(photoId, "测试照片", "2026/05/test.jpg");

            when(photoMapper.selectById(photoId)).thenReturn(photo);
            when(shareLinkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(shareLinkMapper.insert(any(ShareLink.class))).thenReturn(1);

            // Act
            ShareLinkDTO result = shareService.createShareLink(photoId);

            // Assert: code 非空且为 8 位
            assertNotNull(result);
            assertNotNull(result.getCode(), "分享码不应为空");
            assertEquals(8, result.getCode().length(), "分享码应为 8 位");

            // 验证 code 仅包含字母数字
            assertTrue(result.getCode().matches("^[A-Za-z0-9]{8}$"),
                    "分享码应仅包含大小写字母和数字，实际: " + result.getCode());

            // 验证 shareUrl 格式正确
            String expectedShareUrl = SHARE_BASE_URL + "/share/" + result.getCode();
            assertEquals(expectedShareUrl, result.getShareUrl(), "shareUrl 格式不正确");

            // 验证关联照片信息
            assertEquals(photoId, result.getPhotoId());
            assertEquals("测试照片", result.getPhotoTitle());
            assertEquals("2026/05/test.jpg", result.getPhotoUrl());

            // 验证 insert 被调用
            verify(shareLinkMapper).insert(any(ShareLink.class));
        }

        @Test
        @DisplayName("同一 photoId 重复创建，应返回已有链接（同一个 code）")
        void testCreateDuplicate() {
            // Arrange
            Long photoId = 1L;
            Photo photo = buildPhoto(photoId, "测试照片", "2026/05/test.jpg");

            ShareLink existingLink = new ShareLink();
            existingLink.setId(100L);
            existingLink.setCode("AbCd1234");
            existingLink.setPhotoId(photoId);
            existingLink.setCreatedAt(LocalDateTime.now());

            when(photoMapper.selectById(photoId)).thenReturn(photo);
            when(shareLinkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingLink);

            // Act: 第一次创建
            ShareLinkDTO result1 = shareService.createShareLink(photoId);

            // Act: 第二次创建（同一个 photoId）
            ShareLinkDTO result2 = shareService.createShareLink(photoId);

            // Assert: 两次返回相同的 code（复用已有链接）
            assertEquals("AbCd1234", result1.getCode(), "第一次应返回已有 code");
            assertEquals("AbCd1234", result2.getCode(), "第二次应返回相同的 code");
            assertEquals(result1.getCode(), result2.getCode(), "两次应返回完全相同的 code");
            assertEquals(result1.getId(), result2.getId(), "两次应返回相同的 ID");

            // 验证 shareUrl 包含已有 code
            assertEquals(SHARE_BASE_URL + "/share/AbCd1234", result1.getShareUrl());
            assertEquals(SHARE_BASE_URL + "/share/AbCd1234", result2.getShareUrl());

            // 验证没有执行 insert（因为复用了已有链接）
            verify(shareLinkMapper, never()).insert(any(ShareLink.class));
        }

        @Test
        @DisplayName("照片不存在时，应抛出 BusinessException(code=404)")
        void shouldThrowWhenPhotoNotFound() {
            // Arrange
            Long nonExistentPhotoId = 999L;
            when(photoMapper.selectById(nonExistentPhotoId)).thenReturn(null);

            // Act & Assert
            BusinessException ex = assertThrows(BusinessException.class, () ->
                    shareService.createShareLink(nonExistentPhotoId)
            );

            assertEquals(404, ex.getCode());
            assertThat(ex.getMessage()).contains("照片不存在");

            // 验证没有尝试创建分享链接
            verify(shareLinkMapper, never()).selectOne(any());
            verify(shareLinkMapper, never()).insert(any());
        }
    }

    // ============================================================
    // getByCode() 测试
    // ============================================================

    @Nested
    @DisplayName("getByCode() - 根据分享码查询")
    class GetByCodeTests {

        @Test
        @DisplayName("用有效 code 查询，应返回正确的 ShareLinkDTO（含 shareUrl 和照片 EXIF 信息）")
        void testGetByCode() {
            // Arrange
            String code = "Test1234";
            Long photoId = 1L;

            ShareLink shareLink = buildShareLink(1L, code, photoId);
            Photo photo = buildPhoto(photoId, "日落风景", "photos/sunset.jpg");
            photo.setDescription("美丽的日落");
            photo.setCameraModel("Canon EOS R5");
            photo.setAperture("f/2.8");
            photo.setShutterSpeed("1/125s");
            photo.setIso("100");
            photo.setFocalLength("50mm");
            photo.setDateTaken("2025:03:15 18:30:00");

            when(shareLinkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(shareLink);
            when(photoMapper.selectById(photoId)).thenReturn(photo);

            // Act
            ShareLinkDTO result = shareService.getByCode(code);

            // Assert: 基本字段
            assertNotNull(result, "查询结果不应为 null");
            assertEquals(1L, result.getId());
            assertEquals(code, result.getCode());
            assertEquals(photoId, result.getPhotoId());

            // 验证 shareUrl
            assertEquals(SHARE_BASE_URL + "/share/" + code, result.getShareUrl());

            // 验证关联照片基本信息
            assertEquals("日落风景", result.getPhotoTitle());
            // URL 没有以 http 开头，应补全为 /files/ 前缀
            assertEquals("/files/photos/sunset.jpg", result.getPhotoUrl());

            // 验证 EXIF 信息
            assertEquals("日落风景", result.getTitle());
            assertEquals("美丽的日落", result.getDescription());
            assertEquals("Canon EOS R5", result.getCameraModel());
            assertEquals("f/2.8", result.getAperture());
            assertEquals("1/125s", result.getShutterSpeed());
            assertEquals("100", result.getIso());
            assertEquals("50mm", result.getFocalLength());
            assertEquals("2025:03:15 18:30:00", result.getDateTaken());
        }

        @Test
        @DisplayName("不存在的 code 应抛 BusinessException")
        void testGetByInvalidCode() {
            // Arrange
            String invalidCode = "INVALID1";
            when(shareLinkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            // Act & Assert: 根据需求文档，不存在的 code 应抛 BusinessException
            BusinessException ex = assertThrows(BusinessException.class, () ->
                    shareService.getByCode(invalidCode)
            );

            assertThat(ex.getMessage()).contains("分享链接");
        }

        @Test
        @DisplayName("分享链接存在但关联照片已被删除，应抛 BusinessException")
        void shouldThrowWhenPhotoDeleted() {
            // Arrange
            String code = "ValidCde";
            ShareLink shareLink = buildShareLink(1L, code, 99L);

            when(shareLinkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(shareLink);
            when(photoMapper.selectById(99L)).thenReturn(null); // 照片已删除

            // Act & Assert
            BusinessException ex = assertThrows(BusinessException.class, () ->
                    shareService.getByCode(code)
            );

            assertThat(ex.getMessage()).contains("分享链接");
        }

        @Test
        @DisplayName("code 为 null 时，应抛出 BusinessException")
        void shouldHandleNullCode() {
            // Act & Assert: service 层现在在调用 mapper 之前就校验 code
            BusinessException ex = assertThrows(BusinessException.class, () ->
                    shareService.getByCode(null)
            );

            assertNotNull(ex);
            assertEquals(404, ex.getCode());
            assertThat(ex.getMessage()).contains("分享链接");
        }

        @Test
        @DisplayName("code 为空字符串时，应抛出 BusinessException")
        void shouldHandleBlankCode() {
            // Act & Assert
            BusinessException ex = assertThrows(BusinessException.class, () ->
                    shareService.getByCode("   ")
            );

            assertNotNull(ex);
            assertEquals(404, ex.getCode());
            assertThat(ex.getMessage()).contains("分享链接");
        }
    }

    // ============================================================
    // getShareLinks() 测试
    // ============================================================

    @Nested
    @DisplayName("getShareLinks() - 获取分享链接列表")
    class GetShareLinksTests {

        @Test
        @DisplayName("有分享链接时，应返回按创建时间降序排列的列表")
        void shouldReturnShareLinksOrderedByCreatedAtDesc() {
            // Arrange
            ShareLink sl1 = buildShareLink(1L, "Code0001", 10L);
            sl1.setCreatedAt(LocalDateTime.of(2025, 6, 1, 10, 0));
            ShareLink sl2 = buildShareLink(2L, "Code0002", 20L);
            sl2.setCreatedAt(LocalDateTime.of(2025, 6, 2, 10, 0)); // 更新

            Photo p1 = buildPhoto(10L, "照片A", "a.jpg");
            Photo p2 = buildPhoto(20L, "照片B", "b.jpg");

            when(shareLinkMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(Arrays.asList(sl2, sl1)); // 按时间降序
            when(photoMapper.selectById(10L)).thenReturn(p1);
            when(photoMapper.selectById(20L)).thenReturn(p2);

            // Act
            List<ShareLinkDTO> result = shareService.getShareLinks();

            // Assert
            assertNotNull(result);
            assertEquals(2, result.size());

            // 第一个应是最新的
            assertEquals("Code0002", result.get(0).getCode());
            assertEquals("照片B", result.get(0).getPhotoTitle());
            assertEquals(SHARE_BASE_URL + "/share/Code0002", result.get(0).getShareUrl());

            assertEquals("Code0001", result.get(1).getCode());
            assertEquals("照片A", result.get(1).getPhotoTitle());
            assertEquals(SHARE_BASE_URL + "/share/Code0001", result.get(1).getShareUrl());
        }

        @Test
        @DisplayName("无分享链接时，应返回空列表")
        void shouldReturnEmptyListWhenNoShareLinks() {
            // Arrange
            when(shareLinkMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(Collections.emptyList());

            // Act
            List<ShareLinkDTO> result = shareService.getShareLinks();

            // Assert
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    // ============================================================
    // deleteShareLink() 测试
    // ============================================================

    @Nested
    @DisplayName("deleteShareLink() - 删除分享链接")
    class DeleteShareLinkTests {

        @Test
        @DisplayName("正常删除存在的分享链接，删除后再次查询应抛异常")
        void testDeleteShareLink() {
            // Arrange
            Long shareId = 1L;
            String code = "DelCode1";
            ShareLink shareLink = buildShareLink(shareId, code, 10L);

            // 第一次查询：存在
            when(shareLinkMapper.selectById(shareId)).thenReturn(shareLink);
            when(shareLinkMapper.deleteById(shareId)).thenReturn(1);

            // Act: 删除成功
            assertDoesNotThrow(() -> shareService.deleteShareLink(shareId));
            verify(shareLinkMapper).deleteById(shareId);

            // Arrange: 删除后再次查询（模拟 selectById 返回 null）
            when(shareLinkMapper.selectById(shareId)).thenReturn(null);

            // Act & Assert: 删除后再次删除应抛异常
            BusinessException ex = assertThrows(BusinessException.class, () ->
                    shareService.deleteShareLink(shareId)
            );

            assertEquals(404, ex.getCode());
            assertThat(ex.getMessage()).contains("分享链接不存在");
        }

        @Test
        @DisplayName("删除不存在的分享链接，应抛出 BusinessException(code=404)")
        void shouldThrowWhenDeletingNonExistentLink() {
            // Arrange
            Long nonExistentId = 999L;
            when(shareLinkMapper.selectById(nonExistentId)).thenReturn(null);

            // Act & Assert
            BusinessException ex = assertThrows(BusinessException.class, () ->
                    shareService.deleteShareLink(nonExistentId)
            );

            assertEquals(404, ex.getCode());
            assertThat(ex.getMessage()).contains("分享链接不存在");

            // 验证没有执行 delete
            verify(shareLinkMapper, never()).deleteById(anyLong());
        }
    }

    // ============================================================
    // toDTO() 测试
    // ============================================================

    @Nested
    @DisplayName("toDTO() - Entity 转 DTO")
    class ToDTOTests {

        @Test
        @DisplayName("正常转换，所有字段应正确映射")
        void shouldConvertEntityToDto() {
            // Arrange
            ShareLink shareLink = new ShareLink();
            shareLink.setId(1L);
            shareLink.setCode("Abc12345");
            shareLink.setPhotoId(10L);
            LocalDateTime now = LocalDateTime.now();
            shareLink.setCreatedAt(now);

            // Act
            ShareLinkDTO dto = shareService.toDTO(shareLink);

            // Assert
            assertNotNull(dto);
            assertEquals(1L, dto.getId());
            assertEquals("Abc12345", dto.getCode());
            assertEquals(10L, dto.getPhotoId());
            assertEquals(now, dto.getCreatedAt());
        }
    }

    // ============================================================
    // 生成码质量测试
    // ============================================================

    @Nested
    @DisplayName("分享码生成质量")
    class CodeGenerationQualityTests {

        @Test
        @DisplayName("多次创建的分享码应各不相同（唯一性验证）")
        void shouldGenerateDifferentCodesForDifferentPhotos() {
            // Arrange
            Photo p1 = buildPhoto(1L, "照片1", "p1.jpg");
            Photo p2 = buildPhoto(2L, "照片2", "p2.jpg");
            Photo p3 = buildPhoto(3L, "照片3", "p3.jpg");

            when(photoMapper.selectById(1L)).thenReturn(p1);
            when(photoMapper.selectById(2L)).thenReturn(p2);
            when(photoMapper.selectById(3L)).thenReturn(p3);

            when(shareLinkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(shareLinkMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
            when(shareLinkMapper.insert(any(ShareLink.class))).thenReturn(1);

            // Act
            ShareLinkDTO dto1 = shareService.createShareLink(1L);
            ShareLinkDTO dto2 = shareService.createShareLink(2L);
            ShareLinkDTO dto3 = shareService.createShareLink(3L);

            // Assert: 三个码应各不相同（SecureRandom 生成的冲突概率极低）
            assertNotEquals(dto1.getCode(), dto2.getCode(), "不同照片的分享码不应相同");
            assertNotEquals(dto2.getCode(), dto3.getCode(), "不同照片的分享码不应相同");
            assertNotEquals(dto1.getCode(), dto3.getCode(), "不同照片的分享码不应相同");

            // 验证每个码都是 8 位
            assertEquals(8, dto1.getCode().length());
            assertEquals(8, dto2.getCode().length());
            assertEquals(8, dto3.getCode().length());
        }
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private Photo buildPhoto(Long id, String title, String url) {
        Photo photo = new Photo();
        photo.setId(id);
        photo.setTitle(title);
        photo.setUrl(url);
        photo.setDescription("");
        photo.setFileName(title + ".jpg");
        photo.setFileSize(1024L);
        photo.setViewCount(0);
        photo.setCategoryId(null);
        photo.setTags("");
        photo.setCameraModel("");
        photo.setAperture("");
        photo.setShutterSpeed("");
        photo.setIso("");
        photo.setFocalLength("");
        photo.setDateTaken("");
        photo.setCreatedAt(LocalDateTime.now());
        photo.setUpdatedAt(LocalDateTime.now());
        return photo;
    }

    private ShareLink buildShareLink(Long id, String code, Long photoId) {
        ShareLink shareLink = new ShareLink();
        shareLink.setId(id);
        shareLink.setCode(code);
        shareLink.setPhotoId(photoId);
        shareLink.setCreatedAt(LocalDateTime.now());
        return shareLink;
    }
}
