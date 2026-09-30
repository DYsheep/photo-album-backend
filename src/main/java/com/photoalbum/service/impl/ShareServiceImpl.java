package com.photoalbum.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.photoalbum.common.BusinessException;
import com.photoalbum.dto.ShareLinkDTO;
import com.photoalbum.entity.Photo;
import com.photoalbum.entity.ShareLink;
import com.photoalbum.entity.User;
import com.photoalbum.mapper.PhotoMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.photoalbum.mapper.PhotoCollectionMapper;
import com.photoalbum.mapper.PhotoCollectionPhotoMapper;
import com.photoalbum.mapper.ShareLinkMapper;
import com.photoalbum.mapper.UserMapper;
import com.photoalbum.security.AccessPolicy;
import com.photoalbum.security.CurrentUserSupport;
import com.photoalbum.security.ShareLinkPolicy;
import com.photoalbum.service.PhotoUrlResolver;
import com.photoalbum.service.ShareService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 分享链接服务实现
 *
 * 两条安全约束（与账号权限模型保持一致）：
 *   1. 分享链接不突破可见性策略：照片被设为私密后，无权访问者通过分享链接同样不可见（返回"不存在或已失效"）；
 *   2. 分享链接有时效：到期即失效，支持永久。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareServiceImpl implements ShareService {

    /** 随机码字符集：大小写字母 + 数字 */
    private static final String CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int CODE_LENGTH = 8;
    private static final int MAX_RETRY = 10;

    private static final String NOT_FOUND = "分享链接不存在或已失效";

    /** 访问口令最小长度 */
    private static final int MIN_ACCESS_CODE_LENGTH = 6;

    /** 含私密内容的分享：未指定有效期时的默认有效期（天） */
    private static final int DEFAULT_PRIVATE_SHARE_DAYS = 30;

    /** 含私密内容的分享：有效期上限（天） */
    private static final int MAX_PRIVATE_SHARE_DAYS = 90;

    private final ShareLinkMapper shareLinkMapper;
    private final PhotoCollectionMapper collectionMapper;
    private final UserMapper userMapper;
    private final PhotoCollectionPhotoMapper collectionPhotoMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final PhotoMapper photoMapper;
    private final AccessPolicy accessPolicy;
    private final PhotoUrlResolver photoUrlResolver;

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
    public ShareLinkDTO createShareLink(Long photoId, LocalDateTime expiresAt) {
        // 校验照片是否存在
        Photo photo = photoMapper.selectById(photoId);
        if (photo == null) {
            throw new BusinessException(404, "照片不存在");
        }

        LocalDateTime normalizedExpiry = normalizeExpiry(expiresAt);

        // 同一张照片只保留一条链接：已存在则按本次设置更新有效期（永久传 null）
        ShareLink existing = shareLinkMapper.selectOne(
                new LambdaQueryWrapper<ShareLink>().eq(ShareLink::getPhotoId, photoId));
        if (existing != null) {
            existing.setExpiresAt(normalizedExpiry);
            shareLinkMapper.updateById(existing);
            log.info("分享链接有效期已更新: photoId={}, code={}, expiresAt={}",
                    photoId, existing.getCode(), normalizedExpiry);
            return buildDto(existing, photo);
        }

        // 生成唯一分享码
        String code = generateUniqueCode();

        ShareLink shareLink = new ShareLink();
        shareLink.setCode(code);
        shareLink.setPhotoId(photoId);
        shareLink.setExpiresAt(normalizedExpiry);
        shareLink.setCreatedAt(LocalDateTime.now());

        shareLinkMapper.insert(shareLink);
        log.info("分享链接创建成功: photoId={}, code={}, expiresAt={}", photoId, code, normalizedExpiry);

        return buildDto(shareLink, photo);
    }

    /**
     * 归一化并校验到期时间
     *
     * 只传日期（当天 00:00）时按"当日 23:59:59"处理，符合"到期日"的直觉；
     * 传 null 表示永久有效。
     */
    private LocalDateTime normalizeExpiry(LocalDateTime expiresAt) {
        if (expiresAt == null) {
            return null;
        }
        LocalDateTime value = expiresAt;
        if (LocalTime.MIDNIGHT.equals(value.toLocalTime())) {
            value = value.toLocalDate().atTime(LocalTime.MAX.withNano(0));
        }
        if (!value.isAfter(LocalDateTime.now())) {
            throw new BusinessException(400, "分享到期时间必须晚于当前时间");
        }
        return value;
    }

    /**
     * 访问口令强度校验（修复 H02 的第二个面）
     *
     * 口令是分享唯一的访问门槛，而访问校验接口没有账号维度（只按分享码与来源地址限流），
     * 因此弱口令——尤其短纯数字——在换 IP 的分布式尝试下仍可能被穷举。
     */
    private void validateAccessCodeStrength(String accessCode) {
        if (accessCode.length() < MIN_ACCESS_CODE_LENGTH) {
            throw new BusinessException(400, "访问口令至少 " + MIN_ACCESS_CODE_LENGTH + " 位");
        }
        if (accessCode.chars().allMatch(Character::isDigit)) {
            throw new BusinessException(400, "访问口令不能为纯数字，请混合字母或符号");
        }
    }

    /**
     * 收敛含私密内容分享的有效期（修复 H03 的第二个面）
     *
     * 未指定时给安全默认值（DEFAULT_PRIVATE_SHARE_DAYS 天）而非“永久有效”——
     * 私密内容的访问窗口不应由默认值决定为无限；显式指定但超过上限时直接拒绝，
     * 而不是静默截断，使调用方明确知道边界所在。
     */
    private LocalDateTime boundPrivateShareExpiry(LocalDateTime expiry) {
        LocalDateTime now = LocalDateTime.now();
        if (expiry == null) {
            return now.plusDays(DEFAULT_PRIVATE_SHARE_DAYS);
        }
        if (expiry.isAfter(now.plusDays(MAX_PRIVATE_SHARE_DAYS))) {
            throw new BusinessException(400,
                    "含私密内容的分享有效期不得超过 " + MAX_PRIVATE_SHARE_DAYS + " 天");
        }
        return expiry;
    }

    @Override
    public ShareLinkDTO createCollectionShare(Long collectionId, LocalDateTime expiresAt,
                                             Boolean includePrivate, String accessCode) {
        com.photoalbum.entity.PhotoCollection collection = collectionMapper.selectById(collectionId);
        if (collection == null) {
            throw new BusinessException(404, "合集不存在");
        }
        // 只有能管理该合集的账号才能对外分享它（不可见即不可分享）
        if (!accessPolicy.canManageCollection(CurrentUserSupport.getCurrentUser(), collection)) {
            throw new BusinessException(404, "合集不存在");
        }

        LocalDateTime normalizedExpiry = normalizeExpiry(expiresAt);
        boolean withPrivate = Boolean.TRUE.equals(includePrivate);
        String trimmedCode = accessCode == null ? null : accessCode.trim();

        // 口令强度：只要设置了口令就要达标（口令是该分享唯一的访问门槛）
        if (trimmedCode != null && !trimmedCode.isEmpty()) {
            validateAccessCodeStrength(trimmedCode);
        }
        // 含私密必须设口令。否则拿到分享码的人（分享码随二维码与卡片转发扩散）
        // 即可经分享接口与卡片封面接口取到私密照片内容——封面接口由服务端代取对象字节，
        // 不受对象级私有 ACL 约束（修复 H03）
        if (withPrivate && (trimmedCode == null || trimmedCode.isEmpty())) {
            throw new BusinessException(400, "分享私密内容必须设置访问口令");
        }
        // 含私密时收敛有效期：不默认永久有效（修复 H03）
        if (withPrivate) {
            normalizedExpiry = boundPrivateShareExpiry(normalizedExpiry);
        }

        String encodedCode = (trimmedCode == null || trimmedCode.isEmpty())
                ? null : passwordEncoder.encode(trimmedCode);

        // 同一合集只保留一条链接：已存在则更新有效期/私密开关/口令
        ShareLink existing = shareLinkMapper.selectOne(new LambdaQueryWrapper<ShareLink>()
                .eq(ShareLink::getTargetType, "collection")
                .eq(ShareLink::getTargetId, collectionId));
        if (existing != null) {
            existing.setExpiresAt(normalizedExpiry);
            existing.setIncludePrivate(withPrivate ? 1 : 0);
            existing.setAccessCode(encodedCode);
            shareLinkMapper.updateById(existing);
            log.info("合集分享已更新: collectionId={}, code={}, includePrivate={}, expiresAt={}",
                    collectionId, existing.getCode(), withPrivate, normalizedExpiry);
            return buildCollectionDto(existing, collection);
        }

        ShareLink shareLink = new ShareLink();
        shareLink.setCode(generateUniqueCode());
        shareLink.setTargetType("collection");
        shareLink.setTargetId(collectionId);
        shareLink.setPhotoId(null);
        shareLink.setIncludePrivate(withPrivate ? 1 : 0);
        shareLink.setAccessCode(encodedCode);
        shareLink.setExpiresAt(normalizedExpiry);
        shareLink.setCreatedAt(LocalDateTime.now());
        User creator = CurrentUserSupport.getCurrentUser();
        shareLink.setCreatedBy(creator != null ? creator.getId() : null);
        shareLinkMapper.insert(shareLink);
        log.info("合集分享创建成功: collectionId={}, code={}, includePrivate={}, expiresAt={}",
                collectionId, shareLink.getCode(), withPrivate, normalizedExpiry);
        return buildCollectionDto(shareLink, collection);
    }

    /** 合集分享 DTO 组装（照片列表由解析接口按访问者/创建者可见性填充） */
    private ShareLinkDTO buildCollectionDto(ShareLink shareLink, com.photoalbum.entity.PhotoCollection collection) {
        ShareLinkDTO dto = toDTO(shareLink);
        dto.setTargetType("collection");
        dto.setCollectionId(collection.getId());
        dto.setCollectionName(collection.getName());
        dto.setCollectionDescription(collection.getDescription());
        dto.setIncludePrivate(ShareLinkPolicy.effectiveIncludePrivate(shareLink) ? 1 : 0);
        dto.setRequiresAccessCode(ShareLinkPolicy.hasAccessCode(shareLink));
        dto.setShareUrl(shareBaseUrl + "/share/" + shareLink.getCode());
        return dto;
    }

    @Override
    public ShareLinkDTO getByCode(String code) {
        return getByCode(code, null);
    }

    @Override
    public ShareLinkDTO getByCode(String code, String accessCode) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(404, NOT_FOUND);
        }

        ShareLink shareLink = shareLinkMapper.selectOne(
                new LambdaQueryWrapper<ShareLink>().eq(ShareLink::getCode, code));
        if (shareLink == null) {
            throw new BusinessException(404, NOT_FOUND);
        }

        // 时效校验：到期即失效（永久链接 expiresAt 为 null）
        if (shareLink.getExpiresAt() != null
                && !shareLink.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException(404, NOT_FOUND);
        }

        // ===== 合集分享分支 =====
        if ("collection".equals(shareLink.getTargetType())) {
            // 口令校验：设置了口令的分享，必须携带正确口令（错误/缺失一律拒绝）
            if (ShareLinkPolicy.hasAccessCode(shareLink)) {
                if (accessCode == null || !passwordEncoder.matches(accessCode, shareLink.getAccessCode())) {
                    throw new BusinessException(403, "访问口令不正确");
                }
            }
            return buildCollectionShareDto(shareLink);
        }

        Photo photo = photoMapper.selectById(shareLink.getPhotoId());
        if (photo == null) {
            throw new BusinessException(404, NOT_FOUND);
        }

        // 可见性联动：照片被设为私密后，无权限访问者通过分享链接同样看不到
        // （分享链接是"可转发的能力"，但不能突破私密标记；照片改回公开后链接自动恢复可用）
        if (!accessPolicy.canViewPhoto(CurrentUserSupport.getCurrentUser(), photo)) {
            throw new BusinessException(404, NOT_FOUND);
        }

        ShareLinkDTO dto = toDTO(shareLink);
        dto.setShareUrl(shareBaseUrl + "/share/" + code);
        dto.setPhotoTitle(photo.getTitle());
        dto.setPhotoUrl(photoUrlResolver.resolve(photo.getUrl(), photo.getIsPrivate()));

        // 照片详细信息（公开分享页展示用）
        dto.setTitle(photo.getTitle());
        dto.setDescription(photo.getDescription());
        dto.setCameraModel(photo.getCameraModel());
        dto.setAperture(photo.getAperture());
        dto.setShutterSpeed(photo.getShutterSpeed());
        dto.setIso(photo.getIso());
        dto.setFocalLength(photo.getFocalLength());
        dto.setDateTaken(photo.getDateTaken());
        // URL 补全：公开照片直链，私密照片短期预签名地址
        dto.setPhotoUrl(photoUrlResolver.resolve(photo.getUrl(), photo.getIsPrivate()));

        return dto;
    }

    @Override
    public List<ShareLinkDTO> getShareLinks() {
        List<ShareLink> shareLinks = shareLinkMapper.selectList(
                new LambdaQueryWrapper<ShareLink>().orderByDesc(ShareLink::getCreatedAt));

        return shareLinks.stream().map(sl -> {
            ShareLinkDTO dto = toDTO(sl);
            dto.setShareUrl(shareBaseUrl + "/share/" + sl.getCode());

            // 关联对象：合集分享取合集名，照片分享取照片标题
            if ("collection".equals(sl.getTargetType())) {
                com.photoalbum.entity.PhotoCollection col = collectionMapper.selectById(sl.getTargetId());
                dto.setCollectionId(sl.getTargetId());
                dto.setCollectionName(col != null ? col.getName() : null);
            } else {
                Photo photo = photoMapper.selectById(sl.getPhotoId());
                if (photo != null) {
                    dto.setPhotoTitle(photo.getTitle());
                    dto.setPhotoUrl(photoUrlResolver.resolve(photo.getUrl(), photo.getIsPrivate()));
                }
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

    /**
     * 合集分享解析：返回合集信息 + 可见照片列表
     *
     * 可见性：includePrivate=1 时取"创建该分享的账号可见的照片"（因此被其 deny 命中的照片不会出现）；
     *        =0 时仅公开照片。过期与不存在的链接统一 404（在调用处已校验）。
     */
    private ShareLinkDTO buildCollectionShareDto(ShareLink shareLink) {
        com.photoalbum.entity.PhotoCollection collection = collectionMapper.selectById(shareLink.getTargetId());
        if (collection == null) {
            throw new BusinessException(404, NOT_FOUND);
        }

        // 运行期兜底：无口令的历史链接一律不含私密（见 ShareLinkPolicy）
        boolean includePrivate = ShareLinkPolicy.effectiveIncludePrivate(shareLink);
        User creator = shareLink.getCreatedBy() != null ? userMapper.selectById(shareLink.getCreatedBy()) : null;

        List<com.photoalbum.entity.PhotoCollectionPhoto> rels = collectionPhotoMapper.selectList(
                new LambdaQueryWrapper<com.photoalbum.entity.PhotoCollectionPhoto>()
                        .eq(com.photoalbum.entity.PhotoCollectionPhoto::getCollectionId, collection.getId())
                        .orderByAsc(com.photoalbum.entity.PhotoCollectionPhoto::getSortOrder));

        java.util.List<com.photoalbum.dto.PhotoDTO> photos = new java.util.ArrayList<>();
        for (com.photoalbum.entity.PhotoCollectionPhoto rel : rels) {
            Photo photo = photoMapper.selectById(rel.getPhotoId());
            if (photo == null) {
                continue;
            }
            if (includePrivate) {
                // 以创建者视角判定：deny 命中的照片不会出现在分享里
                if (creator == null || !accessPolicy.canViewPhoto(creator, photo)) {
                    continue;
                }
            } else if (photo.getIsPrivate() != null && photo.getIsPrivate() == 1) {
                continue;
            }
            photos.add(toSharePhotoDTO(photo));
        }

        ShareLinkDTO dto = buildCollectionDto(shareLink, collection);
        dto.setPhotos(photos);
        return dto;
    }

    /** 分享页展示用的照片 DTO（地址经解析器签发：私密照片用短期预签名） */
    private com.photoalbum.dto.PhotoDTO toSharePhotoDTO(Photo photo) {
        com.photoalbum.dto.PhotoDTO dto = new com.photoalbum.dto.PhotoDTO();
        dto.setId(photo.getId());
        dto.setTitle(photo.getTitle());
        dto.setDescription(photo.getDescription());
        dto.setCategoryId(photo.getCategoryId());
        dto.setIsPrivate(photo.getIsPrivate());
        dto.setUrl(photoUrlResolver.resolve(photo.getUrl(), photo.getIsPrivate()));
        dto.setThumbnailUrl(photoUrlResolver.resolveThumbnail(photo.getThumbnailUrl(), photo.getIsPrivate()));
        return dto;
    }

    public ShareLinkDTO toDTO(ShareLink shareLink) {
        ShareLinkDTO dto = new ShareLinkDTO();
        dto.setId(shareLink.getId());
        dto.setCode(shareLink.getCode());
        dto.setPhotoId(shareLink.getPhotoId());
        dto.setTargetType(shareLink.getTargetType());
        dto.setTargetId(shareLink.getTargetId());
        dto.setIncludePrivate(ShareLinkPolicy.effectiveIncludePrivate(shareLink) ? 1 : 0);
        dto.setCreatedAt(shareLink.getCreatedAt());
        dto.setExpiresAt(shareLink.getExpiresAt());
        dto.setExpired(shareLink.getExpiresAt() != null
                && !shareLink.getExpiresAt().isAfter(LocalDateTime.now()));
        return dto;
    }

    /** 组装返回给前端的 DTO（链接创建/更新后的响应） */
    private ShareLinkDTO buildDto(ShareLink shareLink, Photo photo) {
        ShareLinkDTO dto = toDTO(shareLink);
        dto.setShareUrl(shareBaseUrl + "/share/" + shareLink.getCode());
        dto.setPhotoTitle(photo.getTitle());
        dto.setPhotoUrl(photoUrlResolver.resolve(photo.getUrl(), photo.getIsPrivate()));
        return dto;
    }
}
