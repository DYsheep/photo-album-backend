package com.photoalbum.controller;

import com.photoalbum.common.Result;
import com.photoalbum.dto.CollectionDTO;
import com.photoalbum.dto.PhotoDTO;
import com.photoalbum.entity.User;
import com.photoalbum.service.CollectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 合集管理控制器
 */
@RestController
@RequiredArgsConstructor
public class CollectionController {

    private final CollectionService collectionService;

    // ========== 前台接口 ==========

    /**
     * 获取已发布合集列表
     * GET /api/collections
     */
    @GetMapping("/api/collections")
    public Result<List<CollectionDTO>> listPublished() {
        List<CollectionDTO> collections = collectionService.getPublishedCollections();
        return Result.ok(collections);
    }

    /**
     * 获取合集详情（含照片列表）
     * GET /api/collections/{id}
     */
    @GetMapping("/api/collections/{id}")
    public Result<List<PhotoDTO>> detail(@PathVariable Long id) {
        List<PhotoDTO> photos = collectionService.getCollectionPhotos(id);
        return Result.ok(photos);
    }

    // ========== 后台管理接口 ==========

    /**
     * 获取全部合集列表（含未发布）
     * GET /api/admin/collections
     */
    @GetMapping("/api/admin/collections")
    public Result<List<CollectionDTO>> listAll() {
        List<CollectionDTO> collections = collectionService.getAllCollections();
        return Result.ok(collections);
    }

    /**
     * 创建合集
     * POST /api/admin/collections
     */
    @PostMapping("/api/admin/collections")
    public Result<CollectionDTO> create(@RequestBody CollectionDTO dto) {
        try {
            checkManagePermission();
            CollectionDTO result = collectionService.createCollection(dto);
            return Result.ok(result);
        } catch (com.photoalbum.common.BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    /**
     * 更新合集
     * PUT /api/admin/collections/{id}
     */
    @PutMapping("/api/admin/collections/{id}")
    public Result<CollectionDTO> update(@PathVariable Long id, @RequestBody CollectionDTO dto) {
        try {
            checkManagePermission();
            CollectionDTO result = collectionService.updateCollection(id, dto);
            return Result.ok(result);
        } catch (com.photoalbum.common.BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    /**
     * 删除合集
     * DELETE /api/admin/collections/{id}
     */
    @DeleteMapping("/api/admin/collections/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        try {
            checkManagePermission();
            collectionService.deleteCollection(id);
            return Result.ok();
        } catch (com.photoalbum.common.BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    /**
     * 向合集添加照片
     * POST /api/admin/collections/{id}/photos/{photoId}
     */
    @PostMapping("/api/admin/collections/{id}/photos/{photoId}")
    public Result<Void> addPhoto(@PathVariable("id") Long collectionId,
                                  @PathVariable Long photoId) {
        try {
            collectionService.addPhoto(collectionId, photoId);
            return Result.ok();
        } catch (com.photoalbum.common.BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    /**
     * 从合集移除照片
     * DELETE /api/admin/collections/{id}/photos/{photoId}
     */
    @DeleteMapping("/api/admin/collections/{id}/photos/{photoId}")
    public Result<Void> removePhoto(@PathVariable("id") Long collectionId,
                                     @PathVariable Long photoId) {
        try {
            collectionService.removePhoto(collectionId, photoId);
            return Result.ok();
        } catch (com.photoalbum.common.BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    /**
     * 批量重排序合集
     * PUT /api/admin/collections/reorder
     * Body: [{ id: 1, sortOrder: 0 }, { id: 3, sortOrder: 1 }, ...]
     */
    @PutMapping("/api/admin/collections/reorder")
    public Result<Void> reorder(@RequestBody List<Map<String, Object>> orderList) {
        try {
            collectionService.reorderCollections(orderList);
            return Result.ok();
        } catch (com.photoalbum.common.BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    /**
     * 合集内相邻照片 ID
     * GET /api/collections/{id}/adjacent?photoId=123
     */
    @GetMapping("/api/collections/{id}/adjacent")
    public Result<Map<String, Object>> adjacent(@PathVariable Long id,
                                                 @RequestParam Long photoId) {
        Map<String, Object> result = collectionService.getAdjacentInCollection(id, photoId);
        return Result.ok(result);
    }

    private User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User) return (User) auth.getPrincipal();
        return null;
    }
    private boolean isAdmin() { User u = getCurrentUser(); return u != null && "admin".equals(u.getRole()); }
    private void checkManagePermission() {
        User u = getCurrentUser();
        if (u == null) throw new RuntimeException("未登录");
        if (isAdmin()) return;
        if (u.getCanManage() == null || u.getCanManage() != 1)
            throw new RuntimeException("无管理权限");
    }
}
