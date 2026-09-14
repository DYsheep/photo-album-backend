package com.photoalbum.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.photoalbum.common.BusinessException;
import com.photoalbum.dto.PhotoDTO;
import com.photoalbum.entity.Category;
import com.photoalbum.entity.Photo;
import com.photoalbum.mapper.CategoryMapper;
import com.photoalbum.mapper.PhotoCollectionPhotoMapper;
import com.photoalbum.mapper.PhotoMapper;
import com.photoalbum.mapper.ShareLinkMapper;
import com.photoalbum.security.AccessPolicy;
import com.photoalbum.service.PhotoUrlResolver;
import com.photoalbum.service.TagService;
import com.qcloud.cos.COSClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PhotoService 单元测试
 * 使用 Mockito mock Mapper 层，测试 Service 层业务逻辑
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PhotoService 单元测试")
class PhotoServiceTest {

    @Mock
    private PhotoMapper photoMapper;

    @Mock
    private CategoryMapper categoryMapper;

    /** deletePhoto/batchDelete 会级联清理合集关联与分享链接，需一并 mock */
    @Mock
    private PhotoCollectionPhotoMapper collectionPhotoMapper;

    @Mock
    private ShareLinkMapper shareLinkMapper;


    /** 可见性策略由 AccessPolicyTest 单独覆盖，此处仅需注入以保证调用链完整 */
    @Mock
    private AccessPolicy accessPolicy;

    /** 标签关联表同步（写路径双写），单测中为无操作 */
    @Mock
    private TagService tagService;

    /** 对象存储客户端为外部依赖，单元测试中必须 mock（否则上传路径会因空指针失败） */
    @Mock
    private COSClient cosClient;

    @InjectMocks
    private PhotoServiceImpl photoService;

    private Path tempUploadDir;

    // 测试用真实图片文件头（魔数），用于通过上传内容校验
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] GIF_MAGIC = {'G', 'I', 'F', '8', '9', 'a'};
    private static final byte[] BMP_MAGIC = {'B', 'M'};
    private static final byte[] WEBP_MAGIC = {
            'R', 'I', 'F', 'F', 0x00, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P'};

    /** 生成指定总长度、带真实文件头的图片字节 */
    private static byte[] imageBytes(byte[] magic, int totalSize) {
        byte[] data = new byte[totalSize];
        System.arraycopy(magic, 0, data, 0, Math.min(magic.length, totalSize));
        return data;
    }

    @BeforeEach
    void setUp() throws IOException {
        // 创建临时上传目录（供用例自身清理使用）
        tempUploadDir = Files.createTempDirectory("photo-test-upload-");
        ReflectionTestUtils.setField(photoService, "accessUrlPrefix", "/files/");
        // 注入真实的地址解析器（内部只做字符串与签名处理，不发网络请求）
        PhotoUrlResolver urlResolver = new PhotoUrlResolver(cosClient);
        ReflectionTestUtils.setField(urlResolver, "cosBucket", "test-bucket");
        ReflectionTestUtils.setField(urlResolver, "cosDomain", "https://cos.example.com");
        ReflectionTestUtils.setField(urlResolver, "accessUrlPrefix", "/files/");
        ReflectionTestUtils.setField(urlResolver, "presignedTtlMinutes", 60L);
        ReflectionTestUtils.setField(urlResolver, "privateAclEnabled", false);
        ReflectionTestUtils.setField(photoService, "photoUrlResolver", urlResolver);
    }

    @AfterEach
    void tearDown() throws IOException {
        // 清理临时目录
        if (tempUploadDir != null && Files.exists(tempUploadDir)) {
            Files.walk(tempUploadDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        }
    }

    // ============================================================
    // upload() 测试
    // ============================================================

    @Nested
    @DisplayName("upload() - 上传照片")
    class UploadTests {

        @Test
        @DisplayName("正常上传 JPEG 图片，应成功保存并返回 PhotoDTO（含 EXIF 结构化字段）")
        void shouldUploadJpegSuccessfully() throws Exception {
            // Arrange
            MockMultipartFile file = new MockMultipartFile(
                    "file", "test-photo.jpg", "image/jpeg",
                    imageBytes(JPEG_MAGIC, 1024) // 1KB（含真实 JPEG 文件头）
            );

            // 使用 ArgumentCaptor 捕获 insert 的 Photo 以验证结构化字段
            ArgumentCaptor<Photo> photoCaptor = ArgumentCaptor.forClass(Photo.class);
            when(photoMapper.insert(photoCaptor.capture())).thenReturn(1);
            // mock getById 用于 toDTO 中的分类名称填充
            when(categoryMapper.selectById(anyLong())).thenReturn(null);

            // Act
            PhotoDTO result = photoService.upload(file, "测试照片", 1L, null, "描述", "风景,旅行", null);

            // Assert - DTO 基本字段
            assertNotNull(result);
            assertEquals("测试照片", result.getTitle());
            assertEquals("描述", result.getDescription());
            assertEquals(1L, result.getCategoryId());
            assertEquals("风景,旅行", result.getTags());
            assertEquals("test-photo.jpg", result.getFileName());
            assertEquals(1024, result.getFileSize());
            assertTrue(result.getUrl().startsWith("/files/"));

            // 验证 Photo entity 的结构化字段已被设置（mock 图片无真实 EXIF，值应为空字符串）
            Photo captured = photoCaptor.getValue();
            assertNotNull(captured.getCameraModel(), "cameraModel should be set (empty for mock file)");
            assertNotNull(captured.getAperture(), "aperture should be set (empty for mock file)");
            assertNotNull(captured.getShutterSpeed(), "shutterSpeed should be set (empty for mock file)");
            assertNotNull(captured.getIso(), "iso should be set (empty for mock file)");
            assertNotNull(captured.getFocalLength(), "focalLength should be set (empty for mock file)");
            assertNotNull(captured.getDateTaken(), "dateTaken should be set (empty for mock file)");

            verify(photoMapper).insert(any(Photo.class));
        }

        @Test
        @DisplayName("上传 PNG 图片，应成功")
        void shouldUploadPngSuccessfully() throws Exception {
            // Arrange
            MockMultipartFile file = new MockMultipartFile(
                    "file", "screenshot.png", "image/png",
                    imageBytes(PNG_MAGIC, 512)
            );
            when(photoMapper.insert(any(Photo.class))).thenReturn(1);

            // Act
            PhotoDTO result = photoService.upload(file, null, null, null, null, null, null);

            // Assert: title 应回退为文件名
            assertNotNull(result);
            assertEquals("screenshot.png", result.getTitle());
            assertEquals("", result.getDescription());
            assertNull(result.getCategoryId());
            assertEquals("", result.getTags());
            verify(photoMapper).insert(any(Photo.class));
        }

        @Test
        @DisplayName("上传 WEBP 图片，应成功")
        void shouldUploadWebpSuccessfully() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "animated.webp", "image/webp", imageBytes(WEBP_MAGIC, 2048)
            );
            when(photoMapper.insert(any(Photo.class))).thenReturn(1);

            PhotoDTO result = photoService.upload(file, "动图", null, null, null, null, null);

            assertNotNull(result);
            assertEquals("动图", result.getTitle());
            verify(photoMapper).insert(any(Photo.class));
        }

        @Test
        @DisplayName("上传不支持的格式（TXT），应抛出 BusinessException")
        void shouldRejectInvalidFileType() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "document.txt", "text/plain", new byte[100]
            );

            BusinessException ex = assertThrows(BusinessException.class, () ->
                    photoService.upload(file, null, null, null, null, null, null)
            );

            assertThat(ex.getMessage()).contains("仅支持");
            verify(photoMapper, never()).insert(any());
        }

        @Test
        @DisplayName("上传不支持格式（PDF），应抛出 BusinessException")
        void shouldRejectPdfFile() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "report.pdf", "application/pdf", new byte[500]
            );

            BusinessException ex = assertThrows(BusinessException.class, () ->
                    photoService.upload(file, null, null, null, null, null, null)
            );

            assertThat(ex.getMessage()).contains("仅支持");
            verify(photoMapper, never()).insert(any());
        }

        @Test
        @DisplayName("上传空文件，应抛出 BusinessException")
        void shouldRejectEmptyFile() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "empty.jpg", "image/jpeg", new byte[0]
            );

            BusinessException ex = assertThrows(BusinessException.class, () ->
                    photoService.upload(file, null, null, null, null, null, null)
            );

            assertThat(ex.getMessage()).contains("不能为空");
            verify(photoMapper, never()).insert(any());
        }

        @Test
        @DisplayName("上传超过 10MB 的文件，应抛出 BusinessException")
        void shouldRejectOversizedFile() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "large.jpg", "image/jpeg",
                    new byte[11 * 1024 * 1024] // 11MB
            );

            BusinessException ex = assertThrows(BusinessException.class, () ->
                    photoService.upload(file, null, null, null, null, null, null)
            );

            assertThat(ex.getMessage()).contains("不能超过 10MB");
            verify(photoMapper, never()).insert(any());
        }

        @Test
        @DisplayName("上传伪装成图片的网页文件（扩展名为 jpg、内容为 HTML），应抛出 BusinessException")
        void shouldRejectDisguisedHtmlFile() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "fake.jpg", "image/jpeg",
                    "<html><script>alert(1)</script></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );

            BusinessException ex = assertThrows(BusinessException.class, () ->
                    photoService.upload(file, null, null, null, null, null, null)
            );

            assertThat(ex.getMessage()).contains("不是有效的图片");
            verify(photoMapper, never()).insert(any());
        }

        @Test
        @DisplayName("上传 GIF 格式，应成功")
        void shouldUploadGifSuccessfully() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "animation.gif", "image/gif", imageBytes(GIF_MAGIC, 512)
            );
            when(photoMapper.insert(any(Photo.class))).thenReturn(1);

            PhotoDTO result = photoService.upload(file, "GIF动画", null, null, null, null, null);

            assertNotNull(result);
            assertEquals("GIF动画", result.getTitle());
            verify(photoMapper).insert(any(Photo.class));
        }

        @Test
        @DisplayName("上传 BMP 格式，应成功")
        void shouldUploadBmpSuccessfully() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "bitmap.bmp", "image/bmp", imageBytes(BMP_MAGIC, 256)
            );
            when(photoMapper.insert(any(Photo.class))).thenReturn(1);

            PhotoDTO result = photoService.upload(file, "位图", null, null, null, null, null);

            assertNotNull(result);
            verify(photoMapper).insert(any(Photo.class));
        }

        @Test
        @DisplayName("上传时 categoryId 关联有效分类，DTO 中应有分类名称")
        void shouldFillCategoryNameInDto() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "photo.jpg", "image/jpeg", imageBytes(JPEG_MAGIC, 1024)
            );
            Category cat = new Category();
            cat.setId(1L);
            cat.setName("风景");

            when(photoMapper.insert(any(Photo.class))).thenReturn(1);
            when(categoryMapper.selectById(1L)).thenReturn(cat);

            PhotoDTO result = photoService.upload(file, "风景照", 1L, null, null, null, null);

            assertNotNull(result);
            assertEquals("风景", result.getCategoryName());
            verify(categoryMapper).selectById(1L);
        }
    }

    // ============================================================
    // deletePhoto() 测试
    // ============================================================

    @Nested
    @DisplayName("deletePhoto() - 删除照片")
    class DeletePhotoTests {

        @Test
        @DisplayName("正常删除存在的照片，应删除数据库记录")
        void shouldDeleteExistingPhoto() throws Exception {
            Photo photo = buildPhoto(1L, "test.jpg", "2026/05/test.jpg");

            when(photoMapper.selectById(1L)).thenReturn(photo);
            when(photoMapper.deleteById(1L)).thenReturn(1);

            photoService.deletePhoto(1L);

            verify(photoMapper).selectById(1L);
            verify(photoMapper).deleteById(1L);
        }

        @Test
        @DisplayName("删除不存在的照片，应抛出 BusinessException(code=404)")
        void shouldThrowWhenDeletingNonExistentPhoto() {
            when(photoMapper.selectById(999L)).thenReturn(null);

            BusinessException ex = assertThrows(BusinessException.class, () ->
                    photoService.deletePhoto(999L)
            );

            assertEquals(404, ex.getCode());
            assertThat(ex.getMessage()).contains("不存在");
            verify(photoMapper, never()).deleteById(anyLong());
        }

        @Test
        @DisplayName("删除照片时 URL 为 null，不应尝试删除磁盘文件（防御性编程）")
        void shouldHandleNullUrlGracefully() throws Exception {
            Photo photo = buildPhoto(2L, "nourl.jpg", null);

            when(photoMapper.selectById(2L)).thenReturn(photo);
            when(photoMapper.deleteById(2L)).thenReturn(1);

            // 不应抛出异常
            assertDoesNotThrow(() -> photoService.deletePhoto(2L));
            verify(photoMapper).deleteById(2L);
        }
    }

    // ============================================================
    // batchDelete() 测试
    // ============================================================

    @Nested
    @DisplayName("batchDelete() - 批量删除")
    class BatchDeleteTests {

        @Test
        @DisplayName("批量删除多张照片，应调用 deleteBatchIds")
        void shouldBatchDeletePhotos() throws Exception {
            List<Long> ids = Arrays.asList(1L, 2L, 3L);
            Photo p1 = buildPhoto(1L, "a.jpg", "2026/05/a.jpg");
            Photo p2 = buildPhoto(2L, "b.jpg", "2026/05/b.jpg");

            when(photoMapper.selectById(1L)).thenReturn(p1);
            when(photoMapper.selectById(2L)).thenReturn(p2);
            when(photoMapper.selectById(3L)).thenReturn(null); // 第三张不存在
            when(photoMapper.deleteBatchIds(ids)).thenReturn(2);

            photoService.batchDelete(ids);

            verify(photoMapper).deleteBatchIds(ids);
        }

        @Test
        @DisplayName("批量删除空列表，不应抛异常")
        void shouldHandleEmptyIdList() throws Exception {
            List<Long> ids = Collections.emptyList();

            when(photoMapper.deleteBatchIds(ids)).thenReturn(0);

            assertDoesNotThrow(() -> photoService.batchDelete(ids));
            verify(photoMapper).deleteBatchIds(ids);
        }

        @Test
        @DisplayName("批量删除时某张照片的磁盘文件不存在，应跳过继续处理")
        void shouldSkipMissingFileAndContinue() throws Exception {
            List<Long> ids = Arrays.asList(10L);
            Photo photo = buildPhoto(10L, "missing.jpg", "nonexistent/missing.jpg");

            when(photoMapper.selectById(10L)).thenReturn(photo);
            when(photoMapper.deleteBatchIds(ids)).thenReturn(1);

            // 文件不存在时 deleteFile 静默跳过
            assertDoesNotThrow(() -> photoService.batchDelete(ids));
            verify(photoMapper).deleteBatchIds(ids);
        }
    }

    // ============================================================
    // updatePhoto() 测试
    // ============================================================

    @Nested
    @DisplayName("updatePhoto() - 修改照片信息")
    class UpdatePhotoTests {

        @Test
        @DisplayName("正常更新照片信息，应更新字段并保存")
        void shouldUpdatePhotoSuccessfully() {
            Photo photo = buildPhoto(1L, "旧标题", "2026/05/old.jpg");
            photo.setDescription("旧描述");
            photo.setCategoryId(1L);

            PhotoDTO dto = new PhotoDTO();
            dto.setTitle("新标题");
            dto.setDescription("新描述");
            dto.setCategoryId(2L);
            dto.setTags("新标签");

            when(photoMapper.selectById(1L)).thenReturn(photo);
            when(photoMapper.updateById(any(Photo.class))).thenReturn(1);

            photoService.updatePhoto(1L, dto);

            assertEquals("新标题", photo.getTitle());
            assertEquals("新描述", photo.getDescription());
            assertEquals(2L, photo.getCategoryId());
            assertEquals("新标签", photo.getTags());
            verify(photoMapper).updateById(photo);
        }

        @Test
        @DisplayName("更新不存在的照片，应抛出 BusinessException")
        void shouldThrowWhenUpdatingNonExistentPhoto() {
            when(photoMapper.selectById(999L)).thenReturn(null);

            PhotoDTO dto = new PhotoDTO();
            dto.setTitle("新标题");

            BusinessException ex = assertThrows(BusinessException.class, () ->
                    photoService.updatePhoto(999L, dto)
            );

            assertEquals(404, ex.getCode());
            verify(photoMapper, never()).updateById(any());
        }

        @Test
        @DisplayName("部分更新（只更新标题），其他字段不变")
        void shouldPartialUpdate() {
            Photo photo = buildPhoto(1L, "旧标题", "path/a.jpg");
            photo.setDescription("旧描述");

            PhotoDTO dto = new PhotoDTO();
            dto.setTitle("新标题");
            // 不设置 description, categoryId, tags

            when(photoMapper.selectById(1L)).thenReturn(photo);
            when(photoMapper.updateById(any(Photo.class))).thenReturn(1);

            photoService.updatePhoto(1L, dto);

            assertEquals("新标题", photo.getTitle());
            assertEquals("旧描述", photo.getDescription()); // 未变
        }
    }

    // ============================================================
    // getDashboardStats() 测试
    // ============================================================

    @Nested
    @DisplayName("getDashboardStats() - 仪表盘统计")
    class DashboardStatsTests {

        @Test
        @DisplayName("有数据时，应正确返回各项统计")
        void shouldReturnStatsWithData() {
            // Arrange: 模拟 3 张照片，2 个分类
            Photo p1 = new Photo(); p1.setId(1L); p1.setTitle("照片1");
            p1.setViewCount(100); p1.setFileSize(1024L); p1.setCategoryId(1L); p1.setCreatedAt(LocalDateTime.now());
            Photo p2 = new Photo(); p2.setId(2L); p2.setTitle("照片2");
            p2.setViewCount(50); p2.setFileSize(2048L); p2.setCategoryId(2L); p2.setCreatedAt(LocalDateTime.now());
            Photo p3 = new Photo(); p3.setId(3L); p3.setTitle("照片3");
            p3.setViewCount(null); p3.setFileSize(null); p3.setCategoryId(1L); p3.setCreatedAt(LocalDateTime.now());

            Category cat1 = new Category(); cat1.setId(1L); cat1.setName("风景");
            Category cat2 = new Category(); cat2.setId(2L); cat2.setName("人像");

            when(photoMapper.selectCount(any())).thenReturn(3L);
            when(categoryMapper.selectCount(null)).thenReturn(2L);
            when(photoMapper.selectList(any())).thenReturn(Arrays.asList(p1, p2, p3));
            when(photoMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(p1, p2, p3));
            when(categoryMapper.selectById(1L)).thenReturn(cat1);
            when(categoryMapper.selectById(2L)).thenReturn(cat2);

            // Act
            Map<String, Object> stats = photoService.getDashboardStats();

            // Assert
            assertEquals(3L, stats.get("totalPhotos"));
            assertEquals(2L, stats.get("totalCategories"));
            assertEquals(150, stats.get("totalViews"));    // 100 + 50 + 0
            assertEquals("3.0 KB", stats.get("storageUsed")); // 1024 + 2048 + 0

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> recent = (List<Map<String, Object>>) stats.get("recentPhotos");
            assertNotNull(recent);
            assertEquals(3, recent.size());
            assertEquals("照片1", recent.get(0).get("title"));
            assertEquals("风景", recent.get(0).get("categoryName"));
        }

        @Test
        @DisplayName("无数据时，各项统计应为零")
        void shouldReturnZeroStatsWhenEmpty() {
            when(photoMapper.selectCount(any())).thenReturn(0L);
            when(categoryMapper.selectCount(null)).thenReturn(0L);
            when(photoMapper.selectList(any())).thenReturn(Collections.emptyList());
            when(photoMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

            Map<String, Object> stats = photoService.getDashboardStats();

            assertEquals(0L, stats.get("totalPhotos"));
            assertEquals(0L, stats.get("totalCategories"));
            assertEquals(0, stats.get("totalViews"));
            assertEquals("0 B", stats.get("storageUsed"));

            @SuppressWarnings("unchecked")
            List<?> recent = (List<?>) stats.get("recentPhotos");
            assertTrue(recent.isEmpty());
        }
    }

    // ============================================================
    // getPhotoPage() 测试
    // ============================================================

    @Nested
    @DisplayName("getPhotoPage() - 分页查询")
    class PhotoPageTests {

        @Test
        @DisplayName("无筛选条件时，应返回所有照片分页结果（含 EXIF 结构化字段）")
        void shouldReturnPagedResultsWithoutFilters() {
            PhotoDTO dto = new PhotoDTO();
            dto.setPageNum(1);
            dto.setPageSize(10);

            Photo photo = buildPhoto(1L, "照片1", "2026/05/p1.jpg");
            photo.setCategoryId(1L);
            photo.setExifInfo("{\"相机型号\":\"Canon EOS R5\"}");
            photo.setCameraModel("Canon EOS R5");
            photo.setAperture("f/2.8");
            photo.setShutterSpeed("1/125s");
            photo.setIso("100");
            photo.setFocalLength("50mm");
            photo.setDateTaken("2025:01:15 14:30:00");

            // Mock MyBatis-Plus Page
            Page<Photo> mockPage = new Page<>(1, 10);
            mockPage.setRecords(Arrays.asList(photo));
            mockPage.setTotal(1);

            when(photoMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(mockPage);
            when(categoryMapper.selectById(1L)).thenReturn(null);

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) photoService.getPhotoPage(dto);

            assertNotNull(result);
            assertEquals(1, ((List<?>) result.get("list")).size());
            assertEquals(1L, result.get("total"));
            assertEquals(1L, result.get("pageNum"));
            assertEquals(10L, result.get("pageSize"));

            // 验证 DTO 中包含 EXIF 结构化字段
            @SuppressWarnings("unchecked")
            List<PhotoDTO> list = (List<PhotoDTO>) result.get("list");
            PhotoDTO resultDto = list.get(0);
            assertEquals("Canon EOS R5", resultDto.getCameraModel());
            assertEquals("f/2.8", resultDto.getAperture());
            assertEquals("1/125s", resultDto.getShutterSpeed());
            assertEquals("100", resultDto.getIso());
            assertEquals("50mm", resultDto.getFocalLength());
            assertEquals("2025:01:15 14:30:00", resultDto.getDateTaken());
        }

        @Test
        @DisplayName("带关键词搜索，应过滤标题或描述匹配的记录")
        void shouldFilterByKeyword() {
            PhotoDTO dto = new PhotoDTO();
            dto.setPageNum(1);
            dto.setPageSize(10);
            dto.setKeyword("风景");

            Page<Photo> mockPage = new Page<>(1, 10);
            mockPage.setRecords(Collections.emptyList());
            mockPage.setTotal(0);

            when(photoMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(mockPage);

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) photoService.getPhotoPage(dto);

            assertEquals(0L, result.get("total"));
        }

        @Test
        @DisplayName("按分类筛选，应只返回对应分类的照片")
        void shouldFilterByCategory() {
            PhotoDTO dto = new PhotoDTO();
            dto.setPageNum(1);
            dto.setPageSize(10);
            dto.setCategoryIdFilter(5L);

            Photo photo = buildPhoto(1L, "照片1", "2026/05/p1.jpg");
            photo.setCategoryId(5L);

            Page<Photo> mockPage = new Page<>(1, 10);
            mockPage.setRecords(Arrays.asList(photo));
            mockPage.setTotal(1);

            when(photoMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(mockPage);
            when(categoryMapper.selectById(5L)).thenReturn(null);

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) photoService.getPhotoPage(dto);

            assertEquals(1, ((List<?>) result.get("list")).size());
        }
    }

    // ============================================================
    // getTagList() 测试
    // ============================================================

    @Nested
    @DisplayName("getTagList() - 获取标签列表")
    class TagListTests {

        @Test
        @DisplayName("有标签数据时，应返回按 count 降序的标签列表")
        void shouldReturnTagListSortedByCount() {
            // Arrange: "日出"出现2次，"风景"出现1次
            Photo p1 = new Photo();
            p1.setId(1L);
            p1.setTags("日出,风景");
            Photo p2 = new Photo();
            p2.setId(2L);
            p2.setTags("日出");

            // getTagList 会拼接可见性过滤条件，因此传入的是 LambdaQueryWrapper 而非 null
            when(photoMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(p1, p2));

            // Act
            List<Map<String, Object>> result = photoService.getTagList();

            // Assert: 按 count 降序
            assertEquals(2, result.size());
            assertEquals("日出", result.get(0).get("name"));
            assertEquals(2L, result.get(0).get("count"));
            assertEquals("风景", result.get(1).get("name"));
            assertEquals(1L, result.get(1).get("count"));
        }

        @Test
        @DisplayName("无标签数据时，应返回空列表")
        void shouldReturnEmptyListWhenNoTags() {
            when(photoMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

            List<Map<String, Object>> result = photoService.getTagList();

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("tags 为空字符串的照片应被正确跳过，不计入统计")
        void shouldSkipPhotosWithBlankTags() {
            Photo p1 = new Photo();
            p1.setId(1L);
            p1.setTags("日出");
            Photo p2 = new Photo();
            p2.setId(2L);
            p2.setTags("");          // 空字符串，应跳过
            Photo p3 = new Photo();
            p3.setId(3L);
            p3.setTags(null);        // null，应跳过

            when(photoMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(p1, p2, p3));

            List<Map<String, Object>> result = photoService.getTagList();

            assertEquals(1, result.size());
            assertEquals("日出", result.get(0).get("name"));
            assertEquals(1L, result.get(0).get("count"));
        }
    }

    // ============================================================
    // batchUpdate() 测试
    // ============================================================

    @Nested
    @DisplayName("batchUpdate() - 批量更新照片")
    class BatchUpdateTests {

        @Test
        @DisplayName("修改分类 + 追加标签，应同时更新 categoryId 和 tags")
        void shouldUpdateCategoryAndAppendTags() {
            List<Long> ids = Arrays.asList(1L, 2L);
            Photo p1 = new Photo();
            p1.setId(1L);
            p1.setTags("风景");
            p1.setCategoryId(1L);
            Photo p2 = new Photo();
            p2.setId(2L);
            p2.setTags("人像");
            p2.setCategoryId(2L);

            when(photoMapper.selectBatchIds(ids)).thenReturn(Arrays.asList(p1, p2));
            when(photoMapper.updateById(any(Photo.class))).thenReturn(1);

            photoService.batchUpdate(ids, 5L, "新标签");

            // 验证两张照片都被更新
            assertEquals(5L, p1.getCategoryId());
            assertTrue(p1.getTags().contains("新标签"));
            assertTrue(p1.getTags().contains("风景"));
            assertEquals(5L, p2.getCategoryId());
            assertTrue(p2.getTags().contains("新标签"));
            assertTrue(p2.getTags().contains("人像"));
            verify(photoMapper, times(2)).updateById(any(Photo.class));
        }

        @Test
        @DisplayName("只修改分类不追加标签，应仅更新 categoryId")
        void shouldUpdateOnlyCategoryWhenNoAppendTags() {
            List<Long> ids = Arrays.asList(1L);
            Photo photo = new Photo();
            photo.setId(1L);
            photo.setTags("风景");
            photo.setCategoryId(1L);

            when(photoMapper.selectBatchIds(ids)).thenReturn(Arrays.asList(photo));
            when(photoMapper.updateById(any(Photo.class))).thenReturn(1);

            photoService.batchUpdate(ids, 3L, null);

            assertEquals(3L, photo.getCategoryId());
            assertEquals("风景", photo.getTags()); // tags 不变
            verify(photoMapper).updateById(any(Photo.class));
        }

        @Test
        @DisplayName("追加标签与已有标签重复时，应去重不重复添加")
        void shouldDeduplicateTagsWhenAppending() {
            List<Long> ids = Arrays.asList(1L);
            Photo photo = new Photo();
            photo.setId(1L);
            photo.setTags("风景,日出");
            photo.setCategoryId(1L);

            when(photoMapper.selectBatchIds(ids)).thenReturn(Arrays.asList(photo));
            when(photoMapper.updateById(any(Photo.class))).thenReturn(1);

            // 追加"日出"（已存在）和"旅行"（新标签）
            photoService.batchUpdate(ids, null, "日出,旅行");

            String tags = photo.getTags();
            // LinkedHashSet 去重，保留插入顺序：风景,日出,旅行
            assertTrue(tags.contains("风景"));
            assertTrue(tags.contains("日出"));
            assertTrue(tags.contains("旅行"));
            // "日出"不应重复出现
            assertEquals(1, tags.split("日出", -1).length - 1);
            assertEquals(1, tags.split("旅行", -1).length - 1);
            verify(photoMapper).updateById(any(Photo.class));
        }
    }

    // ============================================================
    // getDashboardStats() - EXIF 统计增强测试
    // ============================================================

    @Nested
    @DisplayName("getDashboardStats() - EXIF 统计字段（增强）")
    class DashboardStatsExifTests {

        @Test
        @DisplayName("有 EXIF 数据时，exifStats 应包含各维度分布数据")
        void shouldIncludeExifStatsWhenExifDataPresent() {
            // Arrange: 两张有 EXIF 数据的照片
            Photo p1 = new Photo();
            p1.setId(1L);
            p1.setTitle("照片1");
            p1.setViewCount(10);
            p1.setFileSize(1024L);
            p1.setCategoryId(1L);
            p1.setCreatedAt(java.time.LocalDateTime.now());
            p1.setCameraModel("Canon EOS R5");
            p1.setFocalLength("50mm");
            p1.setIso("100");
            p1.setDateTaken("2025:01:15 14:30:00");

            Photo p2 = new Photo();
            p2.setId(2L);
            p2.setTitle("照片2");
            p2.setViewCount(20);
            p2.setFileSize(2048L);
            p2.setCategoryId(1L);
            p2.setCreatedAt(java.time.LocalDateTime.now());
            p2.setCameraModel("Canon EOS R5");
            p2.setFocalLength("24mm");
            p2.setIso("400");
            p2.setDateTaken("2024:06:20 10:00:00");

            when(photoMapper.selectCount(any())).thenReturn(2L);
            when(categoryMapper.selectCount(isNull())).thenReturn(1L);
            when(photoMapper.selectList(isNull())).thenReturn(Arrays.asList(p1, p2));
            when(photoMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(p1, p2));
            when(categoryMapper.selectById(anyLong())).thenReturn(null);

            // Act
            Map<String, Object> stats = photoService.getDashboardStats();

            // Assert
            @SuppressWarnings("unchecked")
            Map<String, Object> exifStats = (Map<String, Object>) stats.get("exifStats");
            assertNotNull(exifStats);
            assertTrue(exifStats.containsKey("focalLengths"));
            assertTrue(exifStats.containsKey("cameras"));
            assertTrue(exifStats.containsKey("isos"));
            assertTrue(exifStats.containsKey("yearDistribution"));

            // 验证相机型号分布（服务端做归一化：去空格并统一大写）
            @SuppressWarnings("unchecked")
            Map<String, Long> cameras = (Map<String, Long>) exifStats.get("cameras");
            assertEquals(1, cameras.size());
            assertEquals(2L, cameras.get("CANON EOS R5"));

            // 验证焦段分布
            @SuppressWarnings("unchecked")
            Map<String, Long> focalLengths = (Map<String, Long>) exifStats.get("focalLengths");
            assertEquals(2, focalLengths.size());
            assertEquals(1L, focalLengths.get("50mm"));
            assertEquals(1L, focalLengths.get("24mm"));

            // 验证 ISO 分布
            @SuppressWarnings("unchecked")
            Map<String, Long> isos = (Map<String, Long>) exifStats.get("isos");
            assertEquals(2, isos.size());
            assertEquals(1L, isos.get("100"));
            assertEquals(1L, isos.get("400"));

            // 验证年份分布
            @SuppressWarnings("unchecked")
            Map<String, Long> yearDist = (Map<String, Long>) exifStats.get("yearDistribution");
            assertEquals(2, yearDist.size());
            assertEquals(1L, yearDist.get("2025"));
            assertEquals(1L, yearDist.get("2024"));
        }

        @Test
        @DisplayName("无 EXIF 数据时，exifStats 各字段应为空 map")
        void shouldHaveEmptyExifStatsWhenNoExifData() {
            // Arrange: 照片没有 EXIF 数据
            Photo photo = new Photo();
            photo.setId(1L);
            photo.setTitle("无EXIF照片");
            photo.setViewCount(5);
            photo.setFileSize(512L);
            photo.setCategoryId(1L);
            photo.setCreatedAt(java.time.LocalDateTime.now());
            photo.setCameraModel("");
            photo.setFocalLength("");
            photo.setIso("");
            photo.setDateTaken("");

            when(photoMapper.selectCount(any())).thenReturn(1L);
            when(categoryMapper.selectCount(isNull())).thenReturn(1L);
            when(photoMapper.selectList(isNull())).thenReturn(Arrays.asList(photo));
            when(photoMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(photo));
            when(categoryMapper.selectById(anyLong())).thenReturn(null);

            // Act
            Map<String, Object> stats = photoService.getDashboardStats();

            // Assert
            @SuppressWarnings("unchecked")
            Map<String, Object> exifStats = (Map<String, Object>) stats.get("exifStats");
            assertNotNull(exifStats);
            assertTrue(exifStats.containsKey("focalLengths"));
            assertTrue(exifStats.containsKey("cameras"));
            assertTrue(exifStats.containsKey("isos"));
            assertTrue(exifStats.containsKey("yearDistribution"));

            // 各字段应为空 map（空字符串被 filter 跳过）
            @SuppressWarnings("unchecked")
            Map<String, Long> focalLengths = (Map<String, Long>) exifStats.get("focalLengths");
            assertTrue(focalLengths.isEmpty());

            @SuppressWarnings("unchecked")
            Map<String, Long> cameras = (Map<String, Long>) exifStats.get("cameras");
            assertTrue(cameras.isEmpty());

            @SuppressWarnings("unchecked")
            Map<String, Long> isos = (Map<String, Long>) exifStats.get("isos");
            assertTrue(isos.isEmpty());

            @SuppressWarnings("unchecked")
            Map<String, Long> yearDist = (Map<String, Long>) exifStats.get("yearDistribution");
            assertTrue(yearDist.isEmpty());
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
        photo.setFileName(title);
        photo.setFileSize(1024L);
        photo.setViewCount(0);
        photo.setDescription("");
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
}
