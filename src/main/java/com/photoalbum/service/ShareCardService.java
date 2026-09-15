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
import com.photoalbum.security.CurrentUserSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 分享卡片服务：为"分享链接被转发到社交平台"生成卡片元数据
 *
 * 三条安全约束（与分享链接本身保持一致）：
 *   1. 不可见即不出现：卡片标题 / 封面只取"这个分享里本来就看得到"的内容，
 *      被 deny 命中的照片、私密照片不会成为封面；
 *   2. 需口令的分享不泄露任何内容：标题与封面一律用通用信息（否则拿到链接即可读到合集名）；
 *   3. 失效链接返回通用卡片，不区分"不存在"与"已过期"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareCardService {

    /** 站点名 */
    public static final String SITE_NAME = "摄影相册";
    /** 无有效内容时的默认描述 */
    public static final String SITE_DESCRIPTION = "光影记录 · 摄影作品展示与分享";

    /** 兜底封面：站点图标（随前端一起发布，始终可匿名访问） */
    private static final String DEFAULT_ICON_PATH = "/icons/icon-512.png";

    private static final String TITLE_EXPIRED = "分享链接已失效";
    private static final String DESC_EXPIRED = "该分享不存在或已过期，请向分享者索取新的链接";
    private static final String TITLE_PROTECTED = "受保护的分享";
    private static final String DESC_PROTECTED = "该分享需要访问口令才能查看";
    private static final String TITLE_PHOTO_FALLBACK = "分享的照片";

    private final ShareLinkMapper shareLinkMapper;
    private final PhotoMapper photoMapper;
    private final PhotoCollectionMapper collectionMapper;
    private final PhotoCollectionPhotoMapper collectionPhotoMapper;
    private final UserMapper userMapper;
    private final AccessPolicy accessPolicy;
    private final PhotoUrlResolver photoUrlResolver;

    @Value("${share.base-url:http://localhost:5173}")
    private String shareBaseUrl;

    // ============================================================
    // 对外能力
    // ============================================================

    /** 分享页卡片元数据（任何异常情况都返回通用卡片，保证页面始终可渲染） */
    public ShareCard cardOf(String code) {
        ShareLink link = findLiveLink(code);
        if (link == null) {
            return new ShareCard(TITLE_EXPIRED, DESC_EXPIRED, defaultImage(), pageUrl(code));
        }
        // 需口令：不泄露标题与封面（拿到链接的人未必知道口令）
        if (hasAccessCode(link)) {
            return new ShareCard(TITLE_PROTECTED, DESC_PROTECTED, defaultImage(), pageUrl(code));
        }

        if ("collection".equals(link.getTargetType())) {
            PhotoCollection collection = collectionMapper.selectById(link.getTargetId());
            if (collection == null) {
                return new ShareCard(TITLE_EXPIRED, DESC_EXPIRED, defaultImage(), pageUrl(code));
            }
            Scan scan = scanCollection(link, collection);
            String description = blankToNull(collection.getDescription());
            if (description == null) {
                description = scan.count() > 0 ? "共 " + scan.count() + " 张照片" : SITE_DESCRIPTION;
            }
            return new ShareCard(orDefault(collection.getName(), SITE_NAME),
                    description, coverUrl(code), pageUrl(code));
        }

        Photo photo = photoMapper.selectById(link.getPhotoId());
        if (photo == null || !visibleToGuest(photo)) {
            return new ShareCard(TITLE_EXPIRED, DESC_EXPIRED, defaultImage(), pageUrl(code));
        }
        return new ShareCard(orDefault(photo.getTitle(), TITLE_PHOTO_FALLBACK),
                photoDescription(photo), coverUrl(code), pageUrl(code));
    }

    /**
     * 卡片封面照在对象存储中的原始地址（取不到返回 null）
     *
     * 返回"存储地址"而不是签名地址：封面由本服务代取字节后直出（见 coverImageOf），
     * 页面里引用的是一个永不过期、且不跳转的同域地址。
     */
    public String coverStoredUrlOf(String code) {
        ShareLink link = findLiveLink(code);
        if (link == null || hasAccessCode(link)) {
            return null;
        }
        Photo cover = coverPhoto(link);
        return cover == null ? null : firstNonBlank(cover.getThumbnailUrl(), cover.getUrl());
    }

    /**
     * 卡片封面图片（同域直出，不做任何跳转）
     *
     * 微信等平台抓取缩略图时**不跟随 302 跳转**，因此这里直接返回图片字节；
     * 取不到封面（链接失效 / 需口令 / 无可见照片 / 对象读取失败）返回 null，由调用方退回默认图。
     */
    public PhotoUrlResolver.ObjectData coverImageOf(String code) {
        String stored = coverStoredUrlOf(code);
        return stored == null ? null : photoUrlResolver.readObject(stored);
    }

    /** 站点默认封面图地址（前端构建产物里的图标，可匿名直连、无需跳转） */
    public String defaultImageUrl() {
        return defaultImage();
    }

    // ============================================================
    // 内部实现
    // ============================================================

    /** 按分享码取"有效期内的分享"，不存在 / 已过期 / 码为空一律返回 null */
    private ShareLink findLiveLink(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        ShareLink link = shareLinkMapper.selectOne(
                new LambdaQueryWrapper<ShareLink>().eq(ShareLink::getCode, code));
        if (link == null) {
            return null;
        }
        if (link.getExpiresAt() != null && !link.getExpiresAt().isAfter(LocalDateTime.now())) {
            return null;
        }
        return link;
    }

    /** 卡片封面照：合集优先用合集封面、否则按排序取第一张可见照片；照片分享即该照片 */
    private Photo coverPhoto(ShareLink link) {
        if ("collection".equals(link.getTargetType())) {
            PhotoCollection collection = collectionMapper.selectById(link.getTargetId());
            return collection == null ? null : scanCollection(link, collection).first();
        }
        Photo photo = photoMapper.selectById(link.getPhotoId());
        if (photo == null || !visibleToGuest(photo)) {
            return null;
        }
        return photo;
    }

    /**
     * 单次遍历合集照片：取第一张"分享内可见"的照片作为封面，同时统计可见数量
     *
     * 可见口径与分享页完全一致：includePrivate=1 时以创建者视角判定（deny 命中的照片不出现），
     * 否则只保留公开照片。
     */
    private Scan scanCollection(ShareLink link, PhotoCollection collection) {
        boolean includePrivate = link.getIncludePrivate() != null && link.getIncludePrivate() == 1;
        User creator = includePrivate && link.getCreatedBy() != null
                ? userMapper.selectById(link.getCreatedBy()) : null;

        Photo first = null;
        if (collection.getCoverPhotoId() != null) {
            Photo cover = photoMapper.selectById(collection.getCoverPhotoId());
            if (cover != null && visibleInShare(cover, includePrivate, creator)) {
                first = cover;
            }
        }

        List<PhotoCollectionPhoto> rels = collectionPhotoMapper.selectList(
                new LambdaQueryWrapper<PhotoCollectionPhoto>()
                        .eq(PhotoCollectionPhoto::getCollectionId, collection.getId())
                        .orderByAsc(PhotoCollectionPhoto::getSortOrder));

        int count = 0;
        for (PhotoCollectionPhoto rel : rels) {
            Photo photo = photoMapper.selectById(rel.getPhotoId());
            if (photo == null || !visibleInShare(photo, includePrivate, creator)) {
                continue;
            }
            count++;
            if (first == null) {
                first = photo;
            }
        }
        return new Scan(first, count);
    }

    /** 照片是否出现在该分享中（与 ShareServiceImpl 的分享范围口径一致） */
    private boolean visibleInShare(Photo photo, boolean includePrivate, User creator) {
        if (includePrivate) {
            return creator != null && accessPolicy.canViewPhoto(creator, photo);
        }
        return photo.getIsPrivate() == null || photo.getIsPrivate() == 0;
    }

    /** 照片对"未登录访问者"是否可见（抓取器一律按匿名处理） */
    private boolean visibleToGuest(Photo photo) {
        return accessPolicy.canViewPhoto(CurrentUserSupport.getCurrentUser(), photo);
    }

    /** 照片卡片描述：优先用文字说明，没有则用拍摄参数补足信息量 */
    private String photoDescription(Photo photo) {
        String description = blankToNull(photo.getDescription());
        if (description != null) {
            return description;
        }
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, photo.getCameraModel());
        addIfPresent(parts, photo.getFocalLength());
        addIfPresent(parts, photo.getAperture());
        addIfPresent(parts, photo.getShutterSpeed());
        addIfPresent(parts, photo.getIso());
        return parts.isEmpty() ? SITE_DESCRIPTION : String.join(" · ", parts);
    }

    private void addIfPresent(List<String> parts, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(value.trim());
        }
    }

    /** 分享页地址 */
    private String pageUrl(String code) {
        return base() + "/share/" + (code == null ? "" : code);
    }

    /** 卡片封面接口地址（同域，稳定不过期） */
    private String coverUrl(String code) {
        return base() + "/api/share/" + code + "/cover";
    }

    private String defaultImage() {
        return base() + DEFAULT_ICON_PATH;
    }

    private String base() {
        if (shareBaseUrl == null || shareBaseUrl.isBlank()) {
            return "";
        }
        return shareBaseUrl.endsWith("/")
                ? shareBaseUrl.substring(0, shareBaseUrl.length() - 1) : shareBaseUrl;
    }

    private boolean hasAccessCode(ShareLink link) {
        return link.getAccessCode() != null && !link.getAccessCode().isBlank();
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private String orDefault(String value, String fallback) {
        String trimmed = blankToNull(value);
        return trimmed == null ? fallback : trimmed;
    }

    private String firstNonBlank(String first, String second) {
        return blankToNull(first) != null ? first : blankToNull(second);
    }

    /** 合集扫描结果：封面候选 + 可见照片数 */
    private record Scan(Photo first, int count) {
    }
}
