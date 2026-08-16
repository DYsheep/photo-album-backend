package com.photoalbum.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.photoalbum.common.BusinessException;
import com.photoalbum.dto.ShareLinkDTO;
import com.photoalbum.entity.Photo;
import com.photoalbum.entity.ShareLink;
import com.photoalbum.mapper.PhotoMapper;
import com.photoalbum.mapper.ShareLinkMapper;
import com.photoalbum.service.ShareService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 分享链接服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareServiceImpl implements ShareService {

    /** 随机码字符集：大小写字母 + 数字 */
    private static final String CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int CODE_LENGTH = 8;
    private static final int MAX_RETRY = 10;

    private final ShareLinkMapper shareLinkMapper;
    private final PhotoMapper photoMapper;

    @Value("${share.base-url:http://localhost:5173}")
    private String shareBaseUrl;

    /**
     * 使用 SecureRandom 生成 8 位随机码（A-Za-z0-9）
     */
    private String generateCode() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * 生成唯一随机码（去重重试）
     */
    private String generateUniqueCode() {
        for (int i = 0; i < MAX_RETRY; i++) {
            String code = generateCode();
            Long count = shareLinkMapper.selectCount(
                    new LambdaQueryWrapper<ShareLink>().eq(ShareLink::getCode, code));
            if (count == 0) {
                return code;
            }
            log.debug("分享码 {} 已存在，重试第 {} 次", code, i + 1);
        }
        throw new BusinessException("生成分享码失败，请重试");
    }

    @Override
    public ShareLinkDTO createShareLink(Long photoId) {
        // 校验照片是否存在
        Photo photo = photoMapper.selectById(photoId);
        if (photo == null) {
            throw new BusinessException(404, "照片不存在");
        }

        // 检查是否已存在该照片的分享链接（复用已有）
        ShareLink existing = shareLinkMapper.selectOne(
                new LambdaQueryWrapper<ShareLink>().eq(ShareLink::getPhotoId, photoId));
        if (existing != null) {
            ShareLinkDTO dto = toDTO(existing);
            dto.setPhotoTitle(photo.getTitle());
            dto.setPhotoUrl(photo.getUrl());
            dto.setShareUrl(shareBaseUrl + "/share/" + existing.getCode());
            return dto;
        }

        // 生成唯一分享码
        String code = generateUniqueCode();

        ShareLink shareLink = new ShareLink();
        shareLink.setCode(code);
        shareLink.setPhotoId(photoId);
        shareLink.setCreatedAt(LocalDateTime.now());

        shareLinkMapper.insert(shareLink);
        log.info("分享链接创建成功: photoId={}, code={}", photoId, code);

        ShareLinkDTO dto = toDTO(shareLink);
        dto.setPhotoTitle(photo.getTitle());
        dto.setPhotoUrl(photo.getUrl());
        dto.setShareUrl(shareBaseUrl + "/share/" + code);
        return dto;
    }

    @Override
    public ShareLinkDTO getByCode(String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(404, "分享链接不存在或已失效");
        }

        ShareLink shareLink = shareLinkMapper.selectOne(
                new LambdaQueryWrapper<ShareLink>().eq(ShareLink::getCode, code));
        if (shareLink == null) {
            throw new BusinessException(404, "分享链接不存在或已失效");
        }

        Photo photo = photoMapper.selectById(shareLink.getPhotoId());
        if (photo == null) {
            throw new BusinessException(404, "分享链接不存在或已失效");
        }

        ShareLinkDTO dto = toDTO(shareLink);
        dto.setShareUrl(shareBaseUrl + "/share/" + code);
        dto.setPhotoTitle(photo.getTitle());
        dto.setPhotoUrl(photo.getUrl());

        // 照片详细信息（公开分享页展示用）
        dto.setTitle(photo.getTitle());
        dto.setDescription(photo.getDescription());
        dto.setCameraModel(photo.getCameraModel());
        dto.setAperture(photo.getAperture());
        dto.setShutterSpeed(photo.getShutterSpeed());
        dto.setIso(photo.getIso());
        dto.setFocalLength(photo.getFocalLength());
        dto.setDateTaken(photo.getDateTaken());
        // URL 补全
        String url = photo.getUrl();
        dto.setPhotoUrl(url != null && url.startsWith("http") ? url : "/files/" + url);

        return dto;
    }

    @Override
    public List<ShareLinkDTO> getShareLinks() {
        List<ShareLink> shareLinks = shareLinkMapper.selectList(
                new LambdaQueryWrapper<ShareLink>().orderByDesc(ShareLink::getCreatedAt));

        return shareLinks.stream().map(sl -> {
            ShareLinkDTO dto = toDTO(sl);
            dto.setShareUrl(shareBaseUrl + "/share/" + sl.getCode());

            // 联查照片获取标题
            Photo photo = photoMapper.selectById(sl.getPhotoId());
            if (photo != null) {
                dto.setPhotoTitle(photo.getTitle());
                dto.setPhotoUrl(photo.getUrl());
            }
            return dto;
        }).collect(Collectors.toList());
    }

    @Override
    public void deleteShareLink(Long id) {
        ShareLink shareLink = shareLinkMapper.selectById(id);
        if (shareLink == null) {
            throw new BusinessException(404, "分享链接不存在");
        }
        shareLinkMapper.deleteById(id);
        log.info("分享链接已删除: id={}, code={}", id, shareLink.getCode());
    }

    @Override
    public ShareLinkDTO toDTO(ShareLink shareLink) {
        ShareLinkDTO dto = new ShareLinkDTO();
        dto.setId(shareLink.getId());
        dto.setCode(shareLink.getCode());
        dto.setPhotoId(shareLink.getPhotoId());
        dto.setCreatedAt(shareLink.getCreatedAt());
        return dto;
    }
}
