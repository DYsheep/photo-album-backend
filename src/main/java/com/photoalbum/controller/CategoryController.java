package com.photoalbum.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.photoalbum.common.BusinessException;
import com.photoalbum.common.Result;
import com.photoalbum.dto.CategoryDTO;
import com.photoalbum.entity.Category;
import com.photoalbum.entity.Photo;
import com.photoalbum.mapper.PhotoMapper;
import com.photoalbum.service.CategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分类管理控制器
 */
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;
    private final PhotoMapper photoMapper;

    /**
     * 获取全部分类列表
     */
    @GetMapping
    public Result<List<Category>> list() {
        List<Category> list = categoryService.list(
                new LambdaQueryWrapper<Category>().orderByAsc(Category::getSortOrder)
        );
        return Result.ok(list);
    }

    /**
     * 新建分类
     */
    @PostMapping
    public Result<Category> create(@Valid @RequestBody CategoryDTO dto) {
        // 检查名称是否重复
        long count = categoryService.count(
                new LambdaQueryWrapper<Category>().eq(Category::getName, dto.getName())
        );
        if (count > 0) {
            return Result.fail("分类名称已存在");
        }

        Category category = new Category();
        category.setName(dto.getName());
        category.setDescription(dto.getDescription() != null ? dto.getDescription() : "");
        category.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);
        category.setPhotoCount(0);
        category.setCreatedAt(LocalDateTime.now());

        categoryService.save(category);
        return Result.ok(category);
    }

    /**
     * 修改分类
     */
    @PutMapping("/{id}")
    public Result<Category> update(@PathVariable Long id, @Valid @RequestBody CategoryDTO dto) {
        Category category = categoryService.getById(id);
        if (category == null) {
            return Result.fail("分类不存在");
        }

        // 检查名称是否与其他分类冲突
        if (dto.getName() != null && !dto.getName().equals(category.getName())) {
            long count = categoryService.count(
                    new LambdaQueryWrapper<Category>()
                            .eq(Category::getName, dto.getName())
                            .ne(Category::getId, id)
            );
            if (count > 0) {
                return Result.fail("分类名称已存在");
            }
        }

        category.setName(dto.getName());
        category.setDescription(dto.getDescription() != null ? dto.getDescription() : category.getDescription());
        if (dto.getSortOrder() != null) {
            category.setSortOrder(dto.getSortOrder());
        }

        category.setUpdatedAt(LocalDateTime.now());
        categoryService.updateById(category);
        return Result.ok(category);
    }

    /**
     * 删除分类（有照片关联时保护）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Category category = categoryService.getById(id);
        if (category == null) {
            return Result.fail("分类不存在");
        }

        // 检查是否有关联照片
        long photoCount = photoMapper.selectCount(
                new LambdaQueryWrapper<Photo>().eq(Photo::getCategoryId, id)
        );
        if (photoCount > 0) {
            throw new BusinessException("该分类下还有 " + photoCount + " 张照片，无法删除。请先移走或删除相关照片。");
        }

        categoryService.removeById(id);
        return Result.ok();
    }
}
