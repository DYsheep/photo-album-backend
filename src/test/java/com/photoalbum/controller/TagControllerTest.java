package com.photoalbum.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.photoalbum.common.BusinessException;
import com.photoalbum.entity.User;
import com.photoalbum.security.UserAuthorities;
import com.photoalbum.service.TagService;
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
 * TagController 集成测试
 *
 * 标签的读取/重命名/合并/删除已下沉到 TagService（关联表实现），
 * 控制器只负责参数整理与错误映射，因此这里 mock 掉 TagService 断言接口契约。
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
    private TagService tagService;

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

    @Test
    @DisplayName("GET /api/admin/tags 返回标签及引用计数")
    void shouldReturnTagListWithCounts() throws Exception {
        when(tagService.listWithCounts()).thenReturn(List.of(
                Map.of("name", "日出", "count", 2L),
                Map.of("name", "风景", "count", 1L)));

        mockMvc.perform(get("/api/admin/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("日出"));

        verify(tagService).listWithCounts();
    }

    @Test
    @DisplayName("PUT /api/admin/tags/rename 透传新旧标签名")
    void shouldRenameTag() throws Exception {
        mockMvc.perform(put("/api/admin/tags/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("oldName", "日出", "newName", "朝阳"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(tagService).rename("日出", "朝阳");
    }

    @Test
    @DisplayName("PUT /api/admin/tags/rename 目标已存在时返回业务错误")
    void shouldReturnErrorWhenRenameConflict() throws Exception {
        doThrow(new BusinessException(400, "目标标签已存在，请使用合并"))
                .when(tagService).rename(anyString(), anyString());

        mockMvc.perform(put("/api/admin/tags/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("oldName", "a", "newName", "b"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("DELETE /api/admin/tags/{name} 删除标签")
    void shouldDeleteTag() throws Exception {
        mockMvc.perform(delete("/api/admin/tags/风景"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(tagService).deleteTag("风景");
    }

    @Test
    @DisplayName("POST /api/admin/tags/merge 合并标签")
    void shouldMergeTags() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceNames", Arrays.asList("日出", "朝阳"));
        body.put("targetName", "晨光");

        mockMvc.perform(post("/api/admin/tags/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(tagService).merge(eq(Arrays.asList("日出", "朝阳")), eq("晨光"));
    }

    @Test
    @DisplayName("POST /api/admin/tags/merge 未选择来源标签时返回失败")
    void shouldFailWhenMergeSourcesMissing() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceNames", Collections.emptyList());
        body.put("targetName", "晨光");

        mockMvc.perform(post("/api/admin/tags/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("请选择要合并的标签"));

        verify(tagService, never()).merge(anyList(), anyString());
    }

    @Test
    @DisplayName("POST /api/admin/tags/rebuild 重建标签索引")
    void shouldRebuildTagIndex() throws Exception {
        when(tagService.rebuildIndex()).thenReturn(12);

        mockMvc.perform(post("/api/admin/tags/rebuild"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.rebuilt").value(12));

        verify(tagService).rebuildIndex();
    }
}
