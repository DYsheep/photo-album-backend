package com.photoalbum.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.photoalbum.common.BusinessException;
import com.photoalbum.dto.ShareLinkDTO;
import com.photoalbum.entity.PhotoCollection;
import com.photoalbum.entity.ShareLink;
import com.photoalbum.entity.User;
import com.photoalbum.mapper.PhotoCollectionMapper;
import com.photoalbum.mapper.PhotoCollectionPhotoMapper;
import com.photoalbum.mapper.PhotoMapper;
import com.photoalbum.mapper.ShareLinkMapper;
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

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 合集分享创建规则测试（对应安全审查报告 H03 的创建侧）
 *
 * 规则来源：私密照片的对象虽为私有 ACL，但分享卡片封面接口是服务端用自身凭据代取对象字节后直出的，
 * 不受对象级 ACL 约束。因此"含私密"与"无口令"一旦能同时成立，
 * 拿到分享码的人（分享码随二维码与卡片转发扩散）就等于拿到了私密照片。
 *
 * 三条创建侧约束：
 *   1. 含私密内容必须设置访问口令；
 *   2. 含私密内容不默认永久有效（未指定时给 30 天），显式超过 90 天则拒绝；
 *   3. 只要设置了口令就要满足强度要求（至少 6 位且不得为纯数字）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("合集分享创建规则（H03 修复验证）")
class ShareLinkSecurityRulesTest {

    private static final Long COLLECTION_ID = 8L;

    @Mock
    private ShareLinkMapper shareLinkMapper;
    @Mock
    private PhotoCollectionMapper collectionMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private PhotoCollectionPhotoMapper collectionPhotoMapper;
    @Mock
    private PhotoMapper photoMapper;
    @Mock
    private AccessPolicy accessPolicy;
    @Mock
    private PhotoUrlResolver photoUrlResolver;
    @Mock
    private COSClient cosClient;

    @InjectMocks
    private ShareServiceImpl shareService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(shareService, "shareBaseUrl", "https://www.example.com");
        // 合集可管理（本测试只关注创建规则本身）
        PhotoCollection collection = new PhotoCollection();
        collection.setId(COLLECTION_ID);
        collection.setName("测试合集");
        when(collectionMapper.selectById(COLLECTION_ID)).thenReturn(collection);
        when(accessPolicy.canManageCollection(any(), any())).thenReturn(true);
        // 首次创建：不存在同合集的既有链接。
        // 校验不通过的用例会在走到这两步之前就抛出，故声明为 lenient，避免严格桩模式误报未使用
        lenient().when(shareLinkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        lenient().when(shareLinkMapper.insert(any(ShareLink.class))).thenReturn(1);
    }

    @Nested
    @DisplayName("含私密内容必须设口令")
    class PrivateRequiresAccessCodeTests {

        @Test
        @DisplayName("含私密但未设口令：拒绝创建")
        void privateWithoutAccessCodeIsRejected() {
            assertThatThrownBy(() -> shareService.createCollectionShare(
                    COLLECTION_ID, null, true, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("必须设置访问口令");
        }

        @Test
        @DisplayName("含私密但口令为空白：拒绝创建")
        void privateWithBlankAccessCodeIsRejected() {
            assertThatThrownBy(() -> shareService.createCollectionShare(
                    COLLECTION_ID, null, true, "   "))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("必须设置访问口令");
        }

        @Test
        @DisplayName("含私密且口令合法：创建成功并标记需要口令")
        void privateWithValidAccessCodeSucceeds() {
            ShareLinkDTO dto = shareService.createCollectionShare(
                    COLLECTION_ID, null, true, "Album-2026");

            assertThat(dto.getRequiresAccessCode()).isTrue();
            assertThat(dto.getIncludePrivate()).isEqualTo(1);
        }

        @Test
        @DisplayName("不含私密时未设口令：仍允许（公开分享的既有语义不变）")
        void publicShareMayRemainUnprotected() {
            ShareLinkDTO dto = shareService.createCollectionShare(
                    COLLECTION_ID, null, false, null);

            assertThat(dto.getRequiresAccessCode()).isFalse();
            assertThat(dto.getIncludePrivate()).isEqualTo(0);
            // 公开分享不设口令时保持"永久有效"（expiresAt 为 null）
            assertThat(dto.getExpiresAt()).isNull();
        }
    }

    @Nested
    @DisplayName("含私密内容的有效期收敛")
    class PrivateExpiryTests {

        @Test
        @DisplayName("含私密且未指定有效期：按 30 天设置，而不是永久有效")
        void privateWithoutExpiryGetsDefaultWindow() {
            LocalDateTime before = LocalDateTime.now().plusDays(30).minusMinutes(1);

            ShareLinkDTO dto = shareService.createCollectionShare(
                    COLLECTION_ID, null, true, "Album-2026");

            assertThat(dto.getExpiresAt()).isNotNull();
            assertThat(dto.getExpiresAt()).isAfter(before);
            assertThat(ChronoUnit.DAYS.between(LocalDateTime.now(), dto.getExpiresAt()))
                    .isBetween(29L, 30L);
        }

        @Test
        @DisplayName("含私密且有效期超过 90 天：拒绝创建而非静默截断")
        void privateExpiryBeyondLimitIsRejected() {
            LocalDateTime tooLate = LocalDateTime.now().plusDays(91);

            assertThatThrownBy(() -> shareService.createCollectionShare(
                    COLLECTION_ID, tooLate, true, "Album-2026"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("90 天");
        }

        @Test
        @DisplayName("含私密且有效期在上限内：按指定值保留")
        void privateExpiryWithinLimitIsKept() {
            LocalDateTime expiry = LocalDateTime.now().plusDays(7);

            ShareLinkDTO dto = shareService.createCollectionShare(
                    COLLECTION_ID, expiry, true, "Album-2026");

            assertThat(dto.getExpiresAt()).isEqualTo(expiry);
        }

        @Test
        @DisplayName("不含私密时不受 90 天上限约束")
        void publicShareHasNoPrivateExpiryLimit() {
            LocalDateTime longExpiry = LocalDateTime.now().plusDays(365);

            ShareLinkDTO dto = shareService.createCollectionShare(
                    COLLECTION_ID, longExpiry, false, null);

            assertThat(dto.getExpiresAt()).isEqualTo(longExpiry);
        }
    }

    @Nested
    @DisplayName("访问口令强度（H02 相关）")
    class AccessCodeStrengthTests {

        @Test
        @DisplayName("口令不足 6 位：拒绝")
        void shortAccessCodeIsRejected() {
            assertThatThrownBy(() -> shareService.createCollectionShare(
                    COLLECTION_ID, null, false, "Ab12"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("至少 6 位");
        }

        @Test
        @DisplayName("口令为纯数字：拒绝（短纯数字最易被穷举）")
        void allDigitAccessCodeIsRejected() {
            assertThatThrownBy(() -> shareService.createCollectionShare(
                    COLLECTION_ID, null, false, "12345678"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("纯数字");
        }

        @Test
        @DisplayName("口令含字母：通过（公开分享设口令同样受强度约束）")
        void mixedAccessCodeIsAccepted() {
            ShareLinkDTO dto = shareService.createCollectionShare(
                    COLLECTION_ID, null, false, "abcd12");

            assertThat(dto.getRequiresAccessCode()).isTrue();
        }
    }

    @Nested
    @DisplayName("创建者（含口令时返回的状态字段）")
    class DtoFieldTests {

        @Test
        @DisplayName("创建的分享会记录创建者，供含私密时按创建者视角判定")
        void creatorIsRecorded() {
            User operator = new User();
            operator.setId(42L);
            org.springframework.security.core.context.SecurityContextHolder.getContext()
                    .setAuthentication(new org.springframework.security.authentication
                            .UsernamePasswordAuthenticationToken(operator, null, java.util.List.of()));
            try {
                ShareLinkDTO dto = shareService.createCollectionShare(
                        COLLECTION_ID, null, true, "Album-2026");
                assertThat(dto.getCollectionId()).isEqualTo(COLLECTION_ID);
            } finally {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
        }
    }
}
