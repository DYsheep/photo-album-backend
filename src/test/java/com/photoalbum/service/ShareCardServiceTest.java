package com.photoalbum.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.photoalbum.dto.ShareCard;
import com.photoalbum.entity.Photo;
import com.photoalbum.entity.PhotoCollection;
import com.photoalbum.entity.PhotoCollectionPhoto;
import com.photoalbum.entity.ShareLink;
import com.photoalbum.entity.User;
import com.photoalbum.mapper.PhotoCollectionMapper;
import com.photoalbum.mapper.PhotoCollectionPhotoMapper;
import com.photoalbum.mapper.PhotoMapper;
import com.photoalbum.mapper.ShareLinkMapper;
import com.photoalbum.mapper.UserMapper;
import com.photoalbum.security.AccessPolicy;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.ObjectMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ShareCardService 单元测试
 *
 * 覆盖点：卡片信息只取"这个分享里本来就看得到的内容"，
 * 失效 / 需口令 / 不可见的分享不得泄露任何标题与封面。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShareCardService 单元测试")
class ShareCardServiceTest {

    @Mock
    private ShareLinkMapper shareLinkMapper;
    @Mock
    private PhotoMapper photoMapper;
    @Mock
    private PhotoCollectionMapper collectionMapper;
    @Mock
    private PhotoCollectionPhotoMapper collectionPhotoMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private AccessPolicy accessPolicy;
    @Mock
    private COSClient cosClient;

    @InjectMocks
    private ShareCardService shareCardService;

    private static final String BASE = "https://album.test";
    private static final String DEFAULT_IMAGE = BASE + "/icons/icon-512.png";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(shareCardService, "shareBaseUrl", BASE);
        PhotoUrlResolver urlResolver = new PhotoUrlResolver(cosClient);
        ReflectionTestUtils.setField(urlResolver, "cosBucket", "test-bucket");
        ReflectionTestUtils.setField(urlResolver, "cosDomain", "https://cos.example.com");
        ReflectionTestUtils.setField(urlResolver, "accessUrlPrefix", "/files/");
        ReflectionTestUtils.setField(urlResolver, "presignedTtlMinutes", 60L);
        ReflectionTestUtils.setField(urlResolver, "privateAclEnabled", false);
        ReflectionTestUtils.setField(shareCardService, "photoUrlResolver", urlResolver);
    }

    // ============================================================
    // cardOf()
    // ============================================================

    @Nested
    @DisplayName("cardOf() - 分享页卡片元数据")
    class CardOfTests {

        @Test
        @DisplayName("分享码不存在：返回失效卡片，封面用站点默认图")
        void unknownCodeReturnsExpiredCard() {
            when(shareLinkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            ShareCard card = shareCardService.cardOf("NoSuch01");

            assertThat(card.title()).contains("失效");
            assertThat(card.description()).contains("不存在或已过期");
            assertThat(card.imageUrl()).isEqualTo(DEFAULT_IMAGE);
            assertThat(card.url()).isEqualTo(BASE + "/share/NoSuch01");
        }

        @Test
        @DisplayName("分享码为空：按失效处理，不查库")
        void blankCodeReturnsExpiredCard() {
            ShareCard card = shareCardService.cardOf("   ");

            assertThat(card.title()).contains("失效");
            verifyNoInteractions(shareLinkMapper);
        }

        @Test
        @DisplayName("已过期的链接：与不存在同样按失效处理（不区分原因）")
        void expiredLinkReturnsExpiredCard() {
            ShareLink link = collectionLink(8L, false, null);
            link.setExpiresAt(LocalDateTime.now().minusMinutes(1));
            when(shareLinkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(link);

            assertThat(shareCardService.cardOf("Coll1234").title()).contains("失效");
        }

        @Test
        @DisplayName("设置了口令：返回通用卡片，不泄露合集名称（连合集都不查询）")
        void protectedShareNeverLeaksName() {
            when(shareLinkMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(collectionLink(8L, false, "$2a$10$abcdefghijklmnopqrstuv"));

            ShareCard card = shareCardService.cardOf("Coll1234");

            assertThat(card.title()).isEqualTo("受保护的分享");
            assertThat(card.description()).contains("口令");
            assertThat(card.imageUrl()).isEqualTo(DEFAULT_IMAGE);
            verifyNoInteractions(collectionMapper);
        }

        @Test
        @DisplayName("合集分享：标题取合集名、描述取合集说明、封面指向同域封面接口")
        void collectionCardUsesCollectionInfo() {
            when(shareLinkMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(collectionLink(8L, false, null));

            PhotoCollection collection = new PhotoCollection();
            collection.setId(8L);
            collection.setName("我们的饭");
            collection.setDescription("2026 春天的记录");
            when(collectionMapper.selectById(8L)).thenReturn(collection);
            when(collectionPhotoMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(rel(8L, 21L)));
            when(photoMapper.selectById(21L)).thenReturn(photo(21L, 0, "p21"));

            ShareCard card = shareCardService.cardOf("Coll1234");

            assertThat(card.title()).isEqualTo("我们的饭");
            assertThat(card.description()).isEqualTo("2026 春天的记录");
            assertThat(card.imageUrl()).isEqualTo(BASE + "/api/share/Coll1234/cover");
            assertThat(card.url()).isEqualTo(BASE + "/share/Coll1234");
        }

        @Test
        @DisplayName("合集没有说明时：用可见照片数量作描述，私密照片不计入")
        void collectionDescriptionFallsBackToVisibleCount() {
            when(shareLinkMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(collectionLink(8L, false, null));
            PhotoCollection collection = new PhotoCollection();
            collection.setId(8L);
            collection.setName("不含私密的合集");
            when(collectionMapper.selectById(8L)).thenReturn(collection);
            when(collectionPhotoMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(rel(8L, 21L), rel(8L, 22L)));
            when(photoMapper.selectById(21L)).thenReturn(photo(21L, 0, "p21"));
            when(photoMapper.selectById(22L)).thenReturn(photo(22L, 1, "p22"));

            assertThat(shareCardService.cardOf("Coll1234").description()).isEqualTo("共 1 张照片");
        }

        @Test
        @DisplayName("含私密的合集分享：以创建者视角计数，被 deny 的照片既不计入也不作封面")
        void includePrivateUsesCreatorScope() {
            ShareLink link = collectionLink(8L, true, null);
            link.setCreatedBy(1L);
            when(shareLinkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(link);
            PhotoCollection collection = new PhotoCollection();
            collection.setId(8L);
            collection.setName("我们的饭");
            when(collectionMapper.selectById(8L)).thenReturn(collection);
            when(collectionPhotoMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(rel(8L, 10L), rel(8L, 11L)));
            when(userMapper.selectById(1L)).thenReturn(new User());
            when(photoMapper.selectById(10L)).thenReturn(photo(10L, 1, "p10"));
            when(photoMapper.selectById(11L)).thenReturn(photo(11L, 1, "p11"));
            // 创建者可见 10 号，11 号被其 deny
            when(accessPolicy.canViewPhoto(any(), any())).thenAnswer(inv ->
                    ((Photo) inv.getArgument(1)).getId().equals(10L));

            ShareCard card = shareCardService.cardOf("Coll1234");

            assertThat(card.description()).isEqualTo("共 1 张照片");
            assertThat(shareCardService.coverStoredUrlOf("Coll1234"))
                    .isEqualTo("https://cos.example.com/p10_thumb.jpg");
        }

        @Test
        @DisplayName("照片分享：标题取照片标题，无文字说明时用拍摄参数补足描述")
        void photoCardUsesExifWhenDescriptionMissing() {
            when(shareLinkMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(photoLink(1L, "AbCd1234"));
            Photo photo = photo(1L, 0, "sunset");
            photo.setCameraModel("Sony A7M4");
            photo.setFocalLength("35mm");
            photo.setAperture("f/1.8");
            when(photoMapper.selectById(1L)).thenReturn(photo);
            when(accessPolicy.canViewPhoto(any(), any())).thenReturn(true);

            ShareCard card = shareCardService.cardOf("AbCd1234");

            assertThat(card.title()).isEqualTo("照片1");
            assertThat(card.description()).contains("Sony A7M4").contains("f/1.8");
        }

        @Test
        @DisplayName("照片分享：照片对匿名访问者不可见（私密）时按失效处理，不泄露标题与封面")
        void privatePhotoShareIsUnavailableForGuests() {
            when(shareLinkMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(photoLink(1L, "Priv1234"));
            when(photoMapper.selectById(1L)).thenReturn(photo(1L, 1, "secret"));
            when(accessPolicy.canViewPhoto(any(), any())).thenReturn(false);

            ShareCard card = shareCardService.cardOf("Priv1234");

            assertThat(card.title()).contains("失效");
            assertThat(card.imageUrl()).isEqualTo(DEFAULT_IMAGE);
            assertThat(shareCardService.coverStoredUrlOf("Priv1234")).isNull();
        }
    }

    // ============================================================
    // coverTargetOf()
    // ============================================================

    @Nested
    @DisplayName("coverStoredUrlOf() / coverImageOf() - 卡片封面")
    class CoverTargetTests {

        @Test
        @DisplayName("链接不存在：取不到封面")
        void missingLinkReturnsNull() {
            when(shareLinkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            assertThat(shareCardService.coverStoredUrlOf("NoSuch01")).isNull();
        }

        @Test
        @DisplayName("需口令的分享：不提供封面，不泄露任何照片")
        void protectedShareReturnsNull() {
            when(shareLinkMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(collectionLink(8L, false, "$2a$10$abcdefghijklmnopqrstuv"));

            assertThat(shareCardService.coverStoredUrlOf("Coll1234")).isNull();
        }

        @Test
        @DisplayName("合集分享：优先使用合集封面照片的缩略图")
        void collectionCoverPhotoPreferred() {
            when(shareLinkMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(collectionLink(8L, false, null));
            PhotoCollection collection = new PhotoCollection();
            collection.setId(8L);
            collection.setCoverPhotoId(21L);
            when(collectionMapper.selectById(8L)).thenReturn(collection);
            when(collectionPhotoMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(rel(8L, 21L)));
            when(photoMapper.selectById(21L)).thenReturn(photo(21L, 0, "cover"));

            assertThat(shareCardService.coverStoredUrlOf("Coll1234"))
                    .isEqualTo("https://cos.example.com/cover_thumb.jpg");
        }

        @Test
        @DisplayName("合集封面不可见（私密且不含私密）时，退回第一张公开照片")
        void fallsBackToFirstVisiblePhoto() {
            when(shareLinkMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(collectionLink(8L, false, null));
            PhotoCollection collection = new PhotoCollection();
            collection.setId(8L);
            collection.setCoverPhotoId(21L);
            when(collectionMapper.selectById(8L)).thenReturn(collection);
            when(collectionPhotoMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(rel(8L, 21L), rel(8L, 22L)));
            when(photoMapper.selectById(21L)).thenReturn(photo(21L, 1, "private"));
            when(photoMapper.selectById(22L)).thenReturn(photo(22L, 0, "public"));

            assertThat(shareCardService.coverStoredUrlOf("Coll1234"))
                    .isEqualTo("https://cos.example.com/public_thumb.jpg");
        }

        @Test
        @DisplayName("合集内没有任何可见照片：取不到封面")
        void noVisiblePhotoReturnsNull() {
            when(shareLinkMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(collectionLink(8L, false, null));
            PhotoCollection collection = new PhotoCollection();
            collection.setId(8L);
            when(collectionMapper.selectById(8L)).thenReturn(collection);
            when(collectionPhotoMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(rel(8L, 21L)));
            when(photoMapper.selectById(21L)).thenReturn(photo(21L, 1, "private"));

            assertThat(shareCardService.coverStoredUrlOf("Coll1234")).isNull();
        }

        @Test
        @DisplayName("合集不存在：取不到封面")
        void missingCollectionReturnsNull() {
            when(shareLinkMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(collectionLink(8L, false, null));
            when(collectionMapper.selectById(8L)).thenReturn(null);

            assertThat(shareCardService.coverStoredUrlOf("Coll1234")).isNull();
        }

        @Test
        @DisplayName("封面图片：直接读出对象字节与类型（平台抓图不跟随跳转，必须 200 直出）")
        void coverImageReturnsObjectBytes() {
            when(shareLinkMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(collectionLink(8L, false, null));
            PhotoCollection collection = new PhotoCollection();
            collection.setId(8L);
            collection.setCoverPhotoId(21L);
            when(collectionMapper.selectById(8L)).thenReturn(collection);
            when(collectionPhotoMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(rel(8L, 21L)));
            when(photoMapper.selectById(21L)).thenReturn(photo(21L, 0, "cover"));

            byte[] bytes = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3};
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType("image/jpeg");
            COSObject cosObject = new COSObject();
            cosObject.setObjectMetadata(metadata);
            cosObject.setObjectContent(new ByteArrayInputStream(bytes));
            when(cosClient.getObject("test-bucket", "cover_thumb.jpg")).thenReturn(cosObject);

            PhotoUrlResolver.ObjectData data = shareCardService.coverImageOf("Coll1234");

            assertThat(data).isNotNull();
            assertThat(data.bytes()).containsExactly(bytes);
            assertThat(data.contentType()).isEqualTo("image/jpeg");
        }

        @Test
        @DisplayName("封面图片：对象读取失败时返回 null，由调用方退回默认图")
        void coverImageReturnsNullWhenObjectUnreadable() {
            when(shareLinkMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(collectionLink(8L, false, null));
            PhotoCollection collection = new PhotoCollection();
            collection.setId(8L);
            collection.setCoverPhotoId(21L);
            when(collectionMapper.selectById(8L)).thenReturn(collection);
            when(collectionPhotoMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(rel(8L, 21L)));
            when(photoMapper.selectById(21L)).thenReturn(photo(21L, 0, "cover"));
            when(cosClient.getObject("test-bucket", "cover_thumb.jpg"))
                    .thenThrow(new RuntimeException("对象存储不可用"));

            assertThat(shareCardService.coverImageOf("Coll1234")).isNull();
        }
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private ShareLink collectionLink(Long collectionId, boolean includePrivate, String accessCode) {
        ShareLink link = new ShareLink();
        link.setId(99L);
        link.setCode("Coll1234");
        link.setTargetType("collection");
        link.setTargetId(collectionId);
        link.setIncludePrivate(includePrivate ? 1 : 0);
        link.setAccessCode(accessCode);
        link.setCreatedBy(1L);
        return link;
    }

    private ShareLink photoLink(Long photoId, String code) {
        ShareLink link = new ShareLink();
        link.setId(98L);
        link.setCode(code);
        link.setPhotoId(photoId);
        return link;
    }

    private Photo photo(Long id, int isPrivate, String key) {
        Photo photo = new Photo();
        photo.setId(id);
        photo.setTitle("照片" + id);
        photo.setUrl("https://cos.example.com/" + key + ".jpg");
        photo.setThumbnailUrl("https://cos.example.com/" + key + "_thumb.jpg");
        photo.setIsPrivate(isPrivate);
        return photo;
    }

    private PhotoCollectionPhoto rel(Long collectionId, Long photoId) {
        PhotoCollectionPhoto rel = new PhotoCollectionPhoto();
        rel.setCollectionId(collectionId);
        rel.setPhotoId(photoId);
        rel.setSortOrder(0);
        return rel;
    }
}
