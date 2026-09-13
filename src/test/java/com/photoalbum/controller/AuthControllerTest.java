package com.photoalbum.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.photoalbum.common.Result;
import com.photoalbum.dto.LoginDTO;
import com.photoalbum.entity.User;
import com.photoalbum.security.JwtUtil;
import com.photoalbum.service.UserService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AuthController 集成测试
 * 使用 @SpringBootTest + H2 测试环境 + MockMvc，关闭 Security Filter 以隔离测试 Controller 逻辑
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@DisplayName("AuthController 集成测试")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserService userService;

    @MockBean
    private PasswordEncoder passwordEncoder;

    @MockBean
    private JwtUtil jwtUtil;

    // ============================================================
    // POST /api/auth/login 测试
    // ============================================================

    @Nested
    @DisplayName("POST /api/auth/login - 登录")
    class LoginTests {

        @Test
        @DisplayName("正确用户名密码，应返回 token 和用户信息")
        void shouldLoginSuccessfully() throws Exception {
            LoginDTO loginDTO = new LoginDTO();
            loginDTO.setUsername("admin");
            loginDTO.setPassword("admin123");

            User user = buildUser(1L, "admin", "管理员", "ROLE_ADMIN");
            String token = "eyJhbGciOiJIUzI1NiJ9.test-token";

            when(userService.findByUsername("admin")).thenReturn(user);
            when(passwordEncoder.matches("admin123", user.getPassword())).thenReturn(true);
            when(jwtUtil.generateToken("admin", 1L)).thenReturn(token);

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginDTO)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.token").value(token))
                    .andExpect(jsonPath("$.data.userInfo.id").value(1))
                    .andExpect(jsonPath("$.data.userInfo.username").value("admin"))
                    .andExpect(jsonPath("$.data.userInfo.nickname").value("管理员"))
                    .andExpect(jsonPath("$.data.userInfo.role").value("ROLE_ADMIN"))
                    .andExpect(jsonPath("$.data.userInfo.password").doesNotExist());

            verify(userService).findByUsername("admin");
            verify(passwordEncoder).matches("admin123", user.getPassword());
            verify(jwtUtil).generateToken("admin", 1L);
        }

        @Test
        @DisplayName("错误密码，应返回失败消息")
        void shouldFailWithWrongPassword() throws Exception {
            LoginDTO loginDTO = new LoginDTO();
            loginDTO.setUsername("admin");
            loginDTO.setPassword("wrongpass");

            User user = buildUser(1L, "admin", "管理员", "ROLE_ADMIN");

            when(userService.findByUsername("admin")).thenReturn(user);
            when(passwordEncoder.matches("wrongpass", user.getPassword())).thenReturn(false);

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginDTO)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.message").value("用户名或密码错误"))
                    .andExpect(jsonPath("$.data").isEmpty());

            verify(jwtUtil, never()).generateToken(anyString(), anyLong());
        }

        @Test
        @DisplayName("用户不存在，应返回失败消息")
        void shouldFailWhenUserNotFound() throws Exception {
            LoginDTO loginDTO = new LoginDTO();
            loginDTO.setUsername("nonexistent");
            loginDTO.setPassword("anypass");

            when(userService.findByUsername("nonexistent")).thenReturn(null);

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginDTO)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.message").value("用户名或密码错误"));

            verify(passwordEncoder, never()).matches(anyString(), anyString());
            verify(jwtUtil, never()).generateToken(anyString(), anyLong());
        }

        @Test
        @DisplayName("用户名为空（@Valid 校验），应返回 400")
        void shouldRejectBlankUsername() throws Exception {
            LoginDTO loginDTO = new LoginDTO();
            loginDTO.setUsername("");
            loginDTO.setPassword("admin123");

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginDTO)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
        }

        @Test
        @DisplayName("密码为空（@Valid 校验），应返回 400")
        void shouldRejectBlankPassword() throws Exception {
            LoginDTO loginDTO = new LoginDTO();
            loginDTO.setUsername("admin");
            loginDTO.setPassword("");

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginDTO)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
        }
    }

    // ============================================================
    // POST /api/auth/logout 测试
    // ============================================================

    @Nested
    @DisplayName("POST /api/auth/logout - 退出登录")
    class LogoutTests {

        @Test
        @DisplayName("退出登录总是成功")
        void shouldAlwaysSucceed() throws Exception {
            mockMvc.perform(post("/api/auth/logout"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("操作成功"));
        }
    }

    // ============================================================
    // GET /api/auth/userinfo 测试
    // ============================================================

    @Nested
    @DisplayName("GET /api/auth/userinfo - 获取用户信息")
    class UserInfoTests {

        @Test
        @DisplayName("已认证用户请求，应返回用户信息（不含密码）")
        void shouldReturnUserInfoWhenAuthenticated() throws Exception {
            User user = buildUser(2L, "photographer", "摄影师", "ROLE_USER");
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(user, null, null);
            SecurityContextHolder.getContext().setAuthentication(auth);

            try {
                mockMvc.perform(get("/api/auth/userinfo"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value(200))
                        .andExpect(jsonPath("$.data.id").value(2))
                        .andExpect(jsonPath("$.data.username").value("photographer"))
                        .andExpect(jsonPath("$.data.nickname").value("摄影师"))
                        .andExpect(jsonPath("$.data.role").value("ROLE_USER"))
                        .andExpect(jsonPath("$.data.password").doesNotExist());
            } finally {
                SecurityContextHolder.clearContext();
            }
        }

        @Test
        @DisplayName("未认证请求，应返回 401（未登录）")
        void shouldFailWhenNotAuthenticated() throws Exception {
            SecurityContextHolder.clearContext();

            mockMvc.perform(get("/api/auth/userinfo"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(401))
                    .andExpect(jsonPath("$.message").value("未登录"));
        }
    }

    // ============================================================
    // 响应格式验证
    // ============================================================

    @Nested
    @DisplayName("响应格式验证")
    class ResponseFormatTests {

        @Test
        @DisplayName("成功响应的 Result 结构应为 { code:200, message, data }")
        void shouldHaveCorrectSuccessFormat() throws Exception {
            LoginDTO loginDTO = new LoginDTO();
            loginDTO.setUsername("user");
            loginDTO.setPassword("pass");

            User user = buildUser(1L, "user", "用户", "ROLE_USER");
            when(userService.findByUsername("user")).thenReturn(user);
            when(passwordEncoder.matches("pass", user.getPassword())).thenReturn(true);
            when(jwtUtil.generateToken(anyString(), anyLong())).thenReturn("test-token");

            String responseBody = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginDTO)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            @SuppressWarnings("unchecked")
            Result<?> result = objectMapper.readValue(responseBody, Result.class);
            assertTrue(result.getCode() == 200);
            assertNotNull(result.getMessage());
            assertNotNull(result.getData());
        }
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private User buildUser(Long id, String username, String nickname, String role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword("$2a$10$hashedpasswordvalueherexxxxx");
        user.setNickname(nickname);
        user.setAvatar("https://example.com/avatar.png");
        user.setRole(role);
        return user;
    }
}
