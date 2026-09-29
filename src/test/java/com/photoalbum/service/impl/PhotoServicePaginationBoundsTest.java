package com.photoalbum.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.photoalbum.config.MybatisPlusConfig;
import com.photoalbum.dto.PhotoDTO;
import com.photoalbum.entity.Photo;
import com.photoalbum.mapper.CategoryMapper;
import com.photoalbum.mapper.PhotoCollectionPhotoMapper;
import com.photoalbum.mapper.PhotoMapper;
import com.photoalbum.mapper.ShareLinkMapper;
import com.photoalbum.security.AccessPolicy;
import com.photoalbum.service.TagService;
import com.qcloud.cos.COSClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 照片列表入参收敛测试（对应安全审查报告 H01 的第二个面）
 *
 * 缺陷回顾：分页拦截器缺失会让列表接口一次返回全表；而即便拦截器已注册，
 * 只要入参不受约束，仍存在两条绕过路径：
 *   1. pageSize 传极大值 —— 单次请求即可拉走全量数据并占用大量内存；
 *   2. pageSize 传负数 —— MyBatis-Plus 的语义是 **size < 0 表示不分页**，
 *      等于关掉刚注册的拦截器，直接退回“一次返回全表”。
 *
 * 因此下界与上界都必须由服务端收敛。本测试直接捕获传给 selectPage 的 Page 对象，
 * 断言收敛后的取值，而不是断言响应回显。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("照片分页入参收敛（H01 修复验证）")
class PhotoServicePaginationBoundsTest {

    /** 与 PhotoServiceImpl 内部默认值一致 */
    private static final int EXPECTED_DEFAULT_PAGE_SIZE = 10;

    @Mock
    private PhotoMapper photoMapper;
    @Mock
    private CategoryMapper categoryMapper;
    @Mock
    private PhotoCollectionPhotoMapper collectionPhotoMapper;
    @Mock
    private ShareLinkMapper shareLinkMapper;
    @Mock
    private AccessPolicy accessPolicy;
    @Mock
    private TagService tagService;
    @Mock
    private COSClient cosClient;

    @InjectMocks
    private PhotoServiceImpl photoService;

    @Captor
    private ArgumentCaptor<Page<Photo>> pageCaptor;

    /**
     * 执行一次分页查询，返回真正传给 Mapper 的 Page 对象
     *
     * 桩返回空结果集：本测试只关心入参如何被收敛，不触发记录到 DTO 的映射。
     */
    @SuppressWarnings("unchecked")
    private Page<Photo> pagePassedToMapper(Integer pageNum, Integer pageSize) {
        Page<Photo> stubbed = new Page<>(1, 10);
        stubbed.setRecords(Collections.emptyList());
        stubbed.setTotal(0L);
        when(photoMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(stubbed);

        PhotoDTO dto = new PhotoDTO();
        dto.setPageNum(pageNum);
        dto.setPageSize(pageSize);
        photoService.getPhotoPage(dto);

        verify(photoMapper).selectPage(pageCaptor.capture(), any(LambdaQueryWrapper.class));
        return pageCaptor.getValue();
    }

    @Nested
    @DisplayName("每页条数上界")
    class MaxPageSizeTests {

        @Test
        @DisplayName("pageSize 超过上限时收敛到 MAX_PAGE_SIZE，不能一次拉走全表")
        void oversizedPageSizeIsClamped() {
            Page<Photo> page = pagePassedToMapper(1, 999_999);

            assertThat(page.getSize()).isEqualTo(MybatisPlusConfig.MAX_PAGE_SIZE);
        }

        @Test
        @DisplayName("pageSize 恰好等于上限时保持不变")
        void pageSizeAtLimitIsKept() {
            Page<Photo> page = pagePassedToMapper(1, MybatisPlusConfig.MAX_PAGE_SIZE);

            assertThat(page.getSize()).isEqualTo(MybatisPlusConfig.MAX_PAGE_SIZE);
        }

        @Test
        @DisplayName("正常 pageSize 不被改动")
        void normalPageSizeIsKept() {
            Page<Photo> page = pagePassedToMapper(2, 20);

            assertThat(page.getSize()).isEqualTo(20);
            assertThat(page.getCurrent()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("每页条数下界（负数是绕过分页的路径）")
    class MinPageSizeTests {

        @Test
        @DisplayName("pageSize = -1 不得透传：MyBatis-Plus 中负数表示不分页，等于关掉拦截器")
        void negativePageSizeNeverReachesMapper() {
            Page<Photo> page = pagePassedToMapper(1, -1);

            assertThat(page.getSize())
                    .as("负数一旦传给 Mapper，分页拦截器会跳过 LIMIT，重新变成返回全表")
                    .isPositive();
            assertThat(page.getSize()).isEqualTo(EXPECTED_DEFAULT_PAGE_SIZE);
        }

        @Test
        @DisplayName("pageSize = 0 回退到默认条数")
        void zeroPageSizeFallsBackToDefault() {
            assertThat(pagePassedToMapper(1, 0).getSize()).isEqualTo(EXPECTED_DEFAULT_PAGE_SIZE);
        }

        @Test
        @DisplayName("pageSize = null 回退到默认条数")
        void nullPageSizeFallsBackToDefault() {
            assertThat(pagePassedToMapper(1, null).getSize()).isEqualTo(EXPECTED_DEFAULT_PAGE_SIZE);
        }
    }

    @Nested
    @DisplayName("页码下界")
    class PageNumTests {

        @Test
        @DisplayName("pageNum = 0 收敛到第 1 页")
        void zeroPageNumFallsBackToFirst() {
            assertThat(pagePassedToMapper(0, 10).getCurrent()).isEqualTo(1);
        }

        @Test
        @DisplayName("pageNum 为负数收敛到第 1 页")
        void negativePageNumFallsBackToFirst() {
            assertThat(pagePassedToMapper(-5, 10).getCurrent()).isEqualTo(1);
        }

        @Test
        @DisplayName("pageNum = null 收敛到第 1 页")
        void nullPageNumFallsBackToFirst() {
            assertThat(pagePassedToMapper(null, 10).getCurrent()).isEqualTo(1);
        }
    }
}
