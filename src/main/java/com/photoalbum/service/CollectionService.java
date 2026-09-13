package com.photoalbum.service;

import com.photoalbum.dto.CollectionDTO;
import com.photoalbum.dto.PhotoDTO;

import java.util.List;
import java.util.Map;

/**
 * 合集管理服务接口
 */
public interface CollectionService {

    /**
     * 获取已发布合集列表（前台）
     */
    List<CollectionDTO> getPublishedCollections();

    /**
     * 获取合集详情（含照片列表）
     * @param id 合集 ID
     * @return 合集信息 + 照片 DTO 列表
     */
    CollectionDTO getCollectionDetail(Long id);

    /**
     * 获取合集内的照片列表
     * @param collectionId 合集 ID
     * @return 照片 DTO 列表
     */
    List<PhotoDTO> getCollectionPhotos(Long collectionId);

    /**
     * 创建合集
     */
    CollectionDTO createCollection(CollectionDTO dto);

    /**
     * 更新合集
     */
    CollectionDTO updateCollection(Long id, CollectionDTO dto);

    /**
     * 删除合集
     */
    void deleteCollection(Long id);

    /**
     * 向合集添加照片
     */
    void addPhoto(Long collectionId, Long photoId);

    /**
     * 从合集移除照片
     */
    void removePhoto(Long collectionId, Long photoId);

    /**
     * 获取全部合集列表（后台管理用，含未发布）
     */
    List<CollectionDTO> getAllCollections();

    /** 批量更新合集排序 */
    void reorderCollections(List<Map<String, Object>> orderList);

    /** 获取合集内某张照片的相邻照片 ID */
    Map<String, Object> getAdjacentInCollection(Long collectionId, Long photoId);

    // ========== 协作者（对象级管理权） ==========

    /** 列出合集协作者（含用户名与昵称） */
    List<Map<String, Object>> listMembers(Long collectionId);

    /** 指派协作者（幂等） */
    void addMember(Long collectionId, Long userId);

    /** 移除协作者 */
    void removeMember(Long collectionId, Long userId);
}
