package com.photoalbum.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.photoalbum.entity.PhotoCollectionPhoto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface PhotoCollectionPhotoMapper extends BaseMapper<PhotoCollectionPhoto> {

    /**
     * 批量统计"对当前调用者可见的照片"在各合集内的数量
     *
     * 合集列表此前是"每个合集查一次 COUNT"，N 个合集就是 N 次往返；
     * 这里改成一次 GROUP BY。
     *
     * ${cond} 由 AccessPolicy.visiblePhotoSql 生成，内容只来自数据库中的 Long 值与常量，
     * 不接受任何请求参数；用子查询保证 t_photo 是可见性条件的唯一来源表
     * （条件里的列名不带别名，若直接 JOIN 会出现 id 歧义）。
     */
    @Select("<script>"
            + "SELECT cp.collection_id AS collectionId, COUNT(*) AS cnt "
            + "FROM t_collection_photos cp "
            + "WHERE cp.collection_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "AND cp.photo_id IN (SELECT id FROM t_photo WHERE ${cond}) "
            + "GROUP BY cp.collection_id"
            + "</script>")
    List<Map<String, Object>> countVisibleByCollections(@Param("ids") List<Long> ids,
                                                        @Param("cond") String cond);
}
