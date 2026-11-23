package com.photoalbum.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.photoalbum.common.BusinessException;
import com.photoalbum.common.PermissionConstants;
import com.photoalbum.common.UserRoles;
import com.photoalbum.config.MybatisPlusConfig;
import com.photoalbum.dto.PhotoDTO;
import com.photoalbum.entity.AuthTuple;
import com.photoalbum.entity.Category;
import com.photoalbum.entity.Photo;
import com.photoalbum.entity.PhotoCollection;
import com.photoalbum.entity.PhotoCollectionPhoto;
import com.photoalbum.entity.ShareLink;
import com.photoalbum.entity.User;
import com.photoalbum.mapper.AuthTupleMapper;
import com.photoalbum.mapper.CategoryMapper;
import com.photoalbum.mapper.PhotoCollectionMapper;
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
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
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
    private final PhotoCollectionMapper collectionMapper;
    private final PhotoCollectionPhotoMapper collectionPhotoMapper;
    private final ShareLinkMapper shareLinkMapper;
    private final AuthTupleMapper authTupleMapper;
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

    /** 默认每页条数（pageNum / pageSize 未传或取值非法时使用） */
    private static final int DEFAULT_PAGE_SIZE = 10;

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

    /** 聚合结果为 BigDecimal/Long，统一转 long（null 视为 0） */
    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** 可见范围内的照片查询条件（计数/统计/标签聚合统一复用，保证口径一致） */
    private LambdaQueryWrapper<Photo> visiblePhotoWrapper() {
        LambdaQueryWrapper<Photo> wrapper = new LambdaQueryWrapper<>();
        accessPolicy.applyPhotoFilter(wrapper, getCurrentUser());
        return wrapper;
    }

    /**
     * 分页查询
     *
     * 入参收敛（修复 H01 的第二个面）：页码与每页条数都由本方法限制在合法区间，
     * 不能只依赖前端传值。分页拦截器负责生成 LIMIT，本方法负责让入参无法绕过它。
     */
    @Override
    public Object getPhotoPage(PhotoDTO dto) {
        Page<Photo> page = new Page<>(normalizePageNum(dto.getPageNum()), normalizePageSize(dto.getPageSize()));

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

        // 分类名批量预取：一页只查一次，而不是每行查一次
        Map<Long, String> categoryNames = categoryNamesOf(
                result.getRecords().stream().map(Photo::getCategoryId).collect(Collectors.toList()));
        List<PhotoDTO> dtoList = result.getRecords().stream()
                .map(photo -> toDTO(photo, categoryNames, false))
                .collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("list", dtoList);
        data.put("total", result.getTotal());
        data.put("pageNum", result.getCurrent());
        data.put("pageSize", result.getSize());
        return data;
    }

    /**
     * 收敛页码：非正数一律回到第 1 页
     *
     * MyBatis-Plus 的页码从 1 开始，0 或负数会算出非法偏移量（表现为 SQL 异常或返回空）。
     */
    private int normalizePageNum(Integer pageNum) {
        return (pageNum == null || pageNum < 1) ? 1 : pageNum;
    }

    /**
     * 收敛每页条数：限制在 [1, MybatisPlusConfig.MAX_PAGE_SIZE]
     *
     * 下界必须收到 1，不能只设上限：MyBatis-Plus 的语义是 **size < 0 表示不分页**，
     * 若放行 pageSize=-1，等于绕过刚注册的分页拦截器、重新变成一次返回全表。
     * 上界与拦截器的 maxLimit 共用同一常量，避免两处取值漂移。
     */
    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MybatisPlusConfig.MAX_PAGE_SIZE);
    }

    /**
     * 上传图片到腾讯云 COS
     */
    @Override
    public PhotoDTO upload(MultipartFile file, String title, Long categoryId, Long collectionId,
                           String description, String tags, Integer isPrivate) throws Exception {
        // 私密照片仅限管理员上传（编辑入口同样仅管理员可改，见 updatePhoto）
        if (isPrivate != null && isPrivate == 1 && !isAdmin()) {
            throw new BusinessException(403, "仅管理员可上传私密照片");
        }
        // 目标合集必须存在且当前调用者可管理：
        // 否则只持 photo:upload 的账号可把照片注入任意他人的合集（含私密合集），
        // 绕过 POST /api/admin/collections/{id}/photos/{photoId} 上的对象级校验。
        PhotoCollection targetCollection = null;
        if (collectionId != null) {
            targetCollection = collectionMapper.selectById(collectionId);
            if (targetCollection == null || !accessPolicy.canManageCollection(getCurrentUser(), targetCollection)) {
                throw new BusinessException(404, "合集不存在");
            }
        }
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
        // 私密合集内的新照片与合集保持一致（与 CollectionService.addPhoto 的级联规则对齐），
        // 否则上传路径会成为向私密合集塞入公开照片的旁路
        boolean inheritPrivate = targetCollection != null
                && targetCollection.getIsPrivate() != null && targetCollection.getIsPrivate() == 1;
        photo.setIsPrivate(inheritPrivate ? 1 : (isPrivate != null ? isPrivate : 0));
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

    /**
     * 生成缩略图字节（统一输出 JPEG）
     *
     * 三个要点：
     *  1) **降采样解码**：让解码器直接按 1/2、1/4、1/8 给出较小的位图，而不是先解出全尺寸
     *     再缩小。一张 2400 万像素的照片全尺寸解码要约 72MB 堆内存，降采样后只要几 MB，
     *     这是 2G 内存机器上"批量上传失败"的主要诱因；
     *  2) **不放大**：目标长边取 min(原长边, 上限)。原文写的是
     *     `if (w <= maxWidth) return imageBytes;`——把小图的原始字节直接当缩略图，
     *     导致同一份内容在对象存储里存两份、且 Content-Type 一律被标成 image/jpeg。
     *     现在无论原图多大都重新编码一次，缩略图字段始终是真正的缩略图；
     *  3) **白底**：透明图（PNG 截图、logo）画到 TYPE_INT_RGB 上默认是黑底，
     *     先铺白再画。
     *
     * 解码失败的格式（如 JDK 不支持的 WebP）返回 null，调用方不设置 thumbnailUrl，
     * 前端会回退到原图地址。
     */
    byte[] generateThumbnailBytes(byte[] imageBytes, int maxEdge) {
        try {
            DecodedImage decoded = decodeForThumbnail(imageBytes, maxEdge);
            if (decoded == null || decoded.image() == null) {
                return null;
            }
            int srcWidth = decoded.sourceWidth();
            int srcHeight = decoded.sourceHeight();
            int srcLongEdge = Math.max(srcWidth, srcHeight);
            int targetLongEdge = Math.max(1, Math.min(maxEdge, srcLongEdge));
            double scale = targetLongEdge / (double) srcLongEdge;
            int targetWidth = Math.max(1, (int) Math.round(srcWidth * scale));
            int targetHeight = Math.max(1, (int) Math.round(srcHeight * scale));

            BufferedImage canvas = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = canvas.createGraphics();
            try {
                // 透明区域按白底处理，避免 PNG 缩略图出现黑底
                g2d.setColor(java.awt.Color.WHITE);
                g2d.fillRect(0, 0, targetWidth, targetHeight);
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
                g2d.drawImage(decoded.image(), 0, 0, targetWidth, targetHeight, null);
            } finally {
                g2d.dispose();
            }
            return toJpegBytes(canvas);
        } catch (Exception e) {
            log.warn("缩略图生成失败: {}", e.getMessage());
            return null;
        }
    }

    /** 解码结果：位图 + 原始尺寸（决定"不放大"的目标尺寸需要原始尺寸） */
    private record DecodedImage(BufferedImage image, int sourceWidth, int sourceHeight) {
    }

    /**
     * 按需降采样解码
     *
     * 采样倍数取"仍能让解码尺寸不小于目标长边"的最大 2 的幂，留足余量避免
     * 按格取样造成的锯齿（例如 6000px 原图、目标 800px，取 4 倍 → 解码 1500px）。
     * 某些格式不支持降采样时退回全尺寸解码，行为与改造前一致。
     */
    private DecodedImage decodeForThumbnail(byte[] imageBytes, int maxEdge) throws IOException {
        try (javax.imageio.stream.ImageInputStream iis =
                     ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
            if (iis == null) {
                return null;
            }
            java.util.Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return null; // JDK 没有对应格式的解码器（如 WebP）
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, false, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    return null;
                }
                int srcLongEdge = Math.max(width, height);
                int targetLongEdge = Math.max(1, Math.min(maxEdge, srcLongEdge));
                int factor = 1;
                while (srcLongEdge / (factor * 2) >= targetLongEdge) {
                    factor *= 2;
                }

                BufferedImage image;
                try {
                    ImageReadParam param = reader.getDefaultReadParam();
                    if (factor > 1) {
                        param.setSourceSubsampling(factor, factor, 0, 0);
                    }
                    image = reader.read(0, param);
                } catch (Exception unsupported) {
                    log.debug("该格式不支持降采样，退回全尺寸解码: {}", unsupported.getMessage());
                    image = ImageIO.read(new ByteArrayInputStream(imageBytes));
                }
                return image == null ? null : new DecodedImage(image, width, height);
            } finally {
                reader.dispose();
            }
        }
    }

    /** 统一编码为 JPEG（q=0.85） */
    private byte[] toJpegBytes(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(0.85f);
            writer.setOutput(ios);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /**
     * 修改照片信息
     */
    @Override
    public void updatePhoto(Long id, PhotoDTO dto) {
        Photo photo = requireManageablePhoto(id);

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
     * 删除单张照片（同步删除对象存储文件与全部派生数据）
     *
     * 需对象级权限：不可见（含无权访问的私密照片）按"不存在"处理，
     * 避免用删除接口的 404/200 差异探测全站照片是否存在。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePhoto(Long id) throws Exception {
        Photo photo = requireManageablePhoto(id);

        // 清理派生资源（对象存储、合集关联、分享、标签关联、授权元组）
        purgePhotoDerivatives(photo);

        // 删除数据库记录
        photoMapper.deleteById(id);

        log.info("照片已删除: id={}", id);
    }

    /**
     * 清理一张照片的全部派生数据（不含照片主记录本身）
     *
     * 授权元组必须一起清理：t_auth_tuple 按 object_id 指向照片且没有外键，
     * 照片删除后残留就成了"幽灵授权"——一旦 id 被复用（灌库、备份恢复、
     * 手工改自增），新照片会继承旧照片的授权，原授权对象自动获得可见性。
     */
    private void purgePhotoDerivatives(Photo photo) {
        Long id = photo.getId();
        // 文件删除失败不应阻断数据清理（对象存储是外部依赖）
        try {
            deleteFile(photo.getUrl());
            deleteFile(photo.getThumbnailUrl());
        } catch (Exception e) {
            log.warn("删除照片文件失败: id={}", id, e);
        }

        collectionPhotoMapper.delete(new LambdaQueryWrapper<PhotoCollectionPhoto>()
                .eq(PhotoCollectionPhoto::getPhotoId, id));
        shareLinkMapper.delete(new LambdaQueryWrapper<ShareLink>()
                .eq(ShareLink::getPhotoId, id));
        authTupleMapper.delete(new LambdaQueryWrapper<AuthTuple>()
                .eq(AuthTuple::getObjectType, PermissionConstants.TARGET_PHOTO)
                .eq(AuthTuple::getObjectId, id));
        tagService.deleteRelationsOfPhoto(id);
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

        // 总浏览量 / 存储用量：用 SQL 聚合，不再把全部照片实体读进内存
        // （此前 selectList 全表 + 内存 sum，每行还带着 exif_info 这类大字段）
        Map<String, Object> sums = photoMapper.selectMaps(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Photo>()
                                .select("COALESCE(SUM(view_count), 0) AS views",
                                        "COALESCE(SUM(file_size), 0) AS bytes")
                                .apply(accessPolicy.visiblePhotoSql(getCurrentUser())))
                .stream().findFirst().orElse(Map.of());
        data.put("totalViews", (int) toLong(sums.get("views")));
        data.put("storageUsed", formatFileSize(toLong(sums.get("bytes"))));

        // EXIF 分布：需要逐行分组，但只取参与统计的 4 列（不再整行加载）
        LambdaQueryWrapper<Photo> exifWp = new LambdaQueryWrapper<>();
        accessPolicy.applyPhotoFilter(exifWp, getCurrentUser());
        exifWp.select(Photo::getFocalLength, Photo::getCameraModel, Photo::getIso, Photo::getDateTaken);
        List<Photo> allPhotos = photoMapper.selectList(exifWp);

        // 最近 5 张上传
        LambdaQueryWrapper<Photo> recentWp = new LambdaQueryWrapper<>();
        accessPolicy.applyPhotoFilter(recentWp, getCurrentUser());
        recentWp.orderByDesc(Photo::getCreatedAt).last("LIMIT 5");
        List<Photo> recentPhotos = photoMapper.selectList(recentWp);
        Map<Long, String> recentCategoryNames = categoryNamesOf(
                recentPhotos.stream().map(Photo::getCategoryId).collect(Collectors.toList()));
        List<Map<String, Object>> recentList = recentPhotos.stream().map(p -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", p.getId());
            item.put("title", p.getTitle());
            item.put("url", photoUrlResolver.resolve(p.getUrl(), p.getIsPrivate()));
            String thumb = photoUrlResolver.resolveThumbnail(p.getThumbnailUrl(), p.getIsPrivate());
            item.put("thumbnailUrl", (thumb != null && !thumb.isEmpty()) ? (thumb.startsWith("http") ? thumb : accessUrlPrefix + thumb) : null);
            item.put("createdAt", p.getCreatedAt() != null ? p.getCreatedAt().toString() : "");
            item.put("categoryName", p.getCategoryId() == null
                    ? "" : recentCategoryNames.getOrDefault(p.getCategoryId(), ""));
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
        // 只取 tags 一列：此前 selectList 会把整行实体读出来（含 exif_info、描述、URL 等大字段），
        // 而这里真正需要的只是逗号分隔的标签串
        wrapper.select(Photo::getTags);
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

        // 只处理当前调用者可见的照片：不可见的直接排除（与"不存在"同口径），
        // 否则批量编辑会变成对他人私密照片的盲改通道
        List<Photo> photos = photoMapper.selectBatchIds(ids).stream()
                .filter(photo -> accessPolicy.canViewPhoto(getCurrentUser(), photo))
                .collect(Collectors.toList());
        if (photos.isEmpty()) {
            throw new BusinessException(404, "照片不存在");
        }
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
        Map<Long, String> categoryNames = categoryNamesOf(
                photos.stream().map(Photo::getCategoryId).collect(Collectors.toList()));
        return photos.stream()
                .map(photo -> toDTO(photo, categoryNames, false))
                .collect(Collectors.toList());
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
     * 取"当前调用者可见范围内的照片"，不存在与不可见一律返回 null
     *
     * 写操作必须经过这里：只判"记录是否存在"会让写接口变成存在性预言机——
     * 详情接口对无权访问的私密照片刻意返回 404，而写接口若返回 200 就等于确认了该 ID 存在。
     * 批量场景下两者也必须归为同一结果（都跳过），否则"存在但无权"与"不存在"的差异
     * 同样会泄漏存在性。
     */
    private Photo loadManageablePhotoOrNull(Long id) {
        Photo photo = id == null ? null : photoMapper.selectById(id);
        return (photo != null && accessPolicy.canViewPhoto(getCurrentUser(), photo)) ? photo : null;
    }

    /** 取当前调用者可管理的照片；不可见与不存在统一按 404 处理 */
    private Photo requireManageablePhoto(Long id) {
        Photo photo = loadManageablePhotoOrNull(id);
        if (photo == null) {
            throw new BusinessException(404, "照片不存在");
        }
        return photo;
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
     *
     * 逐张做可见性判定：不存在与不可见都跳过（两者口径一致，不构成存在性预言机），
     * 只删真正可见的记录，因此不会出现"删掉了自己看不到的照片"。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchDelete(List<Long> ids) throws Exception {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<Long> deletable = new ArrayList<>();
        for (Long id : ids) {
            Photo photo = loadManageablePhotoOrNull(id);
            if (photo == null) {
                continue;
            }
            purgePhotoDerivatives(photo);
            deletable.add(photo.getId());
        }
        if (!deletable.isEmpty()) {
            photoMapper.deleteBatchIds(deletable);
        }
        log.info("批量删除完成: 请求 {} 张，实际删除 {} 张", ids.size(), deletable.size());
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
        long size = file.getSize();
        String ext = getFileExtension(originalName);
        if (!EXT_CONTENT_TYPE.containsKey(ext)) {
            // 记录被拒绝的文件名与扩展名：批量上传失败时靠这一行才能判断
            // 是"格式不支持（如 iPhone 的 HEIC、相机 RAW）"还是别的原因
            log.warn("上传被拒绝（格式不支持）: file={}, ext={}, size={}B", originalName, ext, size);
            throw new BusinessException("仅支持 JPG/PNG/WEBP/GIF/BMP 格式");
        }

        long maxBytes = (long) maxSizeMb * 1024 * 1024;
        if (size > maxBytes) {
            log.warn("上传被拒绝（超出大小上限）: file={}, size={}B, limit={}MB", originalName, size, maxSizeMb);
            throw new BusinessException("文件大小不能超过 " + maxSizeMb + "MB");
        }

        byte[] head = new byte[12];
        int read;
        try (java.io.InputStream in = file.getInputStream()) {
            read = in.readNBytes(head, 0, head.length);
        } catch (IOException e) {
            log.warn("上传文件读取失败: file={}, size={}B, err={}", originalName, size, e.getMessage());
            throw new BusinessException("文件读取失败，请重试");
        }
        if (!matchesImageSignature(head, read)) {
            log.warn("上传被拒绝（内容不是有效图片）: file={}, size={}B", originalName, size);
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
     * 使用 SQL 原子自增：此前的“读-改-写”在并发点赞时会丢更新。
     *
     * 可见性前置判定（修复 M01）：点赞接口对匿名开放，若不做判定，
     * 私密照片会变成一个“存在性预言机”——能点赞即说明该 ID 存在，
     * 而照片详情接口对这些照片刻意返回 404；同时匿名者也能给不可见照片刷量。
     * 不可见与不存在统一按 404 处理，与详情接口口径一致，不暴露资源存在性。
     */
    public int likePhoto(Long id) {
        if (getVisiblePhoto(id) == null) {
            throw new BusinessException(404, "照片不存在");
        }
        photoMapper.update(null, new LambdaUpdateWrapper<Photo>()
                .setSql("like_count = COALESCE(like_count, 0) + 1")
                .eq(Photo::getId, id));
        Photo photo = photoMapper.selectById(id);
        if (photo == null) {
            throw new BusinessException(404, "照片不存在");
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
        // 单条路径（详情/上传返回）：分类只查一次，EXIF 明细照常返回
        Map<Long, String> names = categoryNamesOf(
                photo.getCategoryId() == null ? List.of() : List.of(photo.getCategoryId()));
        return toDTO(photo, names, true);
    }

    /**
     * 列表页一次性预取分类名（同一个页面的照片通常只落在少数几个分类上）
     *
     * 此前 toDTO 逐行 `categoryMapper.selectById`，一页 20 条就是 20 次查询，
     * 而首页是"拉全量照片"，这个成本随照片数线性增长。
     */
    private Map<Long, String> categoryNamesOf(Collection<Long> categoryIds) {
        Set<Long> ids = categoryIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return categoryMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName, (a, b) -> a));
    }

    /**
     * @param categoryNames 调用方批量预取的"分类ID → 名称"；为空表示没有需要填充的分类
     * @param includeExif   列表接口传 false：exifInfo 是较大的 JSON，只有详情/灯箱需要
     */
    private PhotoDTO toDTO(Photo photo, Map<Long, String> categoryNames, boolean includeExif) {
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
        // exifInfo 是一段较大的 JSON，只有详情/灯箱需要；列表接口不返回
        if (includeExif) {
            dto.setExifInfo(photo.getExifInfo());
        }
        dto.setCameraModel(photo.getCameraModel());
        dto.setAperture(photo.getAperture());
        dto.setShutterSpeed(photo.getShutterSpeed());
        dto.setIso(photo.getIso());
        dto.setFocalLength(photo.getFocalLength());
        dto.setDateTaken(photo.getDateTaken());
        dto.setGpsLatitude(photo.getGpsLatitude());
        dto.setGpsLongitude(photo.getGpsLongitude());

        if (photo.getCategoryId() != null) {
            String name = categoryNames.get(photo.getCategoryId());
            if (name != null) {
                dto.setCategoryName(name);
            }
        }
        return dto;
    }
}
