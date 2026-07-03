package com.aiconnecting.controller;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.dto.TokenRequest;
import com.aiconnecting.entity.Token;
import com.aiconnecting.entity.User;
import com.aiconnecting.repository.UsageLogRepository;
import com.aiconnecting.repository.UserRepository;
import com.aiconnecting.service.TokenService;
import com.aiconnecting.security.JwtAuthenticationFilter;
import com.aiconnecting.security.JwtUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TokenController.class)
@AutoConfigureMockMvc(addFilters = false)
class TokenControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TokenService tokenService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private UsageLogRepository usageLogRepository;

    @MockBean
    private JwtUtils jwtUtils;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    private User adminUser;
    private User regularUser;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();

        adminUser = User.builder()
                .id(1L).username("admin").password("encoded").role("admin")
                .credits(100.0).status(1).build();
        regularUser = User.builder()
                .id(2L).username("user").password("encoded").role("user")
                .credits(50.0).status(1).build();
    }

    private void setAuthentication(User user) {
        var auth = new UsernamePasswordAuthenticationToken(
                user, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().toUpperCase())));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ==================== List ====================

    @Test
    void list_asAdmin() throws Exception {
        setAuthentication(adminUser);

        Token t = Token.builder().id(1L).name("test-token").userId(2L).build();
        when(tokenService.listAll()).thenReturn(List.of(t));
        User owner = User.builder().id(2L).username("user").build();
        when(userRepository.findAllById(List.of(2L))).thenReturn(List.of(owner));

        mockMvc.perform(get("/api/tokens"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].name").value("test-token"))
                .andExpect(jsonPath("$.data[0].ownerName").value("user"));
    }

    @Test
    void list_asRegularUser() throws Exception {
        setAuthentication(regularUser);

        Token t = Token.builder().id(1L).name("my-token").userId(2L).build();
        when(tokenService.listByUser(2L)).thenReturn(List.of(t));
        when(userRepository.findAllById(List.of(2L))).thenReturn(List.of(regularUser));

        mockMvc.perform(get("/api/tokens"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("my-token"))
                .andExpect(jsonPath("$.data[0].ownerName").value("user"));
    }

    @Test
    void list_withSearch() throws Exception {
        setAuthentication(adminUser);

        Token t1 = Token.builder().id(1L).name("token1").userId(2L).build();
        Token t2 = Token.builder().id(2L).name("token2").userId(1L).build();
        when(tokenService.listAll()).thenReturn(List.of(t1, t2));
        User user2 = User.builder().id(2L).username("testuser").build();
        User user1 = User.builder().id(1L).username("admin").build();
        when(userRepository.findAllById(anyList())).thenReturn(List.of(user2, user1));

        mockMvc.perform(get("/api/tokens").param("search", "testuser"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].ownerName").value("testuser"));
    }

    @Test
    void list_empty() throws Exception {
        setAuthentication(regularUser);
        when(tokenService.listByUser(2L)).thenReturn(List.of());

        mockMvc.perform(get("/api/tokens"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    // ==================== GetById ====================

    @Test
    void getById() throws Exception {
        setAuthentication(regularUser);
        Token t = Token.builder().id(1L).name("my-token").build();
        when(tokenService.getById(1L)).thenReturn(t);

        mockMvc.perform(get("/api/tokens/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("my-token"));
    }

    @Test
    void getById_notFound() throws Exception {
        setAuthentication(regularUser);
        when(tokenService.getById(99L)).thenThrow(new BusinessException("Token 不存在"));

        mockMvc.perform(get("/api/tokens/99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Token 不存在"));
    }

    // ==================== Create ====================

    @Test
    void create() throws Exception {
        setAuthentication(regularUser);

        Token t = Token.builder().id(1L).name("new-token").tokenKey("sk-abc123").userId(2L).build();
        when(tokenService.create(eq(2L), any(TokenRequest.class))).thenReturn(t);

        String body = """
                {
                    "name": "new-token"
                }
                """;

        mockMvc.perform(post("/api/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("new-token"))
                .andExpect(jsonPath("$.data.tokenKey").value("sk-abc123"));
    }

    // ==================== Update ====================

    @Test
    void update() throws Exception {
        setAuthentication(regularUser);

        Token t = Token.builder().id(1L).name("updated-token").build();
        when(tokenService.update(eq(1L), any(TokenRequest.class))).thenReturn(t);

        mockMvc.perform(put("/api/tokens/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"updated-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("updated-token"));
    }

    @Test
    void update_notFound() throws Exception {
        setAuthentication(regularUser);
        when(tokenService.update(eq(99L), any(TokenRequest.class)))
                .thenThrow(new BusinessException("Token 不存在"));

        mockMvc.perform(put("/api/tokens/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"test\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Token 不存在"));
    }

    // ==================== Delete ====================

    @Test
    void deleteToken() throws Exception {
        setAuthentication(regularUser);
        doNothing().when(tokenService).delete(1L);

        mockMvc.perform(delete("/api/tokens/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void deleteToken_notFound() throws Exception {
        setAuthentication(regularUser);
        doThrow(new BusinessException("Token 不存在")).when(tokenService).delete(99L);

        mockMvc.perform(delete("/api/tokens/99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Token 不存在"));
    }

    // ==================== UpdateStatus ====================

    @Test
    void updateStatus() throws Exception {
        setAuthentication(regularUser);
        doNothing().when(tokenService).updateStatus(eq(1L), eq(0));

        mockMvc.perform(put("/api/tokens/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
