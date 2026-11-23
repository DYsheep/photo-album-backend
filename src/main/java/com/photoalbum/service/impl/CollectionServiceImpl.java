package com.photoalbum.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.photoalbum.common.BusinessException;
import com.photoalbum.common.PermissionConstants;
import com.photoalbum.dto.CollectionDTO;
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
import com.photoalbum.mapper.CollectionMemberMapper;
import com.photoalbum.mapper.PhotoCollectionMapper;
import com.photoalbum.mapper.PhotoCollectionPhotoMapper;
import com.photoalbum.mapper.PhotoMapper;
import com.photoalbum.mapper.ShareLinkMapper;
import com.photoalbum.mapper.UserMapper;
import com.photoalbum.entity.CollectionMember;
import com.photoalbum.security.AccessPolicy;
import com.photoalbum.security.CurrentUserSupport;
import com.photoalbum.security.UserAuthorities;
import com.photoalbum.service.CollectionService;
import com.photoalbum.service.PhotoUrlResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionServiceImpl implements CollectionService {

    private final PhotoCollectionMapper collectionMapper;
    private final PhotoCollectionPhotoMapper collectionPhotoMapper;
    private final PhotoMapper photoMapper;
    private final CategoryMapper categoryMapper;
    private final AccessPolicy accessPolicy;
    private final PhotoUrlResolver photoUrlResolver;
    private final CollectionMemberMapper memberMapper;
    private final UserMapper userMapper;
    private final AuthTupleMapper authTupleMapper;
    private final ShareLinkMapper shareLinkMapper;

    @Value("${file.access-url-prefix}")
    private String accessUrlPrefix;

    private User getCurrentUser() {
        return CurrentUserSupport.getCurrentUser();
    }

    /**
     * 校验合集是否可被当前调用者访问，不可访问按"不存在"处理（不暴露存在性）
     * 未发布草稿与无权访问的私密合集对访客一律 404；具备管理权限的账号（后台）可见。
     */
    private PhotoCollection requireAccessibleCollection(Long collectionId) {
        PhotoCollection collection = collectionMapper.selectById(collectionId);
        if (!accessPolicy.canAccessCollection(getCurrentUser(), collection)) {
            throw new BusinessException(404, "合集不存在");
        }
        return collection;
    }

    /**
     * 取"当前调用者可管理的合集"，不可管理（含不可见）一律按不存在处理
     *
     * 写操作必须用这个，而不是 requireAccessibleCollection：后者对任何**已发布的公开合集**
     * 都返回 true（"能看到"不等于"能管理"）。用它做写入口的守卫，等于允许任何具备
     * 内容管理能力的账号对全站公开合集改名、改封面、下线、删除。
     */
    private PhotoCollection requireManageableCollection(Long collectionId) {
        PhotoCollection collection = collectionMapper.selectById(collectionId);
        if (collection == null || !accessPolicy.canManageCollection(getCurrentUser(), collection)) {
            throw new BusinessException(404, "合集不存在");
        }
        return collection;
    }

    @Override
    public List<CollectionDTO> getPublishedCollections() {
        LambdaQueryWrapper<PhotoCollection> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PhotoCollection::getIsPublished, 1);
        accessPolicy.applyCollectionFilter(wrapper, getCurrentUser());
        wrapper.orderByAsc(PhotoCollection::getSortOrder)
                .orderByDesc(PhotoCollection::getCreatedAt);

        List<PhotoCollection> collections = collectionMapper.selectList(wrapper);
        return toDTOList(collections);
    }

    @Override
    public CollectionDTO getCollectionDetail(Long id) {
        PhotoCollection collection = requireAccessibleCollection(id);
        CollectionDTO dto = toDTO(collection);
        dto.setPhotos(buildPhotos(collection));
        return dto;
    }

    @Override
    public List<PhotoDTO> getCollectionPhotos(Long collectionId) {
        // 合集自身不可访问时直接 404，避免未发布/私密合集内照片被枚举读取
        return buildPhotos(requireAccessibleCollection(collectionId));
    }

    /**
     * 组装合集内对当前调用者可见的照片
     *
     * 已持有合集对象，逐张判定由 AccessPolicy 在内存中完成（不产生额外查询），
     * 避免"每张私密照片各查一次权限表"的 N+1。
     */
    private List<PhotoDTO> buildPhotos(PhotoCollection collection) {
        LambdaQueryWrapper<PhotoCollectionPhoto> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PhotoCollectionPhoto::getCollectionId, collection.getId())
                .orderByAsc(PhotoCollectionPhoto::getSortOrder);

        List<PhotoCollectionPhoto> relations = collectionPhotoMapper.selectList(wrapper);
        User user = getCurrentUser();
        List<PhotoDTO> result = new ArrayList<>(relations.size());
        for (PhotoCollectionPhoto rel : relations) {
            Photo photo = photoMapper.selectById(rel.getPhotoId());
            if (photo != null && accessPolicy.canViewPhotoInCollection(user, photo, collection.getId())) {
                result.add(toPhotoDTO(photo));
            }
        }
        return result;
    }

    @Override
    public List<CollectionDTO> getAllCollections() {
        LambdaQueryWrapper<PhotoCollection> wrapper = new LambdaQueryWrapper<>();
        // 数据范围（全站 / 自己创建+被授予）− 禁止项，统一由策略判定：
        // 不再按"有无管理能力"放行，被 deny 的合集对任何账号都不出现
        accessPolicy.applyManagedCollectionFilter(wrapper, getCurrentUser());
        wrapper.orderByAsc(PhotoCollection::getSortOrder)
                .orderByDesc(PhotoCollection::getCreatedAt);
        return toDTOList(collectionMapper.selectList(wrapper));
    }

    @Override
    public List<Map<String, Object>> listMembers(Long collectionId) {
        // 与写入口同一判定：协作者本人可看（isMember 成立），其他人需要可管理权
        requireManageableCollection(collectionId);
        List<CollectionMember> members = memberMapper.selectList(
                new LambdaQueryWrapper<CollectionMember>()
                        .eq(CollectionMember::getCollectionId, collectionId)
                        .orderByAsc(CollectionMember::getId));
        List<Map<String, Object>> result = new ArrayList<>(members.size());
        for (CollectionMember member : members) {
            User memberUser = userMapper.selectById(member.getUserId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", member.getUserId());
            item.put("username", memberUser != null ? memberUser.getUsername() : null);
            item.put("nickname", memberUser != null ? memberUser.getNickname() : null);
            item.put("memberRole", member.getMemberRole());
            item.put("createdAt", member.getCreatedAt());
            result.add(item);
        }
        return result;
    }

    @Override
    @Transactional
    public void addMember(Long collectionId, Long userId) {
        // 对象级判定：只能给"自己可管理"的合集指派协作者。
        // 此前只判存在性，任何具备内容管理能力的账号都能把第三方塞进他人合集，
        // 而协作者身份会立刻通过 CollectionAccessGuard 授予该合集的全部写权限。
        requireManageableCollection(collectionId);
        if (userMapper.selectById(userId) == null) {
            throw new BusinessException(404, "用户不存在");
        }
        Long exists = memberMapper.selectCount(new LambdaQueryWrapper<CollectionMember>()
                .eq(CollectionMember::getCollectionId, collectionId)
                .eq(CollectionMember::getUserId, userId));
        if (exists != null && exists > 0) {
            return; // 幂等
        }
        CollectionMember member = new CollectionMember();
        member.setCollectionId(collectionId);
        member.setUserId(userId);
        member.setMemberRole("editor");
        User operator = getCurrentUser();
        member.setCreatedBy(operator != null ? operator.getId() : null);
        memberMapper.insert(member);
        log.info("合集协作者已指派: collectionId={}, userId={}", collectionId, userId);
    }

    @Override
    @Transactional
    public void removeMember(Long collectionId, Long userId) {
        // 与指派同样要求对象级管理权，避免把他人合集的合法协作者踢下线（拒绝服务）
        requireManageableCollection(collectionId);
        memberMapper.delete(new LambdaQueryWrapper<CollectionMember>()
                .eq(CollectionMember::getCollectionId, collectionId)
                .eq(CollectionMember::getUserId, userId));
        log.info("合集协作者已移除: collectionId={}, userId={}", collectionId, userId);
    }

    @Override
    @Transactional
    public void reorderCollections(List<Map<String, Object>> orderList) {
        if (orderList == null || orderList.isEmpty()) {
            return;
        }
        // 逐条校验管理权：排序是全局可见的改动，不能对他人合集下手
        for (Map<String, Object> item : orderList) {
            Long id = ((Number) item.get("id")).longValue();
            requireManageableCollection(id);
        }
        for (Map<String, Object> item : orderList) {
            Long id = ((Number) item.get("id")).longValue();
            Integer sortOrder = ((Number) item.get("sortOrder")).intValue();
            PhotoCollection col = new PhotoCollection();
            col.setId(id);
            col.setSortOrder(sortOrder);
            collectionMapper.updateById(col);
        }
    }

    @Override
    public Map<String, Object> getAdjacentInCollection(Long collectionId, Long photoId) {
        Map<String, Object> result = new HashMap<>();
        // 合集不可访问时直接拒绝，避免通过相邻关系探测未发布/私密合集的内容
        requireAccessibleCollection(collectionId);

        // 查合集内所有照片排序
        LambdaQueryWrapper<PhotoCollectionPhoto> wp = new LambdaQueryWrapper<>();
        wp.eq(PhotoCollectionPhoto::getCollectionId, collectionId)
                .orderByAsc(PhotoCollectionPhoto::getSortOrder);
        List<PhotoCollectionPhoto> relations = collectionPhotoMapper.selectList(wp);
        List<Long> orderedIds = relations.stream()
                .map(PhotoCollectionPhoto::getPhotoId)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());

        // 逐张做可见性判定：此前直接返回相邻 ID，沿链遍历即可把合集内
        // 对访问者不可见的私密照片 ID 全部收集出来（内容是 404 的，但 ID 泄漏了）
        List<Long> ids = new ArrayList<>();
        if (!orderedIds.isEmpty()) {
            Map<Long, Photo> photoById = photoMapper.selectBatchIds(orderedIds).stream()
                    .collect(Collectors.toMap(Photo::getId, photo -> photo));
            User user = getCurrentUser();
            for (Long id : orderedIds) {
                Photo photo = photoById.get(id);
                if (photo != null && accessPolicy.canViewPhotoInCollection(user, photo, collectionId)) {
                    ids.add(id);
                }
            }
        }

        int idx = ids.indexOf(photoId);
        result.put("prevId", idx > 0 ? ids.get(idx - 1) : null);
        result.put("nextId", idx >= 0 && idx < ids.size() - 1 ? ids.get(idx + 1) : null);
        return result;
    }

    @Override
    @Transactional
    public CollectionDTO createCollection(CollectionDTO dto) {
        PhotoCollection collection = new PhotoCollection();
        collection.setName(dto.getName());
        collection.setDescription(dto.getDescription());
        collection.setCoverPhotoId(dto.getCoverPhotoId());
        collection.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);
        collection.setIsPublished(dto.getIsPublished() != null ? dto.getIsPublished() : 1);
        collection.setCreatedAt(LocalDateTime.now());
        collection.setUpdatedAt(LocalDateTime.now());
        User creator = getCurrentUser();
        if (creator != null) {
            collection.setCreatedBy(creator.getId());
        }
        collectionMapper.insert(collection);
        return toDTO(collection);
    }

    @Override
    @Transactional
    public CollectionDTO updateCollection(Long id, CollectionDTO dto) {
        // 资源级判定：不可管理即按"不存在"处理（避免通过响应差异推断资源存在性）
        PhotoCollection collection = requireManageableCollection(id);
        Integer previousPrivate = collection.getIsPrivate();
        if (dto.getName() != null) collection.setName(dto.getName());
        if (dto.getDescription() != null) collection.setDescription(dto.getDescription());
        if (dto.getCoverPhotoId() != null) collection.setCoverPhotoId(dto.getCoverPhotoId());
        if (dto.getSortOrder() != null) collection.setSortOrder(dto.getSortOrder());
        if (dto.getIsPublished() != null) collection.setIsPublished(dto.getIsPublished());
        // 私密标记仅管理员可设置（非管理员静默忽略，与照片编辑规则保持一致）
        if (dto.getIsPrivate() != null
                && com.photoalbum.common.UserRoles.isAdmin(
                        getCurrentUser() == null ? null : getCurrentUser().getRole())) {
            collection.setIsPrivate(dto.getIsPrivate());
        }
        collection.setUpdatedAt(LocalDateTime.now());
        collectionMapper.updateById(collection);

        // 合集被设为私密：合集内照片一并置为私密（单向传播，改回公开不自动放行）
        boolean becamePrivate = collection.getIsPrivate() != null && collection.getIsPrivate() == 1
                && (previousPrivate == null || previousPrivate != 1);
        if (becamePrivate) {
            int changed = markCollectionPhotosPrivate(collection.getId());
            log.info("合集 {} 设为私密，级联置私密照片 {} 张", collection.getId(), changed);
        }
        return toDTO(collection);
    }

    @Override
    @Transactional
    public void deleteCollection(Long id) {
        // 资源级判定：不可管理即按"不存在"处理
        requireManageableCollection(id);
        // 派生关系一并清理：这些表没有外键兜底，合集删除后残留会变成
        // "幽灵协作者 / 幽灵授权 / 死分享"，一旦 id 复用就会作用到新合集上
        memberMapper.delete(new LambdaQueryWrapper<CollectionMember>()
                .eq(CollectionMember::getCollectionId, id));
        authTupleMapper.delete(new LambdaQueryWrapper<AuthTuple>()
                .eq(AuthTuple::getObjectType, PermissionConstants.TARGET_COLLECTION)
                .eq(AuthTuple::getObjectId, id));
        shareLinkMapper.delete(new LambdaQueryWrapper<ShareLink>()
                .eq(ShareLink::getTargetType, "collection")
                .eq(ShareLink::getTargetId, id));
        LambdaQueryWrapper<PhotoCollectionPhoto> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PhotoCollectionPhoto::getCollectionId, id);
        collectionPhotoMapper.delete(wrapper);
        collectionMapper.deleteById(id);
    }

    @Override
    @Transactional
    public void addPhoto(Long collectionId, Long photoId) {
        // 资源级判定：不可管理即不可写（合集与照片都要在调用者可见范围内）
        PhotoCollection collection = requireManageableCollection(collectionId);
        com.photoalbum.entity.Photo target = photoMapper.selectById(photoId);
        if (target == null || !accessPolicy.canViewPhoto(getCurrentUser(), target)) {
            throw new BusinessException(404, "照片不存在");
        }
        LambdaQueryWrapper<PhotoCollectionPhoto> qw = new LambdaQueryWrapper<>();
        qw.eq(PhotoCollectionPhoto::getCollectionId, collectionId)
          .eq(PhotoCollectionPhoto::getPhotoId, photoId)
          .last("LIMIT 1");
        if (collectionPhotoMapper.selectOne(qw) != null) {
            return;
        }
        PhotoCollectionPhoto relation = new PhotoCollectionPhoto();
        relation.setCollectionId(collectionId);
        relation.setPhotoId(photoId);
        relation.setSortOrder(0);
        collectionPhotoMapper.insert(relation);

        // 私密合集：新加入的照片一并置为私密
        if (collection.getIsPrivate() != null && collection.getIsPrivate() == 1) {
            markPhotoPrivate(photoId);
        }
    }

    @Override
    @Transactional
    public void removePhoto(Long collectionId, Long photoId) {
        // 资源级判定：不可管理即不可写
        requireManageableCollection(collectionId);
        LambdaQueryWrapper<PhotoCollectionPhoto> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PhotoCollectionPhoto::getCollectionId, collectionId)
                .eq(PhotoCollectionPhoto::getPhotoId, photoId);
        collectionPhotoMapper.delete(wrapper);
    }

    /** 单张照片置为私密（跳过已是私密的），同时把 COS 对象 ACL 置私有 */
    private void markPhotoPrivate(Long photoId) {
        Photo photo = photoMapper.selectById(photoId);
        if (photo == null || (photo.getIsPrivate() != null && photo.getIsPrivate() == 1)) {
            return;
        }
        Photo update = new Photo();
        update.setId(photo.getId());
        update.setIsPrivate(1);
        photoMapper.updateById(update);
        photoUrlResolver.applyObjectAcl(photo.getUrl(), 1);
        photoUrlResolver.applyObjectAcl(photo.getThumbnailUrl(), 1);
    }

    /**
     * 私密合集：合集内照片一并置为私密。
     * 仅单向传播——合集改回公开时不自动放行照片，避免误公开原本单独设为私密的照片。
     */
    private int markCollectionPhotosPrivate(Long collectionId) {
        int changed = 0;
        for (PhotoCollectionPhoto rel : collectionPhotoMapper.selectList(
                new LambdaQueryWrapper<PhotoCollectionPhoto>()
                        .eq(PhotoCollectionPhoto::getCollectionId, collectionId))) {
            Photo before = photoMapper.selectById(rel.getPhotoId());
            if (before != null && (before.getIsPrivate() == null || before.getIsPrivate() != 1)) {
                markPhotoPrivate(rel.getPhotoId());
                changed++;
            }
        }
        return changed;
    }

    /**
     * 合集封面地址：优先缩略图、其次原图；私密照片由解析器签发短期地址。
     *
     * 注意：不能直接使用库中存储的直链——私密照片的对象 ACL 为私有，
     * 直链会返回 403，表现为封面裂图（本方法即为此前的缺口）。
     */
    private String coverUrlOf(Photo photo) {
        if (photo == null) {
            return null;
        }
        String thumb = photoUrlResolver.resolveThumbnail(photo.getThumbnailUrl(), photo.getIsPrivate());
        return thumb != null ? thumb : photoUrlResolver.resolve(photo.getUrl(), photo.getIsPrivate());
    }

    /**
     * 封面地址：优先用显式指定的封面照片，其次用合集内排序最靠前的一张**可见**照片。
     *
     * 安全要求（修复 S01）：封面地址由 PhotoUrlResolver 签发，而解析器对私密照片会生成短期预签名地址，
     * 因此**必须先做可见性判定再输出**。此前的实现只取“封面照片或合集内排序第一张”的序号、
     * 不判可见性，导致匿名访问者请求公开合集列表时即可拿到私密照片的可用预签名地址。
     * 两张都不可见时返回 null，由前端回退站点默认图。
     */
    private String coverUrlOf(PhotoCollection collection) {
        User user = getCurrentUser();

        if (collection.getCoverPhotoId() != null) {
            Photo cover = photoMapper.selectById(collection.getCoverPhotoId());
            if (cover != null && accessPolicy.canViewPhoto(user, cover)) {
                return coverUrlOf(cover);
            }
        }

        Photo first = firstVisiblePhotoIn(collection.getId(), user);
        return first == null ? null : coverUrlOf(first);
    }

    /**
     * 合集内对调用者可见、且按合集排序最靠前的一张照片
     *
     * 可见性条件复用 AccessPolicy（与照片列表、合集详情同一口径），
     * 排序依据在关联表上，需要相关子查询，故用 last() 追加一条 ORDER BY。
     * 子查询内引用的 t_photo 即主表名（与 AccessPolicy 注入条件所用别名一致），
     * 全程单次查询，不按合集内照片数逐张加载。
     */
    private Photo firstVisiblePhotoIn(Long collectionId, User user) {
        if (collectionId == null) {
            return null;
        }
        LambdaQueryWrapper<Photo> wrapper = new LambdaQueryWrapper<Photo>()
                .inSql(Photo::getId, "SELECT photo_id FROM t_collection_photos WHERE collection_id = " + collectionId);
        accessPolicy.applyPhotoFilter(wrapper, user);
        wrapper.last("ORDER BY (SELECT MIN(cp.sort_order) FROM t_collection_photos cp"
                + " WHERE cp.photo_id = t_photo.id) ASC LIMIT 1");
        return photoMapper.selectOne(wrapper);
    }

    private List<CollectionDTO> toDTOList(List<PhotoCollection> collections) {
        if (collections == null || collections.isEmpty()) {
            return List.of();
        }
        List<Long> ids = collections.stream().map(PhotoCollection::getId).collect(Collectors.toList());
        // 照片数一次查完；显式封面一次查完。此前是"每个合集 2~3 次查询"。
        Map<Long, Long> counts = countVisiblePhotosByCollection(ids);
        Map<Long, Photo> covers = batchLoadCoverPhotos(collections);
        return collections.stream()
                .map(collection -> toDTO(collection, counts.getOrDefault(collection.getId(), 0L), covers))
                .collect(Collectors.toList());
    }

    /** 批量统计各合集内"对调用者可见"的照片数（一次 GROUP BY） */
    private Map<Long, Long> countVisiblePhotosByCollection(List<Long> collectionIds) {
        List<Map<String, Object>> rows = collectionPhotoMapper.countVisibleByCollections(
                collectionIds, accessPolicy.visiblePhotoSql(getCurrentUser()));
        Map<Long, Long> counts = new HashMap<>();
        if (rows == null) {
            return counts;
        }
        for (Map<String, Object> row : rows) {
            Object collectionId = row.get("collectionId");
            Object count = row.get("cnt");
            if (collectionId instanceof Number id && count instanceof Number value) {
                counts.put(id.longValue(), value.longValue());
            }
        }
        return counts;
    }

    /** 一次查出所有显式封面照片（避免逐合集 selectById） */
    private Map<Long, Photo> batchLoadCoverPhotos(List<PhotoCollection> collections) {
        List<Long> coverIds = collections.stream()
                .map(PhotoCollection::getCoverPhotoId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (coverIds.isEmpty()) {
            return Map.of();
        }
        return photoMapper.selectBatchIds(coverIds).stream()
                .collect(Collectors.toMap(Photo::getId, photo -> photo, (a, b) -> a));
    }

    private CollectionDTO toDTO(PhotoCollection collection) {
        return toDTOList(List.of(collection)).get(0);
    }

    private CollectionDTO toDTO(PhotoCollection collection, long photoCount, Map<Long, Photo> covers) {
        User user = getCurrentUser();
        CollectionDTO dto = new CollectionDTO();
        dto.setId(collection.getId());
        dto.setName(collection.getName());
        dto.setDescription(collection.getDescription());
        dto.setSortOrder(collection.getSortOrder());
        dto.setIsPublished(collection.getIsPublished());
        dto.setIsPrivate(collection.getIsPrivate());
        // 统计照片数（与封面一样复用同一套可见性判定，避免暴露隐藏照片的数量）
        dto.setPhotoCount((int) photoCount);

        // 封面：显式封面仅在“该照片对调用者可见”时才对外输出。
        // coverPhotoId 与 coverUrl 一并收敛——否则仅凭 ID 即可确认某张私密照片的存在（修复 S01）
        Photo cover = collection.getCoverPhotoId() == null ? null : covers.get(collection.getCoverPhotoId());
        boolean coverVisible = cover != null && accessPolicy.canViewPhoto(user, cover);
        dto.setCoverPhotoId(coverVisible ? collection.getCoverPhotoId() : null);

        if (coverVisible) {
            dto.setCoverUrl(coverUrlOf(cover));
        } else {
            Photo first = firstVisiblePhotoIn(collection.getId(), user);
            dto.setCoverUrl(first == null ? null : coverUrlOf(first));
        }
        return dto;
    }

    private PhotoDTO toPhotoDTO(Photo photo) {
        PhotoDTO dto = new PhotoDTO();
        dto.setId(photo.getId());
        dto.setTitle(photo.getTitle());
        dto.setDescription(photo.getDescription());
        dto.setCategoryId(photo.getCategoryId());
        // 公开照片直链；私密照片短期预签名地址
        dto.setUrl(photoUrlResolver.resolve(photo.getUrl(), photo.getIsPrivate()));
        dto.setThumbnailUrl(photoUrlResolver.resolveThumbnail(photo.getThumbnailUrl(), photo.getIsPrivate()));
        dto.setFileName(photo.getFileName());
        dto.setFileSize(photo.getFileSize());
        dto.setTags(photo.getTags());
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
        if (photo.getCategoryId() != null) {
            Category cat = categoryMapper.selectById(photo.getCategoryId());
            if (cat != null) {
                dto.setCategoryName(cat.getName());
            }
        }
        return dto;
    }
}
