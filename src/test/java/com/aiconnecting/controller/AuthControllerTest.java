package com.aiconnecting.controller;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.dto.LoginRequest;
import com.aiconnecting.dto.LoginResponse;
import com.aiconnecting.entity.User;
import com.aiconnecting.service.UserService;
import com.aiconnecting.security.JwtAuthenticationFilter;
import com.aiconnecting.security.JwtUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserService userService;

    @MockBean
    private JwtUtils jwtUtils;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void login_success() throws Exception {
        LoginResponse response = LoginResponse.builder()
                .token("jwt-token")
                .id(1L)
                .username("admin")
                .nickname("Admin")
                .role("admin")
                .build();
        when(userService.login(any(LoginRequest.class))).thenReturn(response);

        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("password");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").value("jwt-token"))
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.role").value("admin"));
    }

    @Test
    void login_invalidCredentials() throws Exception {
        when(userService.login(any(LoginRequest.class)))
                .thenThrow(new BusinessException("用户名或密码错误"));

        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("wrong");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void login_disabledAccount() throws Exception {
        when(userService.login(any(LoginRequest.class)))
                .thenThrow(new BusinessException("账号已被禁用"));

        LoginRequest request = new LoginRequest();
        request.setUsername("disabled");
        request.setPassword("password");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("账号已被禁用"));
    }

    @Test
    void login_missingUsername() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setPassword("password");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_missingPassword() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_emptyBody() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_success() throws Exception {
        User user = User.builder()
                .id(2L)
                .username("newuser")
                .password("encoded")
                .nickname("New User")
                .email("new@example.com")
                .role("user")
                .build();
        when(userService.register(any())).thenReturn(user);

        String body = """
                {
                    "username": "newuser",
                    "password": "password123",
                    "nickname": "New User",
                    "email": "new@example.com",
                    "inviteCode": "TESTCODE"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value("newuser"))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    void register_duplicateUsername() throws Exception {
        when(userService.register(any()))
                .thenThrow(new BusinessException("用户名已存在"));

        String body = """
                {
                    "username": "admin",
                    "password": "password123",
                    "email": "test@example.com",
                    "inviteCode": "TESTCODE"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("用户名已存在"));
    }

    @Test
    void register_missingUsername() throws Exception {
        String body = """
                {
                    "password": "password123",
                    "email": "test@example.com"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_missingPassword() throws Exception {
        String body = """
                {
                    "username": "newuser",
                    "email": "test@example.com"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_missingEmail() throws Exception {
        String body = """
                {
                    "username": "newuser",
                    "password": "password123"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_missingInviteCode() throws Exception {
        String body = """
                {
                    "username": "newuser",
                    "password": "password123",
                    "email": "test@example.com"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_invalidEmail() throws Exception {
        String body = """
                {
                    "username": "newuser",
                    "password": "password123",
                    "email": "not-an-email",
                    "inviteCode": "TESTCODE"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
