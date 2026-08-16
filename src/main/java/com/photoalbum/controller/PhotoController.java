package com.photoalbum.controller;

import com.photoalbum.common.Result;
import com.photoalbum.dto.PhotoDTO;
import com.photoalbum.entity.Category;
import com.photoalbum.entity.Photo;
import com.photoalbum.entity.User;
import com.photoalbum.mapper.CategoryMapper;
import com.photoalbum.service.PhotoService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 照片管理控制器
 */
@RestController
@RequestMapping("/api/photos")
@RequiredArgsConstructor
public class PhotoController {

    private final PhotoService photoService;
    private final CategoryMapper categoryMapper;

    /**
     * 分页列表（支持搜索 + 分类筛选）
     * GET /api/photos?pageNum=1&pageSize=10&keyword=xxx&categoryIdFilter=1
     */
    @GetMapping
    @SuppressWarnings("unchecked")
    public Result<?> list(PhotoDTO dto) {
        Object data = photoService.getPhotoPage(dto);
        return Result.ok((Map<String, Object>) data);
    }

    /**
     * 照片详情
     */
    @GetMapping("/{id}")
    public Result<PhotoDTO> detail(@PathVariable Long id) {
        Photo photo = photoService.getById(id);
        if (photo == null) {
            return Result.fail(404, "照片不存在");
        }

        // 浏览量 +1
        photo.setViewCount(photo.getViewCount() != null ? photo.getViewCount() + 1 : 1);
        photoService.updateById(photo);

        return Result.ok(photoService.toDTO(photo));
    }

    /**
     * 上传图片（支持多字段表单数据）
     * POST /api/photos/upload (multipart/form-data)
     */
    @PostMapping("/upload")
    public Result<PhotoDTO> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "tags", required = false) String tags,
            @RequestParam(value = "isPrivate", required = false, defaultValue = "0") Integer isPrivate,
            @RequestParam(value = "collectionId", required = false) Long collectionId) throws Exception {
        checkUploadPermission();
        PhotoDTO result = photoService.upload(file, title, categoryId, collectionId, description, tags, isPrivate);
        return Result.ok(result);
    }

    /**
     * 修改照片信息
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody PhotoDTO dto) {
        try {
            checkManagePermission();
            photoService.updatePhoto(id, dto);
            return Result.ok();
        } catch (com.photoalbum.common.BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    /**
     * 删除单张照片
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        try {
            photoService.deletePhoto(id);
            return Result.ok();
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 批量删除
     * DELETE /api/photos/batch  Body: { ids: [1,2,3] }
     */
    @DeleteMapping("/batch")
    public Result<Void> batchDelete(@RequestBody Map<String, List<Long>> body) {
        List<Long> ids = body.get("ids");
        if (ids == null || ids.isEmpty()) {
            return Result.fail("请选择要删除的照片");
        }
        if (ids.size() > 50) {
            return Result.fail("单次最多删除 50 张");
        }
        try {
            photoService.batchDelete(ids);
            return Result.ok();
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 仪表盘统计数据
     * GET /api/photos/stats
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        Map<String, Object> data = photoService.getDashboardStats();
        return Result.ok(data);
    }

    /**
     * 获取所有有 GPS 坐标的照片
     * GET /api/photos/gps
     */
    @GetMapping("/gps")
    public Result<List<PhotoDTO>> gpsPhotos() {
        List<PhotoDTO> list = photoService.getGpsPhotos();
        return Result.ok(list);
    }

    /**
     * 获取相邻照片 ID（上一张/下一张）
     * GET /api/photos/{id}/adjacent
     */
    @GetMapping("/{id}/adjacent")
    public Result<Map<String, Object>> adjacent(@PathVariable Long id) {
        Map<String, Object> result = photoService.getAdjacentIds(id);
        return Result.ok(result);
    }

    /**
     * 标签列表（前台，含引用计数）
     * GET /api/tags
     */
    @GetMapping("/tags")
    public Result<List<Map<String, Object>>> tags() {
        List<Map<String, Object>> tagList = photoService.getTagList();
        return Result.ok(tagList);
    }

    /**
     * 批量编辑照片
     * PUT /api/photos/batch  Body: {ids:[1,2,3], categoryId:5, appendTags:"新标签"}
     */
    @PutMapping("/batch")
    public Result<Void> batchUpdate(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Integer> idsInt = (List<Integer>) body.get("ids");
        if (idsInt == null || idsInt.isEmpty()) {
            return Result.fail("请选择要编辑的照片");
        }
        List<Long> ids = idsInt.stream().map(Long::valueOf).collect(java.util.stream.Collectors.toList());

        Long categoryId = null;
        if (body.get("categoryId") != null) {
            Object catId = body.get("categoryId");
            categoryId = catId instanceof Integer ? Long.valueOf((Integer) catId) : (Long) catId;
        }

        String appendTags = (String) body.get("appendTags");

        try {
            photoService.batchUpdate(ids, categoryId, appendTags);
            return Result.ok();
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    // ========== 私有工具方法 ==========

    /**
     * Entity → DTO 转换（复用 Controller 层逻辑）
     */
    private PhotoDTO toDTO(Photo photo) {
        PhotoDTO dto = new PhotoDTO();
        dto.setId(photo.getId());
        dto.setTitle(photo.getTitle());
        dto.setDescription(photo.getDescription());
        dto.setCategoryId(photo.getCategoryId());

        // COS 已含完整域名，本地路径需拼接前缀
        String url = photo.getUrl();
        dto.setUrl(url != null && url.startsWith("http") ? url : "/files/" + url);
        String thumbUrl = photo.getThumbnailUrl();
        dto.setThumbnailUrl(thumbUrl != null && !thumbUrl.isEmpty()
                ? (thumbUrl.startsWith("http") ? thumbUrl : "/files/" + thumbUrl) : null);
        dto.setFileName(photo.getFileName());
        dto.setFileSize(photo.getFileSize());
        dto.setTags(photo.getTags());
        dto.setIsPrivate(photo.getIsPrivate());
        dto.setViewCount(photo.getViewCount());
        dto.setExifInfo(photo.getExifInfo());
        dto.setCameraModel(photo.getCameraModel());
        dto.setAperture(photo.getAperture());
        dto.setShutterSpeed(photo.getShutterSpeed());
        dto.setIso(photo.getIso());
        dto.setFocalLength(photo.getFocalLength());
        dto.setDateTaken(photo.getDateTaken());
        dto.setGpsLatitude(photo.getGpsLatitude());
        dto.setGpsLongitude(photo.getGpsLongitude());

        // 填充分类名称
        if (photo.getCategoryId() != null) {
            Category cat = categoryMapper.selectById(photo.getCategoryId());
            if (cat != null) {
                dto.setCategoryName(cat.getName());
            }
        }
        return dto;
    }

    /** 点赞 */
    @PostMapping("/{id}/like")
    public Result<Integer> like(@PathVariable Long id) {
        int count = photoService.likePhoto(id);
        return Result.ok(count);
    }

    private User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User) return (User) auth.getPrincipal();
        return null;
    }
    private boolean isAdmin() { User u = getCurrentUser(); return u != null && "admin".equals(u.getRole()); }
    private void checkUploadPermission() {
        User u = getCurrentUser();
        if (u == null) throw new RuntimeException("未登录");
        if (isAdmin()) return;
        if (u.getCanUpload() == null || u.getCanUpload() != 1)
            throw new RuntimeException("无上传权限");
    }
    private void checkManagePermission() {
        User u = getCurrentUser();
        if (u == null) throw new RuntimeException("未登录");
        if (isAdmin()) return;
        if (u.getCanManage() == null || u.getCanManage() != 1)
            throw new RuntimeException("无管理权限");
    }
}
