package com.photoalbum.controller;

import com.photoalbum.common.BusinessException;
import com.photoalbum.common.Result;
import com.photoalbum.dto.ShareLinkDTO;
import com.photoalbum.service.PhotoService;
import com.photoalbum.service.ShareService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 分享链接控制器
 *
 * 权限约定：分享链接的"访问"是公开的（凭随机分享码），但"创建"必须登录且具备上传或管理权限，
 * 且只能为自己可见的照片创建（可见性由 AccessPolicy 统一判定）。
 */
@RestController
@RequiredArgsConstructor
public class ShareController {

    private final ShareService shareService;
    private final PhotoService photoService;

    /**
     * 为照片创建分享链接（需登录，且具备上传或管理权限）
     *
     * 此前该接口无需登录即可为任意照片（含私密照片）创建分享链接，
     * 攻击者可借此绕过"私密"标记读取照片内容，并在库中堆积大量分享记录。
     */
    @PostMapping("/api/share/photo/{photoId}")
    @PreAuthorize("hasAnyAuthority('photo:upload','photo:manage')")
    public Result<ShareLinkDTO> createPublicShareLink(@PathVariable Long photoId) {
        // 不可见（含无权访问的私密照片）一律按不存在处理
        if (photoService.getVisiblePhoto(photoId) == null) {
            return Result.fail(404, "照片不存在");
        }
        try {
            ShareLinkDTO dto = shareService.createShareLink(photoId);
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
    public Result<ShareLinkDTO> createShareLink(@PathVariable Long photoId) {
        try {
            ShareLinkDTO dto = shareService.createShareLink(photoId);
            return Result.ok("分享链接创建成功", dto);
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    /**
     * 公开访问分享链接
     * GET /api/share/{code}
     */
    @GetMapping("/api/share/{code}")
    public Result<ShareLinkDTO> getShareLink(@PathVariable String code) {
        try {
            ShareLinkDTO dto = shareService.getByCode(code);
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
