package com.photoalbum.controller;

import com.photoalbum.common.BusinessException;
import com.photoalbum.common.Result;
import com.photoalbum.service.TagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 标签管理控制器（需管理权限：photo:manage）
 *
 * 标签的权威数据在 t_tag / t_photo_tag（关联表），本控制器只做参数整理与调用，
 * 重命名/合并/删除不再对全表做字符串替换。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/tags")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('photo:manage')")
public class TagController {

    private final TagService tagService;

    /**
     * 标签列表及引用数（关联表聚合）
     * GET /api/admin/tags
     */
    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(tagService.listWithCounts());
    }

    /**
     * 重命名标签
     * PUT /api/admin/tags/rename  Body: { oldName, newName }
     */
    @PutMapping("/rename")
    public Result<Void> rename(@RequestBody Map<String, String> body) {
        try {
            tagService.rename(body.get("oldName"), body.get("newName"));
            return Result.ok();
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    /**
     * 删除标签
     * DELETE /api/admin/tags/{name}
     */
    @DeleteMapping("/{name}")
    public Result<Void> delete(@PathVariable String name) {
        try {
            tagService.deleteTag(name);
            return Result.ok();
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    /**
     * 合并标签
     * POST /api/admin/tags/merge  Body: { sourceNames: [], targetName }
     */
    @PostMapping("/merge")
    @SuppressWarnings("unchecked")
    public Result<Void> merge(@RequestBody Map<String, Object> body) {
        Object sources = body.get("sourceNames");
        if (!(sources instanceof List<?> list) || list.isEmpty()) {
            return Result.fail("请选择要合并的标签");
        }
        List<String> sourceNames = ((List<Object>) list).stream()
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.toList());
        try {
            tagService.merge(sourceNames, (String) body.get("targetName"));
            return Result.ok();
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    /**
     * 以展示字段为准重建标签索引（一次性维护动作，用于修复历史数据漂移）
     * POST /api/admin/tags/rebuild
     */
    @PostMapping("/rebuild")
    public Result<Map<String, Object>> rebuild() {
        int count = tagService.rebuildIndex();
        return Result.ok("已重建 " + count + " 张照片的标签索引", Map.of("rebuilt", count));
    }
}
