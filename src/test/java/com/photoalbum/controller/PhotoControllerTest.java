package com.photoalbum.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.photoalbum.mapper.CategoryMapper;
import com.photoalbum.service.PhotoService;
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
 * PhotoController 集成测试（新增端点）
 * 使用 @SpringBootTest + H2 测试环境 + MockMvc，关闭 Security Filter 以隔离测试 Controller 逻辑
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@DisplayName("PhotoController 集成测试")
class PhotoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PhotoService photoService;

    @MockBean
    private CategoryMapper categoryMapper;

    // ============================================================
    // GET /api/photos/tags - 标签列表
    // ============================================================

    @Nested
    @DisplayName("GET /api/photos/tags - 标签列表")
    class TagListTests {

        @Test
        @DisplayName("有标签数据时应返回 JSON 数组")
        void shouldReturnTagListAsJsonArray() throws Exception {
            Map<String, Object> tag1 = new LinkedHashMap<>();
            tag1.put("name", "风景");
            tag1.put("count", 5L);
            Map<String, Object> tag2 = new LinkedHashMap<>();
            tag2.put("name", "人像");
            tag2.put("count", 3L);

            List<Map<String, Object>> mockTags = Arrays.asList(tag1, tag2);
            when(photoService.getTagList()).thenReturn(mockTags);

            mockMvc.perform(get("/api/photos/tags"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].name").value("风景"))
                    .andExpect(jsonPath("$.data[0].count").value(5))
                    .andExpect(jsonPath("$.data[1].name").value("人像"))
                    .andExpect(jsonPath("$.data[1].count").value(3));

            verify(photoService).getTagList();
        }
    }

    // ============================================================
    // PUT /api/photos/batch - 批量编辑
    // ============================================================

    @Nested
    @DisplayName("PUT /api/photos/batch - 批量编辑")
    class BatchUpdateTests {

        @Test
        @DisplayName("批量编辑成功应返回 200")
        void shouldBatchUpdateSuccessfully() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ids", Arrays.asList(1, 2, 3));
            body.put("categoryId", 5);
            body.put("appendTags", "新标签,旅行");

            doNothing().when(photoService).batchUpdate(anyList(), eq(5L), eq("新标签,旅行"));

            mockMvc.perform(put("/api/photos/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            verify(photoService).batchUpdate(anyList(), eq(5L), eq("新标签,旅行"));
        }

        @Test
        @DisplayName("批量编辑参数缺失（ids 为空）时应返回错误")
        void shouldFailWhenIdsMissing() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ids", Collections.emptyList());
            body.put("categoryId", 5);

            mockMvc.perform(put("/api/photos/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.message").value("请选择要编辑的照片"));

            verify(photoService, never()).batchUpdate(anyList(), any(), any());
        }
    }
}
