package com.photoalbum.service;

import com.photoalbum.dto.ShareLinkDTO;
import com.photoalbum.entity.ShareLink;

import java.util.List;

/**
 * 分享链接服务接口
 */
public interface ShareService {

    /**
     * 为照片创建分享链接
     *
     * @param photoId 照片 ID
     * @return 包含完整 shareUrl 的 DTO
     */
    ShareLinkDTO createShareLink(Long photoId);

    /**
     * 根据分享码获取分享链接数据（含关联照片信息）
     *
     * @param code 8 位分享码
     * @return 分享链接 DTO（含照片详细信息），不存在返回 null
     */
    ShareLinkDTO getByCode(String code);

    /**
     * 管理员查看所有分享链接列表
     *
     * @return 分享链接 DTO 列表
     */
    List<ShareLinkDTO> getShareLinks();

    /**
     * 根据 ID 删除分享链接
     *
     * @param id 分享链接 ID
     */
    void deleteShareLink(Long id);

    /**
     * Entity → DTO 转换（不含关联照片信息）
     *
     * @param shareLink 实体
     * @return DTO
     */
    ShareLinkDTO toDTO(ShareLink shareLink);
}
