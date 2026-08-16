package com.photoalbum.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.photoalbum.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
