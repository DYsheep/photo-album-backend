package com.photoalbum.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.photoalbum.entity.Category;
import com.photoalbum.mapper.CategoryMapper;
import com.photoalbum.service.CategoryService;
import org.springframework.stereotype.Service;

@Service
public class CategoryServiceImpl extends ServiceImpl<CategoryMapper, Category> implements CategoryService {
}
