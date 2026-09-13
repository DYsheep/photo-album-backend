package com.photoalbum.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.photoalbum.common.BusinessException;
import com.photoalbum.entity.Photo;
import com.photoalbum.entity.PhotoTag;
import com.photoalbum.entity.Tag;
import com.photoalbum.mapper.PhotoMapper;
import com.photoalbum.mapper.PhotoTagMapper;
import com.photoalbum.mapper.TagMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 标签服务（标签的权威数据在 t_tag / t_photo_tag）
 *
 * 为什么需要它：
 *   原实现把标签存成 t_photo.tags 的逗号分隔字符串，导致"重命名/删除/合并"必须对全表做字符串替换，
 *   且无法做标签级授权。现在标签关系落在关联表上：
 *     · 写路径（上传/编辑/批量追加）双写：关联表 + 展示用冗余字段；
 *     · 标签管理（重命名/合并/删除）改走关联表，照片的冗余字段按需重写；
 *     · 统计（各标签引用数）走关联表聚合，不再全表扫描解析字符串；
 *     · 标签级授权（t_user_permission.target_type='tag'）以关联表为准做级联。
 *
 * 过渡说明：列表/详情等读路径仍使用 t_photo.tags 冗余字段展示（零性能回退），
 * 若出现数据漂移，可调用 rebuildIndex() 以冗余字段为准重建关联表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TagService {

    private final TagMapper tagMapper;
    private final PhotoTagMapper photoTagMapper;
    private final PhotoMapper photoMapper;

    // ========== 写路径：同步 ==========

    /**
     * 按逗号分隔字符串全量同步某张照片的标签关联（保留传入顺序、去重）
     */
    @Transactional
    public void syncRelations(Long photoId, String tagsCsv) {
        if (photoId == null) {
            return;
        }
        photoTagMapper.delete(new LambdaQueryWrapper<PhotoTag>().eq(PhotoTag::getPhotoId, photoId));
        for (String name : parseTags(tagsCsv)) {
            Long tagId = ensureTag(name);
            PhotoTag relation = new PhotoTag();
            relation.setPhotoId(photoId);
            relation.setTagId(tagId);
            try {
                photoTagMapper.insert(relation);
            } catch (Exception e) {
                // 并发或重复插入时忽略（唯一约束 uk_photo_tag 兜底）
                log.debug("标签关联已存在，跳过: photoId={}, tag={}", photoId, name);
            }
        }
    }

    /** 解析逗号分隔标签串：去空白、去空项、去重、保序 */
    public List<String> parseTags(String tagsCsv) {
        Set<String> names = new LinkedHashSet<>();
        if (tagsCsv != null && !tagsCsv.isBlank()) {
            for (String part : tagsCsv.split(",")) {
                String name = part.trim();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return new ArrayList<>(names);
    }

    /** 取标签 ID（不存在则创建） */
    @Transactional
    public Long ensureTag(String name) {
        Tag existing = tagMapper.selectOne(new LambdaQueryWrapper<Tag>().eq(Tag::getName, name));
        if (existing != null) {
            return existing.getId();
        }
        Tag tag = new Tag();
        tag.setName(name);
        tagMapper.insert(tag);
        return tag.getId();
    }

    /** 按标签 ID 取名称（用于把标签级授权翻译成可内存匹配的名称集合） */
    public Set<String> namesOf(Set<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return Set.of();
        }
        return tagMapper.selectBatchIds(tagIds).stream()
                .map(Tag::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ========== 读路径：管理端列表 ==========

    /** 各标签的引用数（关联表聚合，不再全表扫描字符串） */
    public List<Map<String, Object>> listWithCounts() {
        Map<Long, Long> counts = photoTagMapper.selectMaps(new QueryWrapper<PhotoTag>()
                        .select("tag_id", "COUNT(*) AS cnt")
                        .groupBy("tag_id"))
                .stream()
                .collect(Collectors.toMap(
                        row -> ((Number) row.get("tag_id")).longValue(),
                        row -> ((Number) row.get("cnt")).longValue()));

        return tagMapper.selectList(null).stream()
                .map(tag -> {
                    Map<String, Object> item = new java.util.HashMap<>();
                    // 同时返回 id：标签级授权需要以标签 ID 作为授权对象
                    item.put("id", tag.getId());
                    item.put("name", tag.getName());
                    item.put("count", counts.getOrDefault(tag.getId(), 0L));
                    return item;
                })
                .sorted((a, b) -> Long.compare((Long) b.get("count"), (Long) a.get("count")))
                .collect(Collectors.toList());
    }

    // ========== 标签管理：重命名 / 删除 / 合并 ==========

    @Transactional
    public void rename(String oldName, String newName) {
        String source = requireName(oldName, "标签不存在");
        String target = requireName(newName, "新标签名不能为空");
        if (source.equals(target)) {
            return;
        }
        Tag tag = requireTag(source);
        if (tagMapper.selectCount(new LambdaQueryWrapper<Tag>().eq(Tag::getName, target)) > 0) {
            throw new BusinessException(400, "目标标签已存在，请使用合并");
        }
        tag.setName(target);
        tagMapper.updateById(tag);
        // 关联关系不变（授权指向标签 ID，重命名后依然有效）；仅重写受影响照片的展示字段
        refreshColumns(photoIdsOfTag(tag.getId()));
        log.info("标签已重命名: {} -> {}", source, target);
    }

    @Transactional
    public void deleteTag(String name) {
        Tag tag = requireTag(requireName(name, "标签不存在"));
        List<Long> photoIds = photoIdsOfTag(tag.getId());
        photoTagMapper.delete(new LambdaQueryWrapper<PhotoTag>().eq(PhotoTag::getTagId, tag.getId()));
        tagMapper.deleteById(tag.getId());
        refreshColumns(photoIds);
        // 指向该标签的标签级授权随之失效（授权记录保留，可在后台清理）
        log.info("标签已删除: {}（影响 {} 张照片）", name, photoIds.size());
    }

    @Transactional
    public void merge(List<String> sourceNames, String targetName) {
        if (sourceNames == null || sourceNames.isEmpty()) {
            throw new BusinessException(400, "请选择要合并的标签");
        }
        String target = requireName(targetName, "目标标签不能为空");
        Long targetId = ensureTag(target);
        Set<Long> affectedPhotoIds = new LinkedHashSet<>();

        for (String rawName : sourceNames) {
            String sourceName = rawName == null ? "" : rawName.trim();
            if (sourceName.isEmpty() || sourceName.equals(target)) {
                continue;
            }
            Tag source = tagMapper.selectOne(new LambdaQueryWrapper<Tag>().eq(Tag::getName, sourceName));
            if (source == null) {
                continue;
            }
            List<Long> photoIds = photoIdsOfTag(source.getId());
            for (Long photoId : photoIds) {
                affectedPhotoIds.add(photoId);
                // 该照片已有目标标签：直接移除来源关联，避免唯一约束冲突
                Long hasTarget = photoTagMapper.selectCount(new LambdaQueryWrapper<PhotoTag>()
                        .eq(PhotoTag::getPhotoId, photoId)
                        .eq(PhotoTag::getTagId, targetId));
                if (hasTarget != null && hasTarget > 0) {
                    photoTagMapper.delete(new LambdaQueryWrapper<PhotoTag>()
                            .eq(PhotoTag::getPhotoId, photoId)
                            .eq(PhotoTag::getTagId, source.getId()));
                } else {
                    PhotoTag relation = photoTagMapper.selectOne(new LambdaQueryWrapper<PhotoTag>()
                            .eq(PhotoTag::getPhotoId, photoId)
                            .eq(PhotoTag::getTagId, source.getId()));
                    if (relation != null) {
                        relation.setTagId(targetId);
                        photoTagMapper.updateById(relation);
                    }
                }
            }
            photoTagMapper.delete(new LambdaQueryWrapper<PhotoTag>().eq(PhotoTag::getTagId, source.getId()));
            tagMapper.deleteById(source.getId());
        }
        refreshColumns(new ArrayList<>(affectedPhotoIds));
        log.info("标签已合并到 {}: 来源={}, 影响 {} 张照片", target, sourceNames, affectedPhotoIds.size());
    }

    // ========== 漂移修复 ==========

    /**
     * 以展示字段（t_photo.tags）为准重建标签关联（一次性维护动作）
     *
     * @return 处理的照片数量
     */
    @Transactional
    public int rebuildIndex() {
        photoTagMapper.delete(null);
        List<Photo> photos = photoMapper.selectList(null);
        for (Photo photo : photos) {
            syncRelations(photo.getId(), photo.getTags());
        }
        log.info("标签索引已重建: 共 {} 张照片", photos.size());
        return photos.size();
    }

    // ========== 内部工具 ==========

    private Tag requireTag(String name) {
        Tag tag = tagMapper.selectOne(new LambdaQueryWrapper<Tag>().eq(Tag::getName, name));
        if (tag == null) {
            throw new BusinessException(404, "标签不存在: " + name);
        }
        return tag;
    }

    private String requireName(String name, String message) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(400, message);
        }
        return name.trim();
    }

    private List<Long> photoIdsOfTag(Long tagId) {
        return photoTagMapper.selectList(new LambdaQueryWrapper<PhotoTag>().eq(PhotoTag::getTagId, tagId))
                .stream().map(PhotoTag::getPhotoId).collect(Collectors.toList());
    }

    /** 按关联表重写这些照片的展示用标签字段 */
    private void refreshColumns(List<Long> photoIds) {
        for (Long photoId : photoIds) {
            List<String> names = photoTagMapper.selectList(
                            new LambdaQueryWrapper<PhotoTag>().eq(PhotoTag::getPhotoId, photoId))
                    .stream()
                    .map(PhotoTag::getTagId)
                    .map(tagId -> {
                        Tag tag = tagMapper.selectById(tagId);
                        return tag != null ? tag.getName() : null;
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());
            Photo update = new Photo();
            update.setId(photoId);
            update.setTags(String.join(",", names));
            photoMapper.updateById(update);
        }
    }
}
