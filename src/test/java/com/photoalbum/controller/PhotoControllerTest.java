package com.photoalbum.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.photoalbum.entity.User;
import com.photoalbum.mapper.CategoryMapper;
import com.photoalbum.security.UserAuthorities;
import com.photoalbum.service.PhotoService;
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

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** 以管理员身份登录（需管理权限的接口测试前置） */
    private void loginAsAdmin() {
        login("admin", 1, 1);
    }

    /** 以普通已登录用户身份登录（无上传/管理权限） */
    private void loginAsUser() {
        login("user", 0, 0);
    }

    /**
     * 模拟登录：权限标记由 UserAuthorities 真实派生，与认证过滤器保持一致
     */
    private void login(String role, Integer canUpload, Integer canManage) {
        User user = new User();
        user.setId(1L);
        user.setUsername("tester");
        user.setRole(role);
        user.setCanUpload(canUpload);
        user.setCanManage(canManage);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, UserAuthorities.of(user)));
    }

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
            loginAsAdmin();
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
            loginAsAdmin();
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

        @Test
        @DisplayName("未登录调用批量编辑，应返回 401 且不执行更新")
        void shouldRejectBatchUpdateWhenNotLoggedIn() throws Exception {
            SecurityContextHolder.clearContext();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ids", Arrays.asList(1, 2));
            body.put("categoryId", 5);

            mockMvc.perform(put("/api/photos/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(401));

            verify(photoService, never()).batchUpdate(anyList(), any(), any());
        }

        @Test
        @DisplayName("无管理权限的已登录用户调用批量编辑，应返回 403 且不执行更新")
        void shouldRejectBatchUpdateWithoutManagePermission() throws Exception {
            loginAsUser();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ids", Arrays.asList(1, 2));
            body.put("categoryId", 5);

            mockMvc.perform(put("/api/photos/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));

            verify(photoService, never()).batchUpdate(anyList(), any(), any());
        }
    }

    // ============================================================
    // DELETE /api/photos/{id} - 删除权限校验
    // ============================================================

    @Nested
    @DisplayName("DELETE /api/photos/{id} - 删除权限校验")
    class DeletePermissionTests {

        @Test
        @DisplayName("管理员删除照片，应成功")
        void shouldAllowAdminToDelete() throws Exception {
            loginAsAdmin();
            doNothing().when(photoService).deletePhoto(1L);

            mockMvc.perform(delete("/api/photos/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            verify(photoService).deletePhoto(1L);
        }

        @Test
        @DisplayName("未登录调用删除接口，应返回 401 且不执行删除")
        void shouldRejectDeleteWhenNotLoggedIn() throws Exception {
            SecurityContextHolder.clearContext();

            mockMvc.perform(delete("/api/photos/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(401));

            verify(photoService, never()).deletePhoto(anyLong());
        }

        @Test
        @DisplayName("无管理权限的已登录用户调用删除接口，应返回 403 且不执行删除")
        void shouldRejectDeleteWithoutManagePermission() throws Exception {
            loginAsUser();

            mockMvc.perform(delete("/api/photos/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));

            verify(photoService, never()).deletePhoto(anyLong());
        }

        @Test
        @DisplayName("无管理权限的已登录用户调用批量删除接口，应返回 403 且不执行删除")
        void shouldRejectBatchDeleteWithoutManagePermission() throws Exception {
            loginAsUser();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ids", Arrays.asList(1, 2, 3));

            mockMvc.perform(delete("/api/photos/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));

            verify(photoService, never()).batchDelete(anyList());
        }
    }
}
