package com.photoalbum.controller;

import com.photoalbum.common.Result;
import com.photoalbum.entity.Photo;
import com.photoalbum.mapper.PhotoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 标签管理控制器（需要认证）
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/tags")
@RequiredArgsConstructor
public class TagController {

    private final PhotoMapper photoMapper;

    /**
     * 获取所有标签及引用数
     * GET /api/admin/tags
     */
    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        List<Map<String, Object>> tagList = buildTagList();
        return Result.ok(tagList);
    }

    /**
     * 重命名标签
     * PUT /api/admin/tags/rename  Body: {oldName, newName}
     */
    @PutMapping("/rename")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> rename(@RequestBody Map<String, String> body) {
        String oldName = body.get("oldName");
        String newName = body.get("newName");

        if (oldName == null || oldName.isBlank() || newName == null || newName.isBlank()) {
            return Result.fail("标签名不能为空");
        }

        if (oldName.equals(newName)) {
            return Result.fail("新旧标签名相同");
        }

        List<Photo> allPhotos = photoMapper.selectList(null);
        int updatedCount = 0;

        for (Photo photo : allPhotos) {
            String tags = photo.getTags();
            if (tags == null || tags.isBlank()) continue;

            String[] tagArray = tags.split(",");
            boolean modified = false;
            for (int i = 0; i < tagArray.length; i++) {
                if (tagArray[i].trim().equals(oldName)) {
                    tagArray[i] = newName;
                    modified = true;
                }
            }

            if (modified) {
                photo.setTags(String.join(",", tagArray));
                photoMapper.updateById(photo);
                updatedCount++;
            }
        }

        log.info("标签重命名: {} -> {}, 影响 {} 张照片", oldName, newName, updatedCount);
        return Result.ok();
    }

    /**
     * 删除标签
     * DELETE /api/admin/tags/{name}
     */
    @DeleteMapping("/{name}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> delete(@PathVariable String name) {
        if (name == null || name.isBlank()) {
            return Result.fail("标签名不能为空");
        }

        List<Photo> allPhotos = photoMapper.selectList(null);
        int updatedCount = 0;

        for (Photo photo : allPhotos) {
            String tags = photo.getTags();
            if (tags == null || tags.isBlank()) continue;

            List<String> tagList = new ArrayList<>();
            for (String t : tags.split(",")) {
                String trimmed = t.trim();
                if (!trimmed.isEmpty() && !trimmed.equals(name)) {
                    tagList.add(trimmed);
                }
            }

            String newTags = String.join(",", tagList);
            if (!newTags.equals(tags)) {
                photo.setTags(newTags);
                photoMapper.updateById(photo);
                updatedCount++;
            }
        }

        log.info("标签删除: {}, 影响 {} 张照片", name, updatedCount);
        return Result.ok();
    }

    /**
     * 合并标签
     * POST /api/admin/tags/merge  Body: {sourceNames:[...], targetName}
     */
    @PostMapping("/merge")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> merge(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> sourceNames = (List<String>) body.get("sourceNames");
        String targetName = (String) body.get("targetName");

        if (sourceNames == null || sourceNames.isEmpty()) {
            return Result.fail("源标签不能为空");
        }
        if (targetName == null || targetName.isBlank()) {
            return Result.fail("目标标签名不能为空");
        }

        Set<String> sourceSet = new HashSet<>(sourceNames);
        sourceSet.remove(targetName); // 防止目标标签在源列表中

        List<Photo> allPhotos = photoMapper.selectList(null);
        int updatedCount = 0;

        for (Photo photo : allPhotos) {
            String tags = photo.getTags();
            if (tags == null || tags.isBlank()) continue;

            Set<String> tagSet = new LinkedHashSet<>();
            for (String t : tags.split(",")) {
                String trimmed = t.trim();
                if (trimmed.isEmpty()) continue;
                if (sourceSet.contains(trimmed)) {
                    // 源标签替换为目标标签
                    tagSet.add(targetName);
                } else {
                    tagSet.add(trimmed);
                }
            }

            String newTags = String.join(",", tagSet);
            if (!newTags.equals(tags)) {
                photo.setTags(newTags);
                photoMapper.updateById(photo);
                updatedCount++;
            }
        }

        log.info("标签合并: {} -> {}, 影响 {} 张照片", sourceNames, targetName, updatedCount);
        return Result.ok();
    }

    // ========== 私有工具方法 ==========

    /**
     * 构建标签列表（含引用计数），按 count 降序
     */
    private List<Map<String, Object>> buildTagList() {
        List<Photo> allPhotos = photoMapper.selectList(null);
        Map<String, Long> tagCountMap = new HashMap<>();

        for (Photo photo : allPhotos) {
            String tagsStr = photo.getTags();
            if (tagsStr == null || tagsStr.isBlank()) continue;
            for (String tag : tagsStr.split(",")) {
                String trimmed = tag.trim();
                if (!trimmed.isEmpty()) {
                    tagCountMap.merge(trimmed, 1L, Long::sum);
                }
            }
        }

        return tagCountMap.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("name", entry.getKey());
                    item.put("count", entry.getValue());
                    return item;
                })
                .sorted((a, b) -> Long.compare(
                        (Long) b.get("count"), (Long) a.get("count")))
                .collect(Collectors.toList());
    }
}
