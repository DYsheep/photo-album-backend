package com.photoalbum.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 逆地理编码缓存
 *
 * 坐标键 = 纬度/经度各保留 2 位（约 1km），城市级归属足够，
 * 避免同一区域重复调用地图接口。
 */
@Data
@TableName("t_geo_cache")
public class GeoCache {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String coordKey;
    private String region;
    private Integer apiStatus;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
