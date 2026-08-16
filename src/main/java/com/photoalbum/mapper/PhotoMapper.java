package com.photoalbum.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.photoalbum.entity.Photo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PhotoMapper extends BaseMapper<Photo> {
}
