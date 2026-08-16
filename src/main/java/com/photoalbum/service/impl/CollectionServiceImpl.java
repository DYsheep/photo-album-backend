package com.photoalbum.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.photoalbum.common.BusinessException;
import com.photoalbum.dto.CollectionDTO;
import com.photoalbum.dto.PhotoDTO;
import com.photoalbum.entity.Category;
import com.photoalbum.entity.Photo;
import com.photoalbum.entity.PhotoCollection;
import com.photoalbum.entity.PhotoCollectionPhoto;
import com.photoalbum.entity.User;
import com.photoalbum.entity.UserPermission;
import com.photoalbum.mapper.CategoryMapper;
import com.photoalbum.mapper.PhotoCollectionMapper;
import com.photoalbum.mapper.PhotoCollectionPhotoMapper;
import com.photoalbum.mapper.PhotoMapper;
import com.photoalbum.mapper.UserPermissionMapper;
import com.photoalbum.service.CollectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionServiceImpl implements CollectionService {

    private final PhotoCollectionMapper collectionMapper;
    private final PhotoCollectionPhotoMapper collectionPhotoMapper;
    private final PhotoMapper photoMapper;
    private final CategoryMapper categoryMapper;
    private final UserPermissionMapper permMapper;

    @Value("${file.access-url-prefix}")
    private String accessUrlPrefix;

    private boolean isAdmin() {
        User user = getCurrentUser();
        return user != null && "admin".equals(user.getRole());
    }

    private boolean canViewPrivate() {
        User user = getCurrentUser();
        return user != null && ("admin".equals(user.getRole()) || "viewer".equals(user.getRole()));
    }

    private void applyCollectionPermission(LambdaQueryWrapper<PhotoCollection> wrapper) {
        if (!canViewPrivate()) { wrapper.eq(PhotoCollection::getIsPrivate, 0); return; }
        if (isAdmin()) return;
        User user = getCurrentUser();
        if (user == null) return;
        List<UserPermission> perms = permMapper.selectList(
            new LambdaQueryWrapper<UserPermission>().eq(UserPermission::getUserId, user.getId())
                .eq(UserPermission::getTargetType, "collection"));
        if (perms.isEmpty()) return;
        List<Long> whitelist = perms.stream().filter(p -> "W".equals(p.getPermType()))
                .map(UserPermission::getTargetId).collect(Collectors.toList());
        List<Long> blacklist = perms.stream().filter(p -> "B".equals(p.getPermType()))
                .map(UserPermission::getTargetId).collect(Collectors.toList());
        if (!whitelist.isEmpty()) {
            wrapper.and(w -> w.eq(PhotoCollection::getIsPrivate, 0).or().in(PhotoCollection::getId, whitelist));
        } else if (!blacklist.isEmpty()) {
            wrapper.and(w -> w.eq(PhotoCollection::getIsPrivate, 0)
                .or(w2 -> w2.eq(PhotoCollection::getIsPrivate, 1).notIn(PhotoCollection::getId, blacklist)));
        }
    }

    /** 检查某张照片是否被当前 viewer 的黑白名单阻止 */
    private boolean isPhotoBlockedByPermission(Long photoId) {
        User user = getCurrentUser();
        if (user == null) return false;
        List<UserPermission> perms = permMapper.selectList(
            new LambdaQueryWrapper<UserPermission>().eq(UserPermission::getUserId, user.getId())
                .eq(UserPermission::getTargetType, "photo"));
        boolean hasWhitelist = perms.stream().anyMatch(p -> "W".equals(p.getPermType()));
        if (hasWhitelist) {
            return perms.stream().noneMatch(p -> "W".equals(p.getPermType()) && p.getTargetId().equals(photoId));
        }
        return perms.stream().anyMatch(p -> "B".equals(p.getPermType()) && p.getTargetId().equals(photoId));
    }

    private User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        Object principal = auth.getPrincipal();
        if (principal instanceof User) return (User) principal;
        return null;
    }

    @Override
    public List<CollectionDTO> getPublishedCollections() {
        LambdaQueryWrapper<PhotoCollection> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PhotoCollection::getIsPublished, 1);
        applyCollectionPermission(wrapper);
        wrapper.orderByAsc(PhotoCollection::getSortOrder)
                .orderByDesc(PhotoCollection::getCreatedAt);

        List<PhotoCollection> collections = collectionMapper.selectList(wrapper);
        return collections.stream().map(this::toDTO).collect(Collectors.toList());
    }

    @Override
    public CollectionDTO getCollectionDetail(Long id) {
        PhotoCollection collection = collectionMapper.selectById(id);
        if (collection == null) {
            throw new BusinessException("合集不存在");
        }
        CollectionDTO dto = toDTO(collection);
        dto.setPhotos(getCollectionPhotos(id));
        return dto;
    }

    @Override
    public List<PhotoDTO> getCollectionPhotos(Long collectionId) {
        LambdaQueryWrapper<PhotoCollectionPhoto> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PhotoCollectionPhoto::getCollectionId, collectionId)
                .orderByAsc(PhotoCollectionPhoto::getSortOrder);

        List<PhotoCollectionPhoto> relations = collectionPhotoMapper.selectList(wrapper);
        return relations.stream().map(rel -> {
            Photo photo = photoMapper.selectById(rel.getPhotoId());
            if (photo == null) return null;
            if (!canViewPrivate() && photo.getIsPrivate() != null && photo.getIsPrivate() == 1) return null;
            if (canViewPrivate() && !isAdmin() && isPhotoBlockedByPermission(photo.getId())) return null;
            return toPhotoDTO(photo);
        }).filter(dto -> dto != null).collect(Collectors.toList());
    }

    @Override
    public List<CollectionDTO> getAllCollections() {
        LambdaQueryWrapper<PhotoCollection> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(PhotoCollection::getSortOrder)
                .orderByDesc(PhotoCollection::getCreatedAt);
        List<PhotoCollection> list = collectionMapper.selectList(wrapper);
        return list.stream().map(this::toDTO).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void reorderCollections(List<Map<String, Object>> orderList) {
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
        // 查合集内所有照片排序
        LambdaQueryWrapper<PhotoCollectionPhoto> wp = new LambdaQueryWrapper<>();
        wp.eq(PhotoCollectionPhoto::getCollectionId, collectionId)
                .orderByAsc(PhotoCollectionPhoto::getSortOrder);
        List<PhotoCollectionPhoto> relations = collectionPhotoMapper.selectList(wp);
        List<Long> ids = relations.stream().map(PhotoCollectionPhoto::getPhotoId).collect(Collectors.toList());
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
        collectionMapper.insert(collection);
        return toDTO(collection);
    }

    @Override
    @Transactional
    public CollectionDTO updateCollection(Long id, CollectionDTO dto) {
        PhotoCollection collection = collectionMapper.selectById(id);
        if (collection == null) {
            throw new BusinessException("合集不存在");
        }
        if (dto.getName() != null) collection.setName(dto.getName());
        if (dto.getDescription() != null) collection.setDescription(dto.getDescription());
        if (dto.getCoverPhotoId() != null) collection.setCoverPhotoId(dto.getCoverPhotoId());
        if (dto.getSortOrder() != null) collection.setSortOrder(dto.getSortOrder());
        if (dto.getIsPublished() != null) collection.setIsPublished(dto.getIsPublished());
        if (dto.getIsPrivate() != null) collection.setIsPrivate(dto.getIsPrivate());
        collection.setUpdatedAt(LocalDateTime.now());
        collectionMapper.updateById(collection);
        return toDTO(collection);
    }

    @Override
    @Transactional
    public void deleteCollection(Long id) {
        PhotoCollection collection = collectionMapper.selectById(id);
        if (collection == null) {
            throw new BusinessException("合集不存在");
        }
        LambdaQueryWrapper<PhotoCollectionPhoto> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PhotoCollectionPhoto::getCollectionId, id);
        collectionPhotoMapper.delete(wrapper);
        collectionMapper.deleteById(id);
    }

    @Override
    @Transactional
    public void addPhoto(Long collectionId, Long photoId) {
        PhotoCollection collection = collectionMapper.selectById(collectionId);
        if (collection == null) {
            throw new BusinessException("合集不存在");
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
    }

    @Override
    @Transactional
    public void removePhoto(Long collectionId, Long photoId) {
        LambdaQueryWrapper<PhotoCollectionPhoto> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PhotoCollectionPhoto::getCollectionId, collectionId)
                .eq(PhotoCollectionPhoto::getPhotoId, photoId);
        collectionPhotoMapper.delete(wrapper);
    }

    private CollectionDTO toDTO(PhotoCollection collection) {
        CollectionDTO dto = new CollectionDTO();
        dto.setId(collection.getId());
        dto.setName(collection.getName());
        dto.setDescription(collection.getDescription());
        dto.setCoverPhotoId(collection.getCoverPhotoId());
        dto.setSortOrder(collection.getSortOrder());
        dto.setIsPublished(collection.getIsPublished());
        dto.setIsPrivate(collection.getIsPrivate());
        // 统计照片数
        LambdaQueryWrapper<PhotoCollectionPhoto> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PhotoCollectionPhoto::getCollectionId, collection.getId());
        dto.setPhotoCount(collectionPhotoMapper.selectCount(wrapper).intValue());
        // 封面 URL（没设封面时用合集第一张照片）
        if (collection.getCoverPhotoId() != null) {
            Photo cover = photoMapper.selectById(collection.getCoverPhotoId());
            if (cover != null) {
                String u = cover.getThumbnailUrl();
                dto.setCoverUrl(u != null && u.startsWith("http") ? u : accessUrlPrefix + u);
            }
        }
        if (dto.getCoverUrl() == null) {
            LambdaQueryWrapper<PhotoCollectionPhoto> firstPw = new LambdaQueryWrapper<>();
            firstPw.eq(PhotoCollectionPhoto::getCollectionId, collection.getId())
                    .orderByAsc(PhotoCollectionPhoto::getSortOrder).last("LIMIT 1");
            PhotoCollectionPhoto first = collectionPhotoMapper.selectOne(firstPw);
            if (first != null) {
                Photo firstPhoto = photoMapper.selectById(first.getPhotoId());
                if (firstPhoto != null) {
                    String u = firstPhoto.getThumbnailUrl();
                    dto.setCoverUrl(u != null && u.startsWith("http") ? u : accessUrlPrefix + u);
                }
            }
        }
        return dto;
    }

    private PhotoDTO toPhotoDTO(Photo photo) {
        PhotoDTO dto = new PhotoDTO();
        dto.setId(photo.getId());
        dto.setTitle(photo.getTitle());
        dto.setDescription(photo.getDescription());
        dto.setCategoryId(photo.getCategoryId());
        String url = photo.getUrl();
        dto.setUrl(url != null && url.startsWith("http") ? url : accessUrlPrefix + url);
        String thumbUrl = photo.getThumbnailUrl();
        dto.setThumbnailUrl(thumbUrl != null && !thumbUrl.isEmpty()
                ? (thumbUrl.startsWith("http") ? thumbUrl : accessUrlPrefix + thumbUrl) : null);
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
