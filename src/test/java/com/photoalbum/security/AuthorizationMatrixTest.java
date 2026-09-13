package com.photoalbum.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.photoalbum.common.Result;
import com.photoalbum.entity.User;
import com.photoalbum.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 权限矩阵测试：端点 × 角色
 *
 * 目的：把"新增接口忘了加权限校验"这类问题交给自动化测试兜住，而不是依赖人工审查
 * （历史问题：单张删除/批量接口与分类写接口曾漏校验）。
 *
 * 做法：
 *   · 开启 Security 过滤器链（路径级规则）+ 方法级 @PreAuthorize 同时生效；
 *   · 用真实 JwtUtil 按角色签发令牌，认证过滤器按令牌解析出对应的模拟账号；
 *   · 控制器依赖的 Service 全部 mock，专注断言"授权结果"而非业务结果。
 *
 * 约定：新增任何需要授权的接口，必须在本文件的矩阵中登记一行。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("权限矩阵测试")
class AuthorizationMatrixTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @MockBean
    private UserService userService;
    @MockBean
    private PhotoService photoService;
    @MockBean
    private ShareService shareService;
    @MockBean
    private CategoryService categoryService;
    @MockBean
    private CollectionService collectionService;
    @MockBean
    private AuditService auditService;
    @MockBean
    private com.photoalbum.mapper.PhotoMapper photoMapper;

    /** 角色定义：匿名 / 普通用户 / 仅上传 / 仅管理 / 管理员 */
    private static final String ANON = "anon";
    private static final String USER = "user";
    private static final String UPLOADER = "uploader";
    private static final String MANAGER = "manager";
    private static final String ADMIN = "admin";

    private static final Set<String> ANY = Set.of(ANON, USER, UPLOADER, MANAGER, ADMIN);
    private static final Set<String> LOGGED_IN = Set.of(USER, UPLOADER, MANAGER, ADMIN);
    /** 上传需要"上传"能力位（与前端上传入口口径一致，管理者未勾选上传能力位时不能上传） */
    private static final Set<String> UPLOAD = Set.of(UPLOADER, ADMIN);
    private static final Set<String> UPLOAD_OR_MANAGE = Set.of(UPLOADER, MANAGER, ADMIN);
    private static final Set<String> MANAGE = Set.of(MANAGER, ADMIN);
    private static final Set<String> ADMIN_ONLY = Set.of(ADMIN);

    @BeforeEach
    void setUpMocks() {
        when(userService.findAll()).thenReturn(List.of());
        when(userService.getUserPermissions(anyLong())).thenReturn(List.of());
        when(userService.previewVisibility(anyLong())).thenReturn(Map.of());
        when(auditService.recent(anyInt())).thenReturn(List.of());
        when(photoMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
    }

    // ============================================================
    // 矩阵
    // ============================================================

    @Test
    @DisplayName("权限矩阵：每个写接口/管理接口仅对允许的角色放行")
    void shouldEnforceAuthorizationMatrix() throws Exception {
        List<Endpoint> endpoints = List.of(
                // 照片
                multipart("POST", "/api/photos/upload", UPLOAD),
                json("PUT", "/api/photos/1", "{}", MANAGE),
                json("DELETE", "/api/photos/1", null, MANAGE),
                json("DELETE", "/api/photos/batch", "{\"ids\":[1,2]}", MANAGE),
                json("PUT", "/api/photos/batch", "{\"ids\":[1]}", MANAGE),
                json("POST", "/api/photos/private-acl/repair", null, MANAGE),
                json("POST", "/api/photos/1/like", null, ANY),
                json("GET", "/api/photos/1", null, ANY),
                json("GET", "/api/photos/stats", null, ANY),
                // 分享
                json("POST", "/api/share/photo/1", "{}", UPLOAD_OR_MANAGE),
                json("GET", "/api/admin/share", null, MANAGE),
                json("DELETE", "/api/admin/share/1", null, MANAGE),
                // 分类
                json("POST", "/api/categories", "{\"name\":\"新分类\"}", MANAGE),
                json("PUT", "/api/categories/1", "{\"name\":\"改名\"}", MANAGE),
                json("DELETE", "/api/categories/1", null, MANAGE),
                // 标签
                json("GET", "/api/admin/tags", null, MANAGE),
                json("PUT", "/api/admin/tags/rename", "{\"oldName\":\"a\",\"newName\":\"b\"}", MANAGE),
                json("DELETE", "/api/admin/tags/风景", null, MANAGE),
                json("POST", "/api/admin/tags/merge", "{\"sourceNames\":[\"a\"],\"targetName\":\"b\"}", MANAGE),
                // 合集：列表接口登录即可访问，服务层按"协作者"关系过滤（对象级管理权）
                json("GET", "/api/admin/collections", null, LOGGED_IN),
                json("POST", "/api/admin/collections", "{\"name\":\"合集\"}", MANAGE),
                json("PUT", "/api/admin/collections/1", "{\"name\":\"合集\"}", MANAGE),
                json("DELETE", "/api/admin/collections/1", null, MANAGE),
                json("POST", "/api/admin/collections/1/photos/2", null, MANAGE),
                json("DELETE", "/api/admin/collections/1/photos/2", null, MANAGE),
                json("PUT", "/api/admin/collections/reorder", "[{\"id\":1,\"sortOrder\":0}]", MANAGE),
                // 用户与审计（仅管理员）
                json("GET", "/api/admin/users", null, ADMIN_ONLY),
                json("POST", "/api/admin/users", "{\"username\":\"u\",\"password\":\"p\"}", ADMIN_ONLY),
                json("PUT", "/api/admin/users/2", "{}", ADMIN_ONLY),
                json("DELETE", "/api/admin/users/2", null, ADMIN_ONLY),
                json("GET", "/api/admin/users/2/preview", null, ADMIN_ONLY),
                json("GET", "/api/admin/audit-logs", null, ADMIN_ONLY),
                json("GET", "/api/admin/users/2/permissions", null, ADMIN_ONLY),
                // 认证（登出为幂等无操作，匿名请求同样放行；登录接口不在本矩阵内）
                json("POST", "/api/auth/logout", null, ANY)
        );

        for (String role : List.of(ANON, USER, UPLOADER, MANAGER, ADMIN)) {
            String token = ANON.equals(role) ? null : tokenFor(role);
            for (Endpoint endpoint : endpoints) {
                boolean denied = isDenied(perform(endpoint, token));
                boolean shouldAllow = endpoint.allowedRoles().contains(role);
                assertThat(denied)
                        .as("%s %s 对角色[%s] 期望 %s，实际 %s",
                                endpoint.method(), endpoint.path(), role,
                                shouldAllow ? "放行" : "拒绝", denied ? "拒绝" : "放行")
                        .isEqualTo(!shouldAllow);
            }
        }
    }

    // ============================================================
    // 工具
    // ============================================================

    private record Endpoint(String method, String path, String body, boolean multipartFile,
                            Set<String> allowedRoles) {
    }

    private static Endpoint json(String method, String path, String body, Set<String> allowedRoles) {
        return new Endpoint(method, path, body, false, allowedRoles);
    }

    private static Endpoint multipart(String method, String path, Set<String> allowedRoles) {
        return new Endpoint(method, path, null, true, allowedRoles);
    }

    /** 按角色签发真实令牌，并让认证过滤器解析出对应的模拟账号 */
    private String tokenFor(String role) {
        User user = new User();
        user.setId(100L + role.length());
        user.setUsername(role);
        user.setRole("admin".equals(role) ? "admin" : "user");
        user.setCanUpload("uploader".equals(role) ? 1 : 0);
        user.setCanManage("manager".equals(role) ? 1 : 0);
        user.setCanViewPrivate(0);
        user.setTokenVersion(0);
        when(userService.findByUsername(role)).thenReturn(user);
        return jwtUtil.generateToken(user.getUsername(), user.getId(), user.getTokenVersion());
    }

    private MvcResult perform(Endpoint endpoint, String token) throws Exception {
        MockHttpServletRequestBuilder request;
        if (endpoint.multipartFile()) {
            request = MockMvcRequestBuilders.multipart(endpoint.path())
                    .file(new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[16]));
        } else {
            request = switch (endpoint.method()) {
                case "GET" -> MockMvcRequestBuilders.get(endpoint.path());
                case "POST" -> MockMvcRequestBuilders.post(endpoint.path());
                case "PUT" -> MockMvcRequestBuilders.put(endpoint.path());
                case "DELETE" -> MockMvcRequestBuilders.delete(endpoint.path());
                default -> throw new IllegalArgumentException("不支持的方法: " + endpoint.method());
            };
            if (endpoint.body() != null) {
                request.contentType(MediaType.APPLICATION_JSON).content(endpoint.body());
            }
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn();
    }

    /**
     * 判定请求是否被拒绝
     *
     * 兼容两种拒绝形态：过滤器链直接返回 401/403（无响应体），
     * 以及方法级鉴权被全局异常处理器转换为业务响应体（HTTP 200 + code 401/403）。
     */
    private boolean isDenied(MvcResult result) throws Exception {
        int status = result.getResponse().getStatus();
        if (status == 401 || status == 403) {
            return true;
        }
        String body = result.getResponse().getContentAsString();
        if (body == null || body.isBlank()) {
            return status >= 400;
        }
        Result<?> parsed = objectMapper.readValue(body, Result.class);
        return parsed.getCode() == 401 || parsed.getCode() == 403;
    }
}
