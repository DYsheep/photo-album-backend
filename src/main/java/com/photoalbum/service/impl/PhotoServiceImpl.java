package com.photoalbum.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.photoalbum.common.BusinessException;
import com.photoalbum.common.UserRoles;
import com.photoalbum.dto.PhotoDTO;
import com.photoalbum.entity.Category;
import com.photoalbum.entity.Photo;
import com.photoalbum.entity.PhotoCollectionPhoto;
import com.photoalbum.entity.ShareLink;
import com.photoalbum.entity.User;
import com.photoalbum.mapper.CategoryMapper;
import com.photoalbum.mapper.PhotoCollectionPhotoMapper;
import com.photoalbum.mapper.PhotoMapper;
import com.photoalbum.mapper.ShareLinkMapper;
import com.photoalbum.security.AccessPolicy;
import com.photoalbum.security.CurrentUserSupport;
import com.photoalbum.service.PhotoService;
import com.photoalbum.service.PhotoUrlResolver;
import com.photoalbum.service.TagService;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.imageio.ImageIO;
import java.util.LinkedHashSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 照片服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoServiceImpl extends ServiceImpl<PhotoMapper, Photo> implements PhotoService {

    private final PhotoMapper photoMapper;
    private final CategoryMapper categoryMapper;
    private final PhotoCollectionPhotoMapper collectionPhotoMapper;
    private final ShareLinkMapper shareLinkMapper;
    private final AccessPolicy accessPolicy;
    private final PhotoUrlResolver photoUrlResolver;
    private final TagService tagService;
    private final COSClient cosClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${cos.bucket}")
    private String cosBucket;

    @Value("${cos.domain}")
    private String cosDomain;

    @Value("${file.access-url-prefix}")
    private String accessUrlPrefix;

    /**
     * 单文件大小上限（MB）。
     * 保留 Java 初始值，便于不经 Spring 注入的单元测试使用；
     * 生产环境通过 file.max-size-mb（或环境变量 FILE_MAX_SIZE_MB）覆盖。
     */
    @Value("${file.max-size-mb:10}")
    private int maxSizeMb = 10;

    /**
     * 扩展名 → 服务端认定的内容类型（不采信客户端提交的 Content-Type）
     */
    private static final Map<String, String> EXT_CONTENT_TYPE = Map.of(
            ".jpg", "image/jpeg",
            ".jpeg", "image/jpeg",
            ".png", "image/png",
            ".gif", "image/gif",
            ".webp", "image/webp",
            ".bmp", "image/bmp");

    /** 当前用户是否为管理员（角色判定，与可见性策略口径一致） */
    private boolean isAdmin() {
        User user = getCurrentUser();
        return user != null && UserRoles.isAdmin(user.getRole());
    }

    private User getCurrentUser() {
        return CurrentUserSupport.getCurrentUser();
    }

    /** 可见范围内的照片查询条件（计数/统计/标签聚合统一复用，保证口径一致） */
    private LambdaQueryWrapper<Photo> visiblePhotoWrapper() {
        LambdaQueryWrapper<Photo> wrapper = new LambdaQueryWrapper<>();
        accessPolicy.applyPhotoFilter(wrapper, getCurrentUser());
        return wrapper;
    }

    /**
     * 分页查询
     */
    @Override
    public Object getPhotoPage(PhotoDTO dto) {
        Page<Photo> page = new Page<>(dto.getPageNum(), dto.getPageSize());

        LambdaQueryWrapper<Photo> wrapper = new LambdaQueryWrapper<>();

        // 关键词搜索（标题或描述）
        if (dto.getKeyword() != null && !dto.getKeyword().isBlank()) {
            wrapper.and(w -> w
                    .like(Photo::getTitle, dto.getKeyword())
                    .or()
                    .like(Photo::getDescription, dto.getKeyword())
            );
        }

        // 分类筛选
        if (dto.getCategoryIdFilter() != null) {
            wrapper.eq(Photo::getCategoryId, dto.getCategoryIdFilter());
        }

        // 非管理员看不到私密照片
        accessPolicy.applyPhotoFilter(wrapper, getCurrentUser());

        wrapper.orderByDesc(Photo::getCreatedAt);

        Page<Photo> result = photoMapper.selectPage(page, wrapper);

        // 转换为 DTO，填充分类名称
        List<PhotoDTO> dtoList = result.getRecords().stream().map(this::toDTO).collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("list", dtoList);
        data.put("total", result.getTotal());
        data.put("pageNum", result.getCurrent());
        data.put("pageSize", result.getSize());
        return data;
    }

    /**
     * 上传图片到腾讯云 COS
     */
    @Override
    public PhotoDTO upload(MultipartFile file, String title, Long categoryId, Long collectionId,
                           String description, String tags, Integer isPrivate) throws Exception {
        validateFile(file);

        String dateDir = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
        String originalName = file.getOriginalFilename();
        String ext = getFileExtension(originalName);
        String uuidName = UUID.randomUUID().toString().replace("-", "") + ext;

        byte[] fileBytes = file.getBytes();

        // 内容类型由服务端按扩展名白名单确定，不采信客户端提交值：
        // 否则可上传扩展名为图片、内容类型为 text/html 的文件，使其在对象存储域名下被当作网页解析
        String contentType = EXT_CONTENT_TYPE.getOrDefault(ext, "application/octet-stream");

        // 上传原图到 COS
        String cosKey = dateDir + "/" + uuidName;
        uploadToCos(fileBytes, cosKey, contentType);

        // 生成缩略图并上传（800px 宽，高画质）
        String thumbKey = dateDir + "/thumb_" + uuidName.replaceFirst("\\.[^.]+$", ".jpg");
        byte[] thumbBytes = generateThumbnailBytes(fileBytes, 800);
        if (thumbBytes != null) {
            uploadToCos(thumbBytes, thumbKey, "image/jpeg");
        }

        // EXIF 解析（用临时文件；无论成功或异常都必须清理，避免临时目录堆积）
        Map<String, String> exifData;
        File tmpFile = null;
        try {
            tmpFile = File.createTempFile("upload_", ext);
            file.transferTo(tmpFile);
            exifData = parseExif(tmpFile);
        } finally {
            if (tmpFile != null && tmpFile.exists() && !tmpFile.delete()) {
                log.warn("临时文件清理失败: {}", tmpFile.getAbsolutePath());
            }
        }

        Photo photo = new Photo();
        photo.setTitle(title != null ? title : originalName);
        photo.setDescription(description != null ? description : "");
        photo.setCategoryId(categoryId);
        photo.setUrl(cosDomain + "/" + cosKey);
        photo.setThumbnailUrl(thumbBytes != null ? cosDomain + "/" + thumbKey : null);
        photo.setFileName(originalName);
        photo.setFileSize(file.getSize());
        photo.setTags(tags != null ? tags : "");
        photo.setIsPrivate(isPrivate != null ? isPrivate : 0);
        photo.setViewCount(0);
        photo.setLikeCount(0);
        photo.setExifInfo(exifData.get("exifJson"));
        String rawModel = exifData.getOrDefault("cameraModel", "");
        photo.setCameraModel(rawModel.trim().replaceAll("\\s+", " "));
        photo.setAperture(exifData.getOrDefault("aperture", ""));
        photo.setShutterSpeed(exifData.getOrDefault("shutterSpeed", ""));
        photo.setIso(exifData.getOrDefault("iso", ""));
        photo.setFocalLength(exifData.getOrDefault("focalLength", ""));
        photo.setDateTaken(exifData.getOrDefault("dateTaken", ""));
        photo.setGpsLatitude(parseDecimal(exifData.get("gpsLatitude")));
        photo.setGpsLongitude(parseDecimal(exifData.get("gpsLongitude")));
        photo.setCreatedAt(LocalDateTime.now());
        photo.setUpdatedAt(LocalDateTime.now());

        photoMapper.insert(photo);

        // 标签关联表同步（展示字段与关联表双写，标签管理与标签级授权以关联表为准）
        tagService.syncRelations(photo.getId(), photo.getTags());

        // 私密照片：把对象 ACL 置为 private，使其直链不可被匿名访问
        // （访问改为由接口签发短期预签名地址，见 PhotoUrlResolver）
        applyPrivateAcl(photo);

        // 若指定了合集，自动加入
        if (collectionId != null) {
            PhotoCollectionPhoto rel = new PhotoCollectionPhoto();
            rel.setCollectionId(collectionId);
            rel.setPhotoId(photo.getId());
            collectionPhotoMapper.insert(rel);
        }

        log.info("图片上传成功到COS: {} -> {}", originalName, cosKey);
        return toDTO(photo);
    }

    private void uploadToCos(byte[] data, String key, String contentType) {
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(data.length);
        meta.setContentType(contentType);
        cosClient.putObject(new PutObjectRequest(cosBucket, key,
                new ByteArrayInputStream(data), meta));
    }

    private byte[] generateThumbnailBytes(byte[] imageBytes, int maxWidth) {
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (original == null) return null;
            int w = original.getWidth();
            int h = original.getHeight();
            if (w <= maxWidth) return imageBytes;

            int newW = maxWidth;
            int newH = (int) (h * ((double) maxWidth / w));
            BufferedImage thumb = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = thumb.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.drawImage(original, 0, 0, newW, newH, null);
            g2d.dispose();

            // 高质量 JPEG 输出
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            javax.imageio.plugins.jpeg.JPEGImageWriteParam jpegParams = 
                (javax.imageio.plugins.jpeg.JPEGImageWriteParam) writer.getDefaultWriteParam();
            jpegParams.setCompressionMode(javax.imageio.plugins.jpeg.JPEGImageWriteParam.MODE_EXPLICIT);
            jpegParams.setCompressionQuality(0.85f);
            writer.setOutput(ImageIO.createImageOutputStream(out));
            writer.write(null, new javax.imageio.IIOImage(thumb, null, null), jpegParams);
            writer.dispose();
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("缩略图生成失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 修改照片信息
     */
    @Override
    public void updatePhoto(Long id, PhotoDTO dto) {
        Photo photo = photoMapper.selectById(id);
        if (photo == null) {
            throw new BusinessException(404, "照片不存在");
        }

        if (dto.getTitle() != null) photo.setTitle(dto.getTitle());
        if (dto.getDescription() != null) photo.setDescription(dto.getDescription());
        if (dto.getCategoryId() != null) photo.setCategoryId(dto.getCategoryId());
        if (dto.getTags() != null) photo.setTags(dto.getTags());
        boolean privacyChanged = false;
        if (dto.getIsPrivate() != null && isAdmin()) {
            privacyChanged = !dto.getIsPrivate().equals(photo.getIsPrivate());
            photo.setIsPrivate(dto.getIsPrivate());
        }
        photo.setUpdatedAt(LocalDateTime.now());

        photoMapper.updateById(photo);

        // 标签变更时同步关联表
        if (dto.getTags() != null) {
            tagService.syncRelations(photo.getId(), photo.getTags());
        }

        // 私密标记变化时同步对象 ACL（公开↔私密）
        if (privacyChanged) {
            applyPrivateAcl(photo);
        }
    }

    /**
     * 按照片的私密标记同步对象 ACL（私密=private，公开=public-read）
     */
    private void applyPrivateAcl(Photo photo) {
        photoUrlResolver.applyObjectAcl(photo.getUrl(), photo.getIsPrivate());
        photoUrlResolver.applyObjectAcl(photo.getThumbnailUrl(), photo.getIsPrivate());
    }

    /**
     * 修复存量私密照片的对象 ACL（一次性维护动作）
     *
     * 历史私密照片的对象可能仍是公共读，执行后其直链将不可匿名访问，只能通过预签名地址访问。
     *
     * @return 已处理（尝试修复）的照片数量
     */
    @Override
    public int repairPrivateAcl() {
        if (!photoUrlResolver.isPrivateAclEnabled()) {
            throw new BusinessException("当前未启用对象级私密 ACL（cos.private-acl-enabled=false）");
        }
        List<Photo> privatePhotos = photoMapper.selectList(
                new LambdaQueryWrapper<Photo>().eq(Photo::getIsPrivate, 1));
        for (Photo photo : privatePhotos) {
            applyPrivateAcl(photo);
        }
        log.info("存量私密照片对象 ACL 修复完成: 共 {} 张", privatePhotos.size());
        return privatePhotos.size();
    }

    /**
     * 删除单张照片（同步删除磁盘文件）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePhoto(Long id) throws Exception {
        Photo photo = photoMapper.selectById(id);
        if (photo == null) {
            throw new BusinessException(404, "照片不存在");
        }

        // 删除 COS 文件（原图 + 缩略图）
        deleteFile(photo.getUrl());
        deleteFile(photo.getThumbnailUrl());

        // 删除合集关联
        LambdaQueryWrapper<PhotoCollectionPhoto> colWp = new LambdaQueryWrapper<>();
        colWp.eq(PhotoCollectionPhoto::getPhotoId, id);
        collectionPhotoMapper.delete(colWp);

        // 删除分享链接
        LambdaQueryWrapper<ShareLink> shareWp = new LambdaQueryWrapper<>();
        shareWp.eq(ShareLink::getPhotoId, id);
        shareLinkMapper.delete(shareWp);

        // 删除数据库记录
        photoMapper.deleteById(id);

        log.info("照片已删除: id={}", id);
    }

    /**
     * 仪表盘统计数据
     */
    @Override
    public Map<String, Object> getDashboardStats() {
        Map<String, Object> data = new HashMap<>();

        // 照片总数
        long totalPhotos = photoMapper.selectCount(visiblePhotoWrapper());
        data.put("totalPhotos", totalPhotos);

        // 分类数量
        long totalCategories = categoryMapper.selectCount(null);
        data.put("totalCategories", totalCategories);

        // 总浏览量
        List<Photo> allPhotos = photoMapper.selectList(visiblePhotoWrapper());
        int totalViews = allPhotos.stream()
                .mapToInt(p -> p.getViewCount() != null ? p.getViewCount() : 0)
                .sum();
        data.put("totalViews", totalViews);

        // 存储用量
        long totalBytes = allPhotos.stream()
                .mapToLong(p -> p.getFileSize() != null ? p.getFileSize() : 0L)
                .sum();
        data.put("storageUsed", formatFileSize(totalBytes));

        // 最近 5 张上传
        LambdaQueryWrapper<Photo> recentWp = new LambdaQueryWrapper<>();
        accessPolicy.applyPhotoFilter(recentWp, getCurrentUser());
        recentWp.orderByDesc(Photo::getCreatedAt).last("LIMIT 5");
        List<Photo> recentPhotos = photoMapper.selectList(recentWp);
        List<Map<String, Object>> recentList = recentPhotos.stream().map(p -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", p.getId());
            item.put("title", p.getTitle());
            item.put("url", accessUrlPrefix + p.getUrl());
            String thumb = p.getThumbnailUrl();
            item.put("thumbnailUrl", (thumb != null && !thumb.isEmpty()) ? (thumb.startsWith("http") ? thumb : accessUrlPrefix + thumb) : null);
            item.put("createdAt", p.getCreatedAt() != null ? p.getCreatedAt().toString() : "");
            // 填充分类名称
            if (p.getCategoryId() != null) {
                Category cat = categoryMapper.selectById(p.getCategoryId());
                item.put("categoryName", cat != null ? cat.getName() : "");
            } else {
                item.put("categoryName", "");
            }
            return item;
        }).collect(Collectors.toList());
        data.put("recentPhotos", recentList);

        // EXIF 统计分析
        Map<String, Object> exifStats = new HashMap<>();

        // 焦段分布
        Map<String, Long> focalLengths = allPhotos.stream()
                .filter(p -> p.getFocalLength() != null && !p.getFocalLength().isBlank())
                .collect(Collectors.groupingBy(Photo::getFocalLength, Collectors.counting()));
        exifStats.put("focalLengths", focalLengths);

        // 相机型号分布（归一化：去空格、统一大写，避免 "PENTAX KS-2" 和 "Pentax K-S2" 被视为不同型号）
        Map<String, Long> cameras = allPhotos.stream()
                .filter(p -> p.getCameraModel() != null && !p.getCameraModel().isBlank())
                .collect(Collectors.groupingBy(
                        p -> p.getCameraModel().trim().replaceAll("\\s+", " ").toUpperCase(),
                        Collectors.counting()));
        exifStats.put("cameras", cameras);

        // ISO 分布
        Map<String, Long> isos = allPhotos.stream()
                .filter(p -> p.getIso() != null && !p.getIso().isBlank())
                .collect(Collectors.groupingBy(Photo::getIso, Collectors.counting()));
        exifStats.put("isos", isos);

        // 拍摄时间按年份分布（从 dateTaken 提取年份）
        Map<String, Long> yearDistribution = allPhotos.stream()
                .filter(p -> p.getDateTaken() != null && !p.getDateTaken().isBlank())
                .map(p -> {
                    String dateTaken = p.getDateTaken();
                    // dateTaken 格式如 "2024:10:15 14:30:00" 或 "2024-10-15"
                    if (dateTaken.length() >= 4) {
                        return dateTaken.substring(0, 4);
                    }
                    return dateTaken;
                })
                .filter(year -> year.matches("\\d{4}"))
                .collect(Collectors.groupingBy(year -> year, Collectors.counting()));
        exifStats.put("yearDistribution", yearDistribution);

        data.put("exifStats", exifStats);

        return data;
    }

    /**
     * 获取标签列表（含引用计数）
     */
    @Override
    public List<Map<String, Object>> getTagList() {
        LambdaQueryWrapper<Photo> wrapper = new LambdaQueryWrapper<>();
        accessPolicy.applyPhotoFilter(wrapper, getCurrentUser());
        List<Photo> allPhotos = photoMapper.selectList(wrapper);
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

    /**
     * 批量更新照片（修改分类 + 追加标签）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchUpdate(List<Long> ids, Long categoryId, String appendTags) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("请选择要编辑的照片");
        }
        if (ids.size() > 50) {
            throw new BusinessException("单次最多编辑 50 张");
        }

        List<Photo> photos = photoMapper.selectBatchIds(ids);
        for (Photo photo : photos) {
            boolean modified = false;

            // 修改分类
            if (categoryId != null) {
                photo.setCategoryId(categoryId);
                modified = true;
            }

            // 追加标签（去重）
            if (appendTags != null && !appendTags.isBlank()) {
                String existingTags = photo.getTags() != null ? photo.getTags() : "";
                Set<String> tagSet = new LinkedHashSet<>();
                // 先收集已有标签
                if (!existingTags.isBlank()) {
                    for (String t : existingTags.split(",")) {
                        String trimmed = t.trim();
                        if (!trimmed.isEmpty()) tagSet.add(trimmed);
                    }
                }
                // 追加新标签
                for (String t : appendTags.split(",")) {
                    String trimmed = t.trim();
                    if (!trimmed.isEmpty()) tagSet.add(trimmed);
                }
                photo.setTags(String.join(",", tagSet));
                modified = true;
            }

            if (modified) {
                photo.setUpdatedAt(LocalDateTime.now());
                photoMapper.updateById(photo);
                // 标签追加后同步关联表
                tagService.syncRelations(photo.getId(), photo.getTags());
            }
        }

        log.info("批量编辑完成: ids={}, categoryId={}, appendTags={}", ids, categoryId, appendTags);
    }

    /**
     * 获取所有有 GPS 坐标的照片
     */
    @Override
    public List<PhotoDTO> getGpsPhotos() {
        LambdaQueryWrapper<Photo> wrapper = new LambdaQueryWrapper<>();
        wrapper.isNotNull(Photo::getGpsLatitude)
                .isNotNull(Photo::getGpsLongitude);
        accessPolicy.applyPhotoFilter(wrapper, getCurrentUser());
        wrapper.orderByDesc(Photo::getCreatedAt);

        List<Photo> photos = photoMapper.selectList(wrapper);
        return photos.stream().map(this::toDTO).collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> getAdjacentIds(Long id) {
        Map<String, Object> result = new HashMap<>();
        // 上一条：id > 当前id的最小的一个
        LambdaQueryWrapper<Photo> prevWp = new LambdaQueryWrapper<>();
        prevWp.lt(Photo::getId, id);
        accessPolicy.applyPhotoFilter(prevWp, getCurrentUser());
        prevWp.orderByDesc(Photo::getId).last("LIMIT 1");
        Photo prev = photoMapper.selectOne(prevWp);
        result.put("prevId", prev != null ? prev.getId() : null);

        // 下一条
        LambdaQueryWrapper<Photo> nextWp = new LambdaQueryWrapper<>();
        nextWp.gt(Photo::getId, id);
        accessPolicy.applyPhotoFilter(nextWp, getCurrentUser());
        nextWp.orderByAsc(Photo::getId).last("LIMIT 1");
        Photo next = photoMapper.selectOne(nextWp);
        result.put("nextId", next != null ? next.getId() : null);

        return result;
    }

    /**
     * 按可见性查询单张照片（详情接口使用）
     *
     * 与列表接口复用同一套可见性规则（applyViewerPermission）：
     * 未登录与普通用户仅可见公开照片，viewer 角色按白名单/黑名单过滤，admin 全量可见。
     * 无权限访问私密照片时返回 null，由调用方按 404 处理以避免暴露资源存在性。
     */
    @Override
    public Photo getVisiblePhoto(Long id) {
        if (id == null) {
            return null;
        }
        Photo photo = photoMapper.selectById(id);
        return accessPolicy.canViewPhoto(getCurrentUser(), photo) ? photo : null;
    }

    /**
     * 格式化文件大小
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * 批量删除
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchDelete(List<Long> ids) throws Exception {
        for (Long id : ids) {
            Photo photo = photoMapper.selectById(id);
            if (photo == null) continue;
            try {
                deleteFile(photo.getUrl());
            } catch (Exception e) {
                log.warn("删除文件失败: {}", photo.getUrl(), e);
            }
        }
        photoMapper.deleteBatchIds(ids);
        log.info("批量删除完成: ids={}", ids);
    }

    // ========== 私有工具方法 ==========

    /**
     * 校验上传文件
     *
     * 依次校验：非空 → 扩展名白名单 → 大小上限 → 文件头魔数（真实类型）。
     * 仅校验扩展名不足以阻止"伪装成图片的网页/脚本文件"被写入对象存储。
     */
    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }

        String originalName = file.getOriginalFilename();
        String ext = getFileExtension(originalName);
        if (!EXT_CONTENT_TYPE.containsKey(ext)) {
            throw new BusinessException("仅支持 JPG/PNG/WEBP/GIF/BMP 格式");
        }

        long maxBytes = (long) maxSizeMb * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw new BusinessException("文件大小不能超过 " + maxSizeMb + "MB");
        }

        byte[] head = new byte[12];
        int read;
        try (java.io.InputStream in = file.getInputStream()) {
            read = in.readNBytes(head, 0, head.length);
        } catch (IOException e) {
            log.warn("上传文件读取失败: {}", e.getMessage());
            throw new BusinessException("文件读取失败，请重试");
        }
        if (!matchesImageSignature(head, read)) {
            throw new BusinessException("文件内容不是有效的图片");
        }
    }

    /**
     * 文件头（魔数）校验：确认文件内容确为允许的图片类型
     *
     * @param head   文件头字节
     * @param length 实际读取到的字节数
     */
    private boolean matchesImageSignature(byte[] head, int length) {
        // JPEG: FF D8 FF
        if (length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return true;
        }
        // PNG: 89 50 4E 47
        if (length >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
            return true;
        }
        // GIF: GIF8
        if (length >= 6 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8') {
            return true;
        }
        // BMP: BM
        if (length >= 2 && head[0] == 'B' && head[1] == 'M') {
            return true;
        }
        // WebP: RIFF .... WEBP
        return length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
    }

    /**
     * 从 COS 删除文件
     */
    private void deleteFile(String url) {
        if (url == null || url.isBlank()) return;
        try {
            // 从完整 URL 提取 COS key
            String key = url.replace(cosDomain + "/", "");
            cosClient.deleteObject(cosBucket, key);
        } catch (Exception e) {
            log.warn("删除COS文件失败: {}", url, e);
        }
    }

    /**
     * 解析图片 EXIF 信息，返回包含 JSON 和结构化字段的 Map
     *
     * @param file 图片文件
     * @return Map，key 包括:
     *         "exifJson"      - 完整 EXIF JSON 字符串
     *         "cameraModel"   - 相机型号
     *         "aperture"      - 光圈值，如 f/2.8
     *         "shutterSpeed"  - 快门速度，如 1/125s
     *         "iso"           - ISO 感光度
     *         "focalLength"   - 焦距，如 50mm
     *         "dateTaken"     - 拍摄时间原始值
     */
    private Map<String, String> parseExif(java.io.File file) {
        Map<String, Object> exif = new LinkedHashMap<>();
        Map<String, String> result = new LinkedHashMap<>();
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(file);

            // 相机信息（IFD0）
            ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (ifd0 != null) {
                safePut(exif, "相机型号", ifd0, ExifIFD0Directory.TAG_MODEL);
                safePut(exif, "制造商", ifd0, ExifIFD0Directory.TAG_MAKE);
                // 结构化字段
                result.put("cameraModel",
                        extractTagString(ifd0, ExifIFD0Directory.TAG_MODEL));
            } else {
                result.put("cameraModel", "");
            }

            // 拍摄参数（Exif SubIFD）
            ExifSubIFDDirectory subIfd = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (subIfd != null) {
                safePut(exif, "光圈", subIfd, ExifSubIFDDirectory.TAG_FNUMBER);
                safePut(exif, "快门速度", subIfd, ExifSubIFDDirectory.TAG_EXPOSURE_TIME);
                safePut(exif, "ISO", subIfd, ExifSubIFDDirectory.TAG_ISO_EQUIVALENT);
                safePut(exif, "焦距", subIfd, ExifSubIFDDirectory.TAG_FOCAL_LENGTH);
                safePut(exif, "拍摄时间", subIfd, ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
                safePut(exif, "曝光模式", subIfd, ExifSubIFDDirectory.TAG_EXPOSURE_MODE);
                safePut(exif, "白平衡", subIfd, ExifSubIFDDirectory.TAG_WHITE_BALANCE_MODE);
                safePut(exif, "闪光灯", subIfd, ExifSubIFDDirectory.TAG_FLASH);
                // 结构化字段
                result.put("aperture",
                        extractTagString(subIfd, ExifSubIFDDirectory.TAG_FNUMBER));
                result.put("shutterSpeed",
                        formatShutterSpeed(extractTagString(subIfd, ExifSubIFDDirectory.TAG_EXPOSURE_TIME)));
                result.put("iso",
                        extractTagString(subIfd, ExifSubIFDDirectory.TAG_ISO_EQUIVALENT));
                result.put("focalLength",
                        formatFocalLength(extractTagString(subIfd, ExifSubIFDDirectory.TAG_FOCAL_LENGTH)));
                result.put("dateTaken",
                        extractTagString(subIfd, ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL));
            } else {
                result.put("aperture", "");
                result.put("shutterSpeed", "");
                result.put("iso", "");
                result.put("focalLength", "");
                result.put("dateTaken", "");
            }

            // GPS 信息
            GpsDirectory gpsDir = metadata.getFirstDirectoryOfType(GpsDirectory.class);
            if (gpsDir != null && gpsDir.getGeoLocation() != null) {
                result.put("gpsLatitude", String.valueOf(gpsDir.getGeoLocation().getLatitude()));
                result.put("gpsLongitude", String.valueOf(gpsDir.getGeoLocation().getLongitude()));
            } else {
                result.put("gpsLatitude", "");
                result.put("gpsLongitude", "");
            }

            result.put("exifJson", exif.isEmpty() ? null : objectMapper.writeValueAsString(exif));
            return result;
        } catch (Exception e) {
            log.debug("EXIF 解析失败: {}", e.getMessage());
            result.put("exifJson", null);
            return result;
        }
    }

    /**
     * 安全地从 Directory 中提取标签原始字符串值
     *
     * @param dir     EXIF 目录
     * @param tagType 标签类型
     * @return 标签字符串值，若不存在则返回 ""
     */
    private String extractTagString(Directory dir, int tagType) {
        try {
            String value = dir.getString(tagType);
            return (value != null && !value.isBlank()) ? value : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * 格式化快门速度字符串，将 "1/125 sec" 转为 "1/125s"
     */
    private String formatShutterSpeed(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.replace(" sec", "s");
    }

    /**
     * 安全解析 Decimal 字符串为 Double，用于 GPS 坐标
     */
    private Double parseDecimal(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 格式化焦距字符串，将 "50.0 mm" 转为 "50mm"
     */
    private String formatFocalLength(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.replace(" ", "");
    }

    /**
     * 安全地从 Directory 中提取标签值并放入 Map
     */
    private void safePut(Map<String, Object> map, String key, Directory dir, int tagType) {
        try {
            String value = dir.getString(tagType);
            if (value != null && !value.isBlank()) {
                // 格式化快门速度（如 "1/125 sec" → "1/125s"）
                if (tagType == ExifSubIFDDirectory.TAG_EXPOSURE_TIME) {
                    value = value.replace(" sec", "s");
                }
                // 格式化焦距（如 "50.0 mm" → "50mm"）
                if (tagType == ExifSubIFDDirectory.TAG_FOCAL_LENGTH) {
                    value = value.replace(" ", "");
                }
                map.put(key, value);
            }
        } catch (Exception ignored) {
            // 该标签不存在则跳过
        }
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "";
        return filename.substring(filename.lastIndexOf(".")).toLowerCase();
    }

    /**
     * 点赞照片，返回最新点赞数
     *
     * 使用 SQL 原子自增：此前的"读-改-写"在并发点赞时会丢更新。
     */
    public int likePhoto(Long id) {
        photoMapper.update(null, new LambdaUpdateWrapper<Photo>()
                .setSql("like_count = COALESCE(like_count, 0) + 1")
                .eq(Photo::getId, id));
        Photo photo = photoMapper.selectById(id);
        if (photo == null) {
            throw new BusinessException("照片不存在");
        }
        return photo.getLikeCount() == null ? 0 : photo.getLikeCount();
    }

    /**
     * 浏览量 +1，返回最新浏览量
     *
     * 同样改为原子自增，避免并发访问时丢计数。
     */
    @Override
    public int incrementViewCount(Long id) {
        photoMapper.update(null, new LambdaUpdateWrapper<Photo>()
                .setSql("view_count = COALESCE(view_count, 0) + 1")
                .eq(Photo::getId, id));
        Photo photo = photoMapper.selectById(id);
        return photo != null && photo.getViewCount() != null ? photo.getViewCount() : 0;
    }

    /**
     * Entity -> DTO 转换，补充分类名称和完整访问URL
     */
    public PhotoDTO toDTO(Photo photo) {
        PhotoDTO dto = new PhotoDTO();
        dto.setId(photo.getId());
        dto.setTitle(photo.getTitle());
        dto.setDescription(photo.getDescription());
        dto.setCategoryId(photo.getCategoryId());
        // 公开照片返回直链；私密照片返回短期预签名地址（对象 ACL 已置为私有）
        dto.setUrl(photoUrlResolver.resolve(photo.getUrl(), photo.getIsPrivate()));
        dto.setThumbnailUrl(photoUrlResolver.resolveThumbnail(photo.getThumbnailUrl(), photo.getIsPrivate()));
        dto.setFileName(photo.getFileName());
        dto.setFileSize(photo.getFileSize());
        dto.setTags(photo.getTags());
        dto.setIsPrivate(photo.getIsPrivate());
        dto.setViewCount(photo.getViewCount());
        dto.setLikeCount(photo.getLikeCount());
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
}
