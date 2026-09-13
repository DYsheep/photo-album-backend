package com.photoalbum.controller;

import com.photoalbum.common.Result;
import com.photoalbum.dto.CollectionDTO;
import com.photoalbum.dto.PhotoDTO;
import com.photoalbum.service.CollectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 合集管理控制器
 *
 * 读取接口公开（可见性由 AccessPolicy 统一过滤）；后台写接口统一要求管理权限（photo:manage）。
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
     * 创建合集（需管理权限）
     * POST /api/admin/collections
     */
    @PostMapping("/api/admin/collections")
    @PreAuthorize("hasAuthority('photo:manage')")
    public Result<CollectionDTO> create(@RequestBody CollectionDTO dto) {
        CollectionDTO result = collectionService.createCollection(dto);
        return Result.ok(result);
    }

    /**
     * 更新合集（需管理权限）
     * PUT /api/admin/collections/{id}
     */
    @PutMapping("/api/admin/collections/{id}")
    @PreAuthorize("hasAuthority('photo:manage') or @collectionAccess.canManageCollection(#id, authentication)")
    public Result<CollectionDTO> update(@PathVariable Long id, @RequestBody CollectionDTO dto) {
        CollectionDTO result = collectionService.updateCollection(id, dto);
        return Result.ok(result);
    }

    /**
     * 删除合集（需管理权限，或为该合集协作者）
     * DELETE /api/admin/collections/{id}
     */
    @DeleteMapping("/api/admin/collections/{id}")
    @PreAuthorize("hasAuthority('photo:manage') or @collectionAccess.canManageCollection(#id, authentication)")
    public Result<Void> delete(@PathVariable Long id) {
        collectionService.deleteCollection(id);
        return Result.ok();
    }

    /**
     * 向合集添加照片（需管理权限，或为该合集协作者）
     * POST /api/admin/collections/{id}/photos/{photoId}
     */
    @PostMapping("/api/admin/collections/{id}/photos/{photoId}")
    @PreAuthorize("hasAuthority('photo:manage') or @collectionAccess.canManageCollection(#collectionId, authentication)")
    public Result<Void> addPhoto(@PathVariable("id") Long collectionId,
                                  @PathVariable Long photoId) {
        collectionService.addPhoto(collectionId, photoId);
        return Result.ok();
    }

    /**
     * 从合集移除照片（需管理权限，或为该合集协作者）
     * DELETE /api/admin/collections/{id}/photos/{photoId}
     */
    @DeleteMapping("/api/admin/collections/{id}/photos/{photoId}")
    @PreAuthorize("hasAuthority('photo:manage') or @collectionAccess.canManageCollection(#collectionId, authentication)")
    public Result<Void> removePhoto(@PathVariable("id") Long collectionId,
                                     @PathVariable Long photoId) {
        collectionService.removePhoto(collectionId, photoId);
        return Result.ok();
    }

    // ========== 协作者管理（对象级管理权，仅管理权限账号可指派） ==========

    /**
     * 查看合集协作者（协作者本人也可查看）
     * GET /api/admin/collections/{id}/members
     */
    @GetMapping("/api/admin/collections/{id}/members")
    @PreAuthorize("hasAuthority('photo:manage') or @collectionAccess.canManageCollection(#id, authentication)")
    public Result<List<Map<String, Object>>> listMembers(@PathVariable Long id) {
        return Result.ok(collectionService.listMembers(id));
    }

    /**
     * 指派协作者
     * POST /api/admin/collections/{id}/members/{userId}
     */
    @PostMapping("/api/admin/collections/{id}/members/{userId}")
    @PreAuthorize("hasAuthority('photo:manage')")
    public Result<Void> addMember(@PathVariable Long id, @PathVariable Long userId) {
        try {
            collectionService.addMember(id, userId);
            return Result.ok();
        } catch (com.photoalbum.common.BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    /**
     * 移除协作者
     * DELETE /api/admin/collections/{id}/members/{userId}
     */
    @DeleteMapping("/api/admin/collections/{id}/members/{userId}")
    @PreAuthorize("hasAuthority('photo:manage')")
    public Result<Void> removeMember(@PathVariable Long id, @PathVariable Long userId) {
        collectionService.removeMember(id, userId);
        return Result.ok();
    }

    /**
     * 批量重排序合集（需管理权限）
     * PUT /api/admin/collections/reorder
     * Body: [{ id: 1, sortOrder: 0 }, { id: 3, sortOrder: 1 }, ...]
     */
    @PutMapping("/api/admin/collections/reorder")
    @PreAuthorize("hasAuthority('photo:manage')")
    public Result<Void> reorder(@RequestBody List<Map<String, Object>> orderList) {
        collectionService.reorderCollections(orderList);
        return Result.ok();
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
}
