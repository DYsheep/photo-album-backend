package com.photoalbum.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.photoalbum.entity.GeoCache;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GeoCacheMapper extends BaseMapper<GeoCache> {
}
