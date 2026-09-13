package com.photoalbum.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.photoalbum.dto.PhotoDTO;
import com.photoalbum.entity.Photo;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface PhotoService extends IService<Photo> {

    /**
     * 分页查询照片列表（支持关键词搜索 + 分类筛选）
     */
    Object getPhotoPage(PhotoDTO dto);

    /**
     * 上传图片
     */
    PhotoDTO upload(MultipartFile file, String title, Long categoryId, Long collectionId, String description, String tags, Integer isPrivate) throws Exception;

    /**
     * 修改照片信息
     */
    void updatePhoto(Long id, PhotoDTO dto);

    /**
     * 删除单张照片（同步删除磁盘文件）
     */
    void deletePhoto(Long id) throws Exception;

    /**
     * 批量删除照片
     */
    void batchDelete(List<Long> ids) throws Exception;

    /**
     * 仪表盘统计数据
     */
    Map<String, Object> getDashboardStats();

    /**
     * 获取标签列表（含引用计数）
     * 从所有 photos 的 tags 字段中拆分、去重、计数，按 count 降序排列
     */
    List<Map<String, Object>> getTagList();

    /**
     * 批量更新照片（修改分类 + 追加标签）
     */
    void batchUpdate(List<Long> ids, Long categoryId, String appendTags);

    /**
     * 获取所有有 GPS 坐标的照片
     */
    List<PhotoDTO> getGpsPhotos();

    /**
     * 获取指定照片的相邻照片 ID（上一张/下一张）
     * @return Map 包含 prevId / nextId（可能为 null）
     */
    Map<String, Object> getAdjacentIds(Long id);

    /**
     * 按可见性查询单张照片（详情接口使用）
     *
     * 与列表接口使用同一套可见性规则：未登录与普通用户仅可见公开照片，
     * viewer 角色按白名单/黑名单过滤，admin 全量可见。
     * 无权限访问私密照片时返回 null（调用方应返回 404，避免暴露资源存在性）。
     */
    Photo getVisiblePhoto(Long id);

    /** Entity → DTO 转换，供 Controller 复用 */
    PhotoDTO toDTO(Photo photo);

    /** 点赞照片，返回最新点赞数（原子自增，避免并发丢更新） */
    int likePhoto(Long id);

    /** 浏览量 +1，返回最新浏览量（原子自增，避免并发丢更新） */
    int incrementViewCount(Long id);

    /**
     * 修复存量私密照片的对象 ACL（一次性维护动作，返回处理数量）
     */
    int repairPrivateAcl();
}
