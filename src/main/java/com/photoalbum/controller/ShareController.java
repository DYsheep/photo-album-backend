package com.photoalbum.controller;

import com.photoalbum.common.BusinessException;
import com.photoalbum.common.RateLimiter;
import com.photoalbum.common.Result;
import com.photoalbum.dto.ShareLinkDTO;
import com.photoalbum.entity.User;
import com.photoalbum.security.ClientIpResolver;
import com.photoalbum.security.CurrentUserSupport;
import com.photoalbum.service.PhotoService;
import com.photoalbum.service.PhotoUrlResolver;
import com.photoalbum.service.ShareCardService;
import com.photoalbum.service.ShareService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Duration;
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

    /**
     * 分享访问限流：同一「分享码 + 来源地址」5 分钟内最多 5 次，超限锁定 15 分钟
     *
     * 访问校验本质是凭据比对（与登录同类），必须限制尝试次数：服务端以 BCrypt 校验口令，
     * 单次仅数十毫秒，不限流时短口令可被高速穷举（修复 H02）。
     */
    private static final int ACCESS_MAX_ATTEMPTS = 5;

    /** 来源维度上限：防止同一来源轮询大量分享码 */
    private static final int ACCESS_IP_MAX_ATTEMPTS = 30;

    private static final long ACCESS_WINDOW_MS = 300_000L;
    private static final long ACCESS_LOCK_MS = 900_000L;

    /** 匿名图片端点（封面 / 二维码）限流：同一来源 5 分钟 300 次 */
    private static final int ASSET_MAX_ATTEMPTS = 300;
    private static final long ASSET_WINDOW_MS = 300_000L;

    private final ShareService shareService;
    private final PhotoService photoService;
    private final ShareCardService shareCardService;
    private final ClientIpResolver clientIpResolver;

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
        // 与公开入口同一可见性口径：无权访问（含私密照片）一律按不存在处理。
        // 否则仅持 photo:manage、却没有 photo:view_private 的账号，可借响应里的
        // 预签名地址读到本应对它 404 的私密照片（用一个能力位绕过另一个能力位）。
        if (photoService.getVisiblePhoto(photoId) == null) {
            return Result.fail(404, "照片不存在");
        }
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
     * 创建（或更新）合集分享链接
     *
     * includePrivate=true 时分享内含私密照片（以创建者可见集为准，扣除其黑名单命中的照片）；
     * accessCode 可选，设置后访问该链接需携带正确口令。
     *
     * 参数走请求体而不用查询参数：口令若出现在 URL 里，会落进 Nginx/WAF/APM 的访问日志、
     * 浏览器历史与任何中间代理，等于把"唯一访问门槛"公开成日志级凭证。
     */
    @PostMapping("/api/admin/share/collection/{collectionId}")
    @PreAuthorize("hasAuthority('photo:manage')")
    public Result<ShareLinkDTO> createCollectionShare(@PathVariable Long collectionId,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> params = body == null ? Map.of() : body;
        String expiresAt = params.get("expiresAt") == null ? null : String.valueOf(params.get("expiresAt"));
        java.time.LocalDateTime expiry;
        try {
            expiry = (expiresAt == null || expiresAt.isBlank())
                    ? null : java.time.LocalDateTime.parse(expiresAt.trim());
        } catch (DateTimeParseException e) {
            // 此前解析异常会冒泡成 500，这里统一收敛为参数错误
            return Result.fail(400, "到期时间格式不正确，应为 YYYY-MM-DD 或 ISO 日期时间");
        }
        Boolean includePrivate = params.get("includePrivate") instanceof Boolean flag && flag;
        String accessCode = params.get("accessCode") == null ? null : String.valueOf(params.get("accessCode"));
        return Result.ok(shareService.createCollectionShare(collectionId, expiry, includePrivate, accessCode));
    }

    /**
     * 分享卡片封面图
     * GET /api/share/{code}/cover
     *
     * 供社交平台抓取 og:image 使用：分享码本身即凭证，因此无需登录。
     * **必须直接返回图片字节（200）**：微信等平台抓取缩略图时不跟随 302 跳转，
     * 给跳转地址会导致分享卡片没有图。图片由服务端从对象存储代取，地址始终是同域、无需签名的稳定地址。
     * 取不到封面时（链接失效、需口令、无可见照片）退回站点默认图，不暴露任何信息。
     */
    @GetMapping("/api/share/{code}/cover")
    public ResponseEntity<byte[]> shareCover(@PathVariable String code,
            jakarta.servlet.http.HttpServletRequest request) {
        if (!allowShareAsset(request)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        PhotoUrlResolver.ObjectData image = shareCardService.coverImageOf(code);
        if (image != null && image.bytes() != null && image.bytes().length > 0) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(image.contentType()))
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                    .body(image.bytes());
        }
        // 兜底：跳站内默认图（同为静态文件，正常抓取路径下不会走到这里）
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(shareCardService.defaultImageUrl()))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .build();
    }

    /**
     * 公开访问分享链接
     * GET /api/share/{code}
     *
     * 访问口令通过请求头 X-Share-Access-Code 传递，**不再接受查询参数**：
     * 查询串里的口令会进入各级访问日志与浏览器历史，而口令是这类分享唯一的访问门槛。
     */
    @GetMapping("/api/share/{code}")
    public Result<ShareLinkDTO> getShareLink(@PathVariable String code,
            @RequestHeader(value = "X-Share-Access-Code", required = false) String accessCode,
            jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response) {
        // 响应体里含照片地址（私密照片是短期签名地址），禁止中间层与浏览器缓存
        response.setHeader("Cache-Control", "no-store");

        try {
            ShareLinkDTO dto = shareService.getByCode(code, accessCode);
            // 访问成功即清掉该来源的失败计数
            clearShareAccess(code, request);
            return Result.ok(dto);
        } catch (BusinessException e) {
            // 只有"确实携带了口令且校验失败"才计入尝试次数：
            // 分享页首屏会用空口令探测一次（判断是否需要口令面板），此前这次也计数，
            // 正常访客刷新几次就会把自己锁 15 分钟；而不带口令的探测也不构成穷举行为。
            boolean realAttempt = accessCode != null && !accessCode.isBlank();
            if (e.getCode() == 403 && realAttempt && !allowShareAccess(code, request)) {
                return Result.fail(429, "尝试次数过多，请15分钟后再试");
            }
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    /**
     * 分享访问是否放行（双维度：分享码+来源、以及来源单独一档）
     *
     * 两个计数都无条件累加，与登录限流同一写法；否则其中一个已锁定时另一个就不再计数。
     * 键中带来源地址而不是只带分享码：只按分享码限流会让任何人用一个来源就锁死他人链接的访问，
     * 相当于把自己的防护变成对他人的拒绝服务。
     */
    private boolean allowShareAccess(String code, jakarta.servlet.http.HttpServletRequest request) {
        String ip = clientIpResolver.resolve(request);
        String normalizedCode = code == null ? "" : code.trim();
        boolean byCode = RateLimiter.tryAcquire(
                "share-access:code:" + normalizedCode + ":" + ip,
                ACCESS_MAX_ATTEMPTS, ACCESS_WINDOW_MS, ACCESS_LOCK_MS);
        boolean byIp = RateLimiter.tryAcquire(
                "share-access:ip:" + ip,
                ACCESS_IP_MAX_ATTEMPTS, ACCESS_WINDOW_MS, ACCESS_LOCK_MS);
        return byCode && byIp;
    }

    /** 访问成功后重置"该来源对该分享码"的失败计数（来源级预算仍按窗口自然衰减） */
    private void clearShareAccess(String code, jakarta.servlet.http.HttpServletRequest request) {
        String ip = clientIpResolver.resolve(request);
        String normalizedCode = code == null ? "" : code.trim();
        RateLimiter.clear("share-access:code:" + normalizedCode + ":" + ip);
    }

    /**
     * 匿名图片端点限流（封面 / 二维码）
     *
     * 这两个端点无需登录，且每次请求要么现场向对象存储取字节、要么现场渲染 PNG，
     * 不限流就是匿名可用的资源放大点。
     */
    private boolean allowShareAsset(jakarta.servlet.http.HttpServletRequest request) {
        String ip = clientIpResolver.resolve(request);
        return RateLimiter.tryAcquire("share-asset:ip:" + ip,
                ASSET_MAX_ATTEMPTS, ASSET_WINDOW_MS, ACCESS_LOCK_MS);
    }

    /**
     * 分享页二维码
     * GET /api/share/{code}/qrcode
     *
     * 印在前端生成的分享卡片图上（扫码打开分享页），同样必须 200 直出图片。
     */
    @GetMapping("/api/share/{code}/qrcode")
    public ResponseEntity<byte[]> shareQrCode(@PathVariable String code,
            jakarta.servlet.http.HttpServletRequest request) {
        if (!allowShareAsset(request)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        byte[] png = shareCardService.qrcodeOf(code);
        if (png == null || png.length == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .body(png);
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
