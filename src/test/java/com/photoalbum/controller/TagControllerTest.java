package com.photoalbum.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.photoalbum.entity.Photo;
import com.photoalbum.mapper.PhotoMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * TagController 集成测试
 * 使用 @SpringBootTest + H2 测试环境 + MockMvc，关闭 Security Filter 以隔离测试 Controller 逻辑
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@DisplayName("TagController 集成测试")
class TagControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PhotoMapper photoMapper;

    // ============================================================
    // GET /api/admin/tags - 标签列表
    // ============================================================

    @Nested
    @DisplayName("GET /api/admin/tags - 标签列表")
    class ListTests {

        @Test
        @DisplayName("有标签时返回标签列表及引用计数，按 count 降序")
        void shouldReturnTagListWithCounts() throws Exception {
            // Arrange: 两张照片，"日出"出现2次，"风景"出现1次
            Photo p1 = buildPhoto(1L, "日出,风景");
            Photo p2 = buildPhoto(2L, "日出");
            when(photoMapper.selectList(isNull())).thenReturn(Arrays.asList(p1, p2));

            // Act & Assert
            mockMvc.perform(get("/api/admin/tags"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].name").value("日出"))
                    .andExpect(jsonPath("$.data[0].count").value(2))
                    .andExpect(jsonPath("$.data[1].name").value("风景"))
                    .andExpect(jsonPath("$.data[1].count").value(1));
        }

        @Test
        @DisplayName("无标签数据时返回空列表")
        void shouldReturnEmptyListWhenNoTags() throws Exception {
            when(photoMapper.selectList(isNull())).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/admin/tags"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }
    }

    // ============================================================
    // PUT /api/admin/tags/rename - 重命名标签
    // ============================================================

    @Nested
    @DisplayName("PUT /api/admin/tags/rename - 重命名标签")
    class RenameTests {

        @Test
        @DisplayName("重命名成功，匹配的照片标签被替换并更新")
        void shouldRenameTagSuccessfully() throws Exception {
            Photo photo = buildPhoto(1L, "日出,风景");
            when(photoMapper.selectList(isNull())).thenReturn(Arrays.asList(photo));
            when(photoMapper.updateById(any(Photo.class))).thenReturn(1);

            Map<String, String> body = new HashMap<>();
            body.put("oldName", "日出");
            body.put("newName", "sunrise");

            mockMvc.perform(put("/api/admin/tags/rename")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            verify(photoMapper).updateById(any(Photo.class));
        }

        @Test
        @DisplayName("参数缺失（缺少 newName）时返回错误")
        void shouldFailWhenParamsMissing() throws Exception {
            Map<String, String> body = new HashMap<>();
            body.put("oldName", "日出");
            // 缺少 newName

            mockMvc.perform(put("/api/admin/tags/rename")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.message").value("标签名不能为空"));
        }
    }

    // ============================================================
    // DELETE /api/admin/tags/{name} - 删除标签
    // ============================================================

    @Nested
    @DisplayName("DELETE /api/admin/tags/{name} - 删除标签")
    class DeleteTests {

        @Test
        @DisplayName("删除标签成功，从照片中移除该标签")
        void shouldDeleteTagSuccessfully() throws Exception {
            Photo photo = buildPhoto(1L, "sunrise,风景,日出");
            when(photoMapper.selectList(isNull())).thenReturn(Arrays.asList(photo));
            when(photoMapper.updateById(any(Photo.class))).thenReturn(1);

            mockMvc.perform(delete("/api/admin/tags/sunrise"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            verify(photoMapper).updateById(any(Photo.class));
        }

        @Test
        @DisplayName("标签名不存在时删除返回成功")
        void shouldDeleteNonExistentTag() throws Exception {
            // 删除不存在的标签，应正常返回（幂等操作）
            when(photoMapper.selectList(null)).thenReturn(List.of());
            mockMvc.perform(delete("/api/admin/tags/nonexistent"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    // ============================================================
    // POST /api/admin/tags/merge - 合并标签
    // ============================================================

    @Nested
    @DisplayName("POST /api/admin/tags/merge - 合并标签")
    class MergeTests {

        @Test
        @DisplayName("合并标签成功，源标签被替换为目标标签且去重")
        void shouldMergeTagsSuccessfully() throws Exception {
            Photo photo = buildPhoto(1L, "tag1,tag2,tag3");
            when(photoMapper.selectList(isNull())).thenReturn(Arrays.asList(photo));
            when(photoMapper.updateById(any(Photo.class))).thenReturn(1);

            Map<String, Object> body = new HashMap<>();
            body.put("sourceNames", Arrays.asList("tag1", "tag2"));
            body.put("targetName", "merged");

            mockMvc.perform(post("/api/admin/tags/merge")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            verify(photoMapper).updateById(any(Photo.class));
        }

        @Test
        @DisplayName("参数缺失（sourceNames 为空列表）时返回错误")
        void shouldFailWhenParamsMissing() throws Exception {
            Map<String, Object> body = new HashMap<>();
            body.put("sourceNames", Collections.emptyList());
            body.put("targetName", "merged");

            mockMvc.perform(post("/api/admin/tags/merge")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.message").value("源标签不能为空"));
        }
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private Photo buildPhoto(Long id, String tags) {
        Photo photo = new Photo();
        photo.setId(id);
        photo.setTitle("照片" + id);
        photo.setTags(tags);
        photo.setUrl("2026/05/photo" + id + ".jpg");
        photo.setFileName("photo" + id + ".jpg");
        return photo;
    }
}
