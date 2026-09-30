package com.photoalbum.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.photoalbum.common.BusinessException;
import com.photoalbum.entity.Photo;
import com.photoalbum.mapper.CategoryMapper;
import com.photoalbum.mapper.PhotoCollectionPhotoMapper;
import com.photoalbum.mapper.PhotoMapper;
import com.photoalbum.mapper.ShareLinkMapper;
import com.photoalbum.security.AccessPolicy;
import com.photoalbum.service.PhotoUrlResolver;
import com.photoalbum.service.TagService;
import com.qcloud.cos.COSClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 点赞接口可见性判定测试（对应安全审查报告 M01）
 *
 * 缺陷回顾：POST /api/photos/{id}/like 对匿名开放，原实现先执行计数自增、
 * 再按主键查一次判断存在性，全程没有可见性判定。后果有两条：
 *   1. 成为一个“存在性预言机”——能点赞即说明该照片 ID 存在，
 *      而照片详情接口对不可见的私密照片刻意返回 404，两处口径矛盾；
 *   2. 匿名者可以给不可见的照片刷点赞数。
 *
 * 本测试固定修复后的两条约束：
 *   1. 不可见与不存在统一按 404 处理，且**不得执行计数更新**；
 *   2. 可见的公开照片照常计数（不误伤正常业务，未登录访客仍可点赞）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("点赞接口可见性判定（M01 修复验证）")
class PhotoServiceLikeVisibilityTest {

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
    private PhotoUrlResolver photoUrlResolver;
    @Mock
    private TagService tagService;
    @Mock
    private COSClient cosClient;

    @InjectMocks
    private PhotoServiceImpl photoService;

    private static Photo photo(Long id, int isPrivate, Integer likeCount) {
        Photo photo = new Photo();
        photo.setId(id);
        photo.setIsPrivate(isPrivate);
        photo.setLikeCount(likeCount);
        return photo;
    }

    @Test
    @DisplayName("不可见的私密照片：拒绝点赞，且不得执行计数更新")
    void invisiblePhotoIsRejectedWithoutCounting() {
        when(photoMapper.selectById(9L)).thenReturn(photo(9L, 1, 0));
        when(accessPolicy.canViewPhoto(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> photoService.likePhoto(9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("照片不存在");

        // 关键断言：判定必须发生在计数之前，否则响应差异仍能暴露照片是否存在
        verify(photoMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("照片不存在：同样按 404 处理，与不可见照片不可区分")
    void missingPhotoIsRejected() {
        when(photoMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> photoService.likePhoto(404L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("照片不存在");

        verify(photoMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("可见的公开照片：照常计数并返回最新点赞数（未登录访客仍可点赞）")
    void visiblePhotoIsCounted() {
        when(photoMapper.selectById(3L)).thenReturn(photo(3L, 0, 7));
        when(accessPolicy.canViewPhoto(any(), any())).thenReturn(true);

        assertThat(photoService.likePhoto(3L)).isEqualTo(7);

        verify(photoMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("私密照片但其对当前调用者可见：允许点赞")
    void visiblePrivatePhotoIsCounted() {
        when(photoMapper.selectById(11L)).thenReturn(photo(11L, 1, 2));
        when(accessPolicy.canViewPhoto(any(), any())).thenReturn(true);

        assertThat(photoService.likePhoto(11L)).isEqualTo(2);

        verify(photoMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("id 为空：按不存在处理，不触发任何查询")
    void nullIdIsRejected() {
        assertThatThrownBy(() -> photoService.likePhoto(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("照片不存在");

        verify(photoMapper, never()).selectById(any());
        verify(photoMapper, never()).update(any(), any());
    }
}
