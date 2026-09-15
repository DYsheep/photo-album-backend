package com.photoalbum.service;

import com.photoalbum.dto.ShareLinkDTO;
import com.photoalbum.entity.ShareLink;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分享链接服务接口
 */
public interface ShareService {

    /**
     * 创建（或更新）合集分享链接
     *
     * @param collectionId   合集 ID（调用者需具备该合集的管理权限）
     * @param expiresAt      到期时间（null=永久）
     * @param includePrivate 是否包含私密照片（true 时以调用者可见集为准并扣除 deny）
     * @param accessCode     访问口令（可为空）
     */
    ShareLinkDTO createCollectionShare(Long collectionId, java.time.LocalDateTime expiresAt,
                                       Boolean includePrivate, String accessCode);

    /**
     * 为照片创建（或复用并更新）分享链接
     *
     * 同一张照片只保留一条链接：已存在时按本次传入的有效期更新，不新增记录。
     *
     * @param photoId   照片 ID
     * @param expiresAt 到期时间；传 null 表示永久有效
     * @return 包含完整 shareUrl 的 DTO
     */
    ShareLinkDTO createShareLink(Long photoId, LocalDateTime expiresAt);

    /**
     * 根据分享码获取分享链接数据（含关联照片信息）
     *
     * 校验顺序：分享码存在 → 未过期 → 照片存在 → 照片对当前调用者可见。
     * 任一环节不通过均按"分享链接不存在或已失效"返回，不暴露具体情况。
     *
     * @param code 8 位分享码
     * @return 分享链接 DTO（含照片详细信息）
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
