package com.photoalbum.controller;

import com.photoalbum.common.BusinessException;
import com.photoalbum.common.RateLimiter;
import com.photoalbum.common.Result;
import com.photoalbum.dto.ShareLinkDTO;
import com.photoalbum.entity.User;
import com.photoalbum.security.CurrentUserSupport;
import com.photoalbum.service.PhotoService;
import com.photoalbum.service.ShareService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 分享链接控制器
 *
 * 权限约定：分享链接的"访问"是公开的（凭随机分享码），但"创建"必须登录且具备上传或管理权限，
 * 且只能为自己可见的照片创建（可见性由 AccessPolicy 统一判定）。
 */
@RestController
@RequiredArgsConstructor
public class ShareController {

    /** 分享创建限流：单账号每小时 120 次，超限锁定 10 分钟 */
    private static final int SHARE_MAX_ATTEMPTS = 120;
    private static final long SHARE_WINDOW_MS = 3_600_000L;
    private static final long SHARE_LOCK_MS = 600_000L;

    private final ShareService shareService;
    private final PhotoService photoService;

    private String currentUserKey() {
        User current = CurrentUserSupport.getCurrentUser();
        return current != null ? String.valueOf(current.getId()) : "anonymous";
    }

    /**
     * 为照片创建分享链接（需登录，且具备上传或管理权限）
     *
     * 请求体可选，支持两种有效期写法（都不传 = 永久有效）：
     *   { "expiresAt": "2026-10-01" }        到期日（按当日 23:59:59 失效）
     *   { "expiresAt": "2026-10-01T12:00:00" } 精确到期时间
     *   { "expiresInDays": 7 }               N 天后到期
     *
     * 此前该接口无需登录即可为任意照片（含私密照片）创建分享链接，
     * 攻击者可借此绕过"私密"标记读取照片内容，并在库中堆积大量分享记录。
     */
    @PostMapping("/api/share/photo/{photoId}")
    @PreAuthorize("hasAnyAuthority('photo:upload','photo:manage')")
    public Result<ShareLinkDTO> createPublicShareLink(@PathVariable Long photoId,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        // 不可见（含无权访问的私密照片）一律按不存在处理
        if (photoService.getVisiblePhoto(photoId) == null) {
            return Result.fail(404, "照片不存在");
        }
        // 限流：单账号每小时 120 次（含调整有效期的重复调用）
        if (!RateLimiter.tryAcquire("share-create:" + currentUserKey(),
                SHARE_MAX_ATTEMPTS, SHARE_WINDOW_MS, SHARE_LOCK_MS)) {
            return Result.fail(429, "操作过于频繁，请稍后再试");
        }
        try {
            ShareLinkDTO dto = shareService.createShareLink(photoId, parseExpiry(body));
            return Result.ok("分享链接创建成功", dto);
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    /**
     * 为照片创建分享链接（管理后台，需管理权限）
     * POST /api/admin/share/photo/{photoId}
     */
    @PostMapping("/api/admin/share/photo/{photoId}")
    @PreAuthorize("hasAuthority('photo:manage')")
    public Result<ShareLinkDTO> createShareLink(@PathVariable Long photoId,
                                                @RequestBody(required = false) Map<String, Object> body) {
        if (!RateLimiter.tryAcquire("share-create:" + currentUserKey(),
                SHARE_MAX_ATTEMPTS, SHARE_WINDOW_MS, SHARE_LOCK_MS)) {
            return Result.fail(429, "操作过于频繁，请稍后再试");
        }
        try {
            ShareLinkDTO dto = shareService.createShareLink(photoId, parseExpiry(body));
            return Result.ok("分享链接创建成功", dto);
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    /**
     * 解析分享有效期
     *
     * 优先级：expiresAt > expiresInDays；均未提供表示永久有效。
     * 日期形式的 expiresAt（YYYY-MM-DD）在服务层按当日 23:59:59 处理。
     */
    private LocalDateTime parseExpiry(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        Object expiresAt = body.get("expiresAt");
        if (expiresAt instanceof String text && !text.isBlank()) {
            String value = text.trim();
            try {
                if (value.length() == 10) {
                    return LocalDate.parse(value).atStartOfDay();
                }
                return LocalDateTime.parse(value.replace(" ", "T"));
            } catch (DateTimeParseException e) {
                throw new BusinessException(400, "到期时间格式不正确，应为 YYYY-MM-DD 或 ISO 日期时间");
            }
        }
        Object days = body.get("expiresInDays");
        if (days instanceof Number number) {
            int value = number.intValue();
            if (value <= 0) {
                throw new BusinessException(400, "有效天数必须大于 0");
            }
            return LocalDate.now().plusDays(value).atStartOfDay();
        }
        return null;
    }

    /**
     * 公开访问分享链接
     * GET /api/share/{code}
     */
    /**
     * 创建（或更新）合集分享链接
     *
     * includePrivate=true 时分享内含私密照片（以创建者可见集为准，扣除其黑名单命中的照片）；
     * accessCode 可选，设置后访问该链接需携带正确口令。
     */
    @PostMapping("/api/admin/share/collection/{collectionId}")
    @PreAuthorize("hasAuthority('photo:manage')")
    public Result<ShareLinkDTO> createCollectionShare(@PathVariable Long collectionId,
            @RequestParam(required = false) String expiresAt,
            @RequestParam(required = false, defaultValue = "false") Boolean includePrivate,
            @RequestParam(required = false) String accessCode) {
        java.time.LocalDateTime expiry = (expiresAt == null || expiresAt.isBlank())
                ? null : java.time.LocalDateTime.parse(expiresAt.trim());
        return Result.ok(shareService.createCollectionShare(collectionId, expiry, includePrivate, accessCode));
    }

    @GetMapping("/api/share/{code}")
    public Result<ShareLinkDTO> getShareLink(@PathVariable String code,
            @RequestParam(required = false) String accessCode) {
        try {
            ShareLinkDTO dto = shareService.getByCode(code, accessCode);
            return Result.ok(dto);
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    /**
     * 管理员查看所有分享链接（需管理权限）
     * GET /api/admin/share
     */
    @GetMapping("/api/admin/share")
    @PreAuthorize("hasAuthority('photo:manage')")
    public Result<List<ShareLinkDTO>> getShareLinks() {
        List<ShareLinkDTO> list = shareService.getShareLinks();
        return Result.ok(list);
    }

    /**
     * 管理员删除分享链接（需管理权限）
     * DELETE /api/admin/share/{id}
     */
    @DeleteMapping("/api/admin/share/{id}")
    @PreAuthorize("hasAuthority('photo:manage')")
    public Result<Void> deleteShareLink(@PathVariable Long id) {
        try {
            shareService.deleteShareLink(id);
            return Result.ok();
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }
}
