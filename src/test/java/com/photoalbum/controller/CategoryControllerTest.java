package com.photoalbum.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.photoalbum.dto.CategoryDTO;
import com.photoalbum.entity.Category;
import com.photoalbum.entity.User;
import com.photoalbum.mapper.PhotoMapper;
import com.photoalbum.security.UserAuthorities;
import com.photoalbum.service.CategoryService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CategoryController 集成测试
 * 使用 @SpringBootTest + H2 测试环境 + MockMvc
 *
 * 分类写接口需要 photo:manage 权限，因此测试以管理员身份执行（权限校验本身的用例见 PhotoControllerTest）。
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@DisplayName("CategoryController 集成测试")
class CategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CategoryService categoryService;

    @MockBean
    private PhotoMapper photoMapper;

    @BeforeEach
    void loginAsAdmin() {
        User user = new User();
        user.setId(1L);
        user.setUsername("tester");
        user.setRole("admin");
        user.setCanUpload(1);
        user.setCanManage(1);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, UserAuthorities.of(user)));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ============================================================
    // GET /api/categories 测试
    // ============================================================

    @Nested
    @DisplayName("GET /api/categories - 获取分类列表")
    class ListCategoriesTests {

        @Test
        @DisplayName("有分类数据时，应返回分类列表（按 sortOrder 排序）")
        void shouldReturnCategoryList() throws Exception {
            Category cat1 = buildCategory(1L, "风景", "自然风光", 1);
            Category cat2 = buildCategory(2L, "人像", "人物摄影", 2);
            Category cat3 = buildCategory(3L, "街拍", "街头摄影", 3);

            when(categoryService.list(any(LambdaQueryWrapper.class)))
                    .thenReturn(Arrays.asList(cat1, cat2, cat3));

            mockMvc.perform(get("/api/categories"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data", hasSize(3)))
                    .andExpect(jsonPath("$.data[0].name").value("风景"))
                    .andExpect(jsonPath("$.data[1].name").value("人像"))
                    .andExpect(jsonPath("$.data[2].name").value("街拍"));
        }

        @Test
        @DisplayName("无分类数据时，应返回空列表")
        void shouldReturnEmptyList() throws Exception {
            when(categoryService.list(any(LambdaQueryWrapper.class)))
                    .thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/categories"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data", hasSize(0)));
        }
    }

    // ============================================================
    // POST /api/categories 测试
    // ============================================================

    @Nested
    @DisplayName("POST /api/categories - 创建分类")
    class CreateCategoryTests {

        @Test
        @DisplayName("唯一名称创建分类，应成功并返回新分类")
        void shouldCreateCategoryWithUniqueName() throws Exception {
            CategoryDTO dto = new CategoryDTO();
            dto.setName("新分类");
            dto.setDescription("分类描述");
            dto.setSortOrder(5);

            when(categoryService.count(any(LambdaQueryWrapper.class))).thenReturn(0L);
            when(categoryService.save(any(Category.class))).thenReturn(true);

            mockMvc.perform(post("/api/categories")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.name").value("新分类"))
                    .andExpect(jsonPath("$.data.description").value("分类描述"))
                    .andExpect(jsonPath("$.data.sortOrder").value(5))
                    .andExpect(jsonPath("$.data.photoCount").value(0));

            verify(categoryService).save(any(Category.class));
        }

        @Test
        @DisplayName("名称已存在，应返回错误消息")
        void shouldRejectDuplicateName() throws Exception {
            CategoryDTO dto = new CategoryDTO();
            dto.setName("风景");
            dto.setSortOrder(0);

            when(categoryService.count(any(LambdaQueryWrapper.class))).thenReturn(1L);

            mockMvc.perform(post("/api/categories")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.message").value("分类名称已存在"));

            verify(categoryService, never()).save(any(Category.class));
        }

        @Test
        @DisplayName("名称为空（@Valid 校验），应返回 400")
        void shouldRejectBlankName() throws Exception {
            CategoryDTO dto = new CategoryDTO();
            dto.setName("");

            mockMvc.perform(post("/api/categories")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));

            verify(categoryService, never()).save(any());
        }
    }

    // ============================================================
    // PUT /api/categories/{id} 测试
    // ============================================================

    @Nested
    @DisplayName("PUT /api/categories/{id} - 修改分类")
    class UpdateCategoryTests {

        @Test
        @DisplayName("正常修改分类名称，应成功")
        void shouldUpdateCategorySuccessfully() throws Exception {
            Category existing = buildCategory(1L, "风景", "自然风光", 1);

            CategoryDTO dto = new CategoryDTO();
            dto.setName("自然风光");
            dto.setDescription("更新后的描述");
            dto.setSortOrder(2);

            when(categoryService.getById(1L)).thenReturn(existing);
            when(categoryService.count(any(LambdaQueryWrapper.class))).thenReturn(0L);
            when(categoryService.updateById(any(Category.class))).thenReturn(true);

            mockMvc.perform(put("/api/categories/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.name").value("自然风光"))
                    .andExpect(jsonPath("$.data.sortOrder").value(2));

            verify(categoryService).updateById(any(Category.class));
        }

        @Test
        @DisplayName("修改为已存在的名称，应返回错误")
        void shouldRejectUpdateToDuplicateName() throws Exception {
            Category existing = buildCategory(1L, "风景", "自然风光", 1);

            CategoryDTO dto = new CategoryDTO();
            dto.setName("人像");

            when(categoryService.getById(1L)).thenReturn(existing);
            when(categoryService.count(any(LambdaQueryWrapper.class))).thenReturn(1L);

            mockMvc.perform(put("/api/categories/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.message").value("分类名称已存在"));

            verify(categoryService, never()).updateById(any());
        }

        @Test
        @DisplayName("修改不存在的分类，应返回错误")
        void shouldFailToUpdateNonExistentCategory() throws Exception {
            CategoryDTO dto = new CategoryDTO();
            dto.setName("新名称");

            when(categoryService.getById(999L)).thenReturn(null);

            mockMvc.perform(put("/api/categories/999")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.message").value("分类不存在"));

            verify(categoryService, never()).updateById(any());
        }

        @Test
        @DisplayName("不修改名称（名称不变），不应触发唯一性校验")
        void shouldSkipUniquenessCheckWhenNameUnchanged() throws Exception {
            Category existing = buildCategory(1L, "风景", "自然风光", 1);

            CategoryDTO dto = new CategoryDTO();
            dto.setName("风景");
            dto.setSortOrder(3);

            when(categoryService.getById(1L)).thenReturn(existing);
            when(categoryService.updateById(any(Category.class))).thenReturn(true);

            mockMvc.perform(put("/api/categories/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            verify(categoryService, never()).count(any(LambdaQueryWrapper.class));
            verify(categoryService).updateById(any(Category.class));
        }
    }

    // ============================================================
    // DELETE /api/categories/{id} 测试
    // ============================================================

    @Nested
    @DisplayName("DELETE /api/categories/{id} - 删除分类")
    class DeleteCategoryTests {

        @Test
        @DisplayName("删除无关联照片的分类，应成功")
        void shouldDeleteEmptyCategory() throws Exception {
            Category category = buildCategory(1L, "空分类", "无照片", 0);

            when(categoryService.getById(1L)).thenReturn(category);
            when(photoMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
            when(categoryService.removeById(1L)).thenReturn(true);

            mockMvc.perform(delete("/api/categories/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            verify(categoryService).removeById(1L);
        }

        @Test
        @DisplayName("删除有关联照片的分类，应抛出 BusinessException")
        void shouldRejectDeletingCategoryWithPhotos() throws Exception {
            Category category = buildCategory(1L, "风景", "有 5 张照片", 1);

            when(categoryService.getById(1L)).thenReturn(category);
            when(photoMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5L);

            mockMvc.perform(delete("/api/categories/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.message", containsString("5 张照片")))
                    .andExpect(jsonPath("$.message", containsString("无法删除")));

            verify(categoryService, never()).removeById(anyLong());
        }

        @Test
        @DisplayName("删除不存在的分类，应返回错误")
        void shouldFailToDeleteNonExistentCategory() throws Exception {
            when(categoryService.getById(999L)).thenReturn(null);

            mockMvc.perform(delete("/api/categories/999"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.message").value("分类不存在"));

            verify(categoryService, never()).removeById(anyLong());
        }
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private Category buildCategory(Long id, String name, String description, int sortOrder) {
        Category category = new Category();
        category.setId(id);
        category.setName(name);
        category.setDescription(description);
        category.setSortOrder(sortOrder);
        category.setPhotoCount(0);
        category.setCreatedAt(LocalDateTime.now());
        category.setUpdatedAt(LocalDateTime.now());
        return category;
    }
}
