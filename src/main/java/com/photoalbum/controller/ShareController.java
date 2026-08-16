package com.photoalbum.controller;

import com.photoalbum.common.Result;
import com.photoalbum.dto.ShareLinkDTO;
import com.photoalbum.service.ShareService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 分享链接控制器
 */
@RestController
@RequiredArgsConstructor
public class ShareController {

    private final ShareService shareService;

    /**
     * 公开创建分享链接（无需登录）
     * POST /api/share/photo/{photoId}
     */
    @PostMapping("/api/share/photo/{photoId}")
    public Result<ShareLinkDTO> createPublicShareLink(@PathVariable Long photoId) {
        try {
            ShareLinkDTO dto = shareService.createShareLink(photoId);
            return Result.ok("分享链接创建成功", dto);
        } catch (com.photoalbum.common.BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    /**
     * 为照片创建分享链接（管理员）
     * POST /api/admin/share/photo/{photoId}
     */
    @PostMapping("/api/admin/share/photo/{photoId}")
    public Result<ShareLinkDTO> createShareLink(@PathVariable Long photoId) {
        try {
            ShareLinkDTO dto = shareService.createShareLink(photoId);
            return Result.ok("分享链接创建成功", dto);
        } catch (com.photoalbum.common.BusinessException e) {
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
        } catch (com.photoalbum.common.BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    /**
     * 管理员查看所有分享链接
     * GET /api/admin/share
     */
    @GetMapping("/api/admin/share")
    public Result<List<ShareLinkDTO>> getShareLinks() {
        List<ShareLinkDTO> list = shareService.getShareLinks();
        return Result.ok(list);
    }

    /**
     * 管理员删除分享链接
     * DELETE /api/admin/share/{id}
     */
    @DeleteMapping("/api/admin/share/{id}")
    public Result<Void> deleteShareLink(@PathVariable Long id) {
        try {
            shareService.deleteShareLink(id);
            return Result.ok();
        } catch (com.photoalbum.common.BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }
}
