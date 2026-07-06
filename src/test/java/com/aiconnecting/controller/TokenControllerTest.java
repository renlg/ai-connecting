package com.aiconnecting.controller;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.dto.TokenRequest;
import com.aiconnecting.entity.Token;
import com.aiconnecting.entity.User;
import com.aiconnecting.repository.ChannelRepository;
import com.aiconnecting.repository.ModelConfigRepository;
import com.aiconnecting.repository.UsageLogRepository;
import com.aiconnecting.repository.UserRepository;
import com.aiconnecting.service.ChannelService;
import com.aiconnecting.service.ModelConfigService;
import com.aiconnecting.service.RelayService;
import com.aiconnecting.service.TokenService;
import com.aiconnecting.service.UsageLogService;
import com.aiconnecting.service.UserService;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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
    private RelayService relayService;

    @MockBean
    private ChannelService channelService;

    @MockBean
    private ChannelRepository channelRepository;

    @MockBean
    private ModelConfigRepository modelConfigRepository;

    @MockBean
    private UsageLogService usageLogService;

    @MockBean
    private UserService userService;

    @MockBean
    private ModelConfigService modelConfigService;

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
                .credits(BigDecimal.valueOf(100)).status(1).build();
        regularUser = User.builder()
                .id(2L).username("user").password("encoded").role("user")
                .credits(BigDecimal.valueOf(50)).status(1).build();
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
        when(userService.getUserIdToNameMap(List.of(2L))).thenReturn(Map.of(2L, "user"));

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
        when(userService.getUserIdToNameMap(List.of(2L))).thenReturn(Map.of(2L, "user"));

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
        when(userService.getUserIdToNameMap(anyList())).thenReturn(Map.of(2L, "testuser", 1L, "admin"));

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
        Token t = Token.builder().id(1L).name("my-token").userId(2L).build();
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

        Token existing = Token.builder().id(1L).name("old-token").userId(2L).build();
        when(tokenService.getById(1L)).thenReturn(existing);
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
        when(tokenService.getById(99L)).thenThrow(new BusinessException("Token 不存在"));

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
        Token existing = Token.builder().id(1L).name("test-token").userId(2L).build();
        when(tokenService.getById(1L)).thenReturn(existing);
        doNothing().when(tokenService).delete(1L);

        mockMvc.perform(delete("/api/tokens/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void deleteToken_notFound() throws Exception {
        setAuthentication(regularUser);
        when(tokenService.getById(99L)).thenThrow(new BusinessException("Token 不存在"));

        mockMvc.perform(delete("/api/tokens/99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Token 不存在"));
    }

    // ==================== UpdateStatus ====================

    @Test
    void updateStatus() throws Exception {
        setAuthentication(regularUser);
        Token existing = Token.builder().id(1L).name("test-token").userId(2L).build();
        when(tokenService.getById(1L)).thenReturn(existing);
        doNothing().when(tokenService).updateStatus(eq(1L), eq(0));

        mockMvc.perform(put("/api/tokens/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== TestChat ====================

    @Test
    void testChat_openai_success() throws Exception {
        setAuthentication(regularUser);
        Token tokenEntity = Token.builder().id(1L).name("test").userId(2L).build();
        when(tokenService.validateTokenKey("sk-test-key")).thenReturn(tokenEntity);
        // Mock relayService.resolveModelName
        when(relayService.resolveModelName("GPT-4o")).thenReturn("gpt-4o");
        // Mock relayService.relayRequest - return a valid OpenAI response JSON
        String openAiResponse = """
                {
                    "choices": [{"message": {"content": "Hello!"}}],
                    "usage": {"prompt_tokens": 5, "completion_tokens": 3, "total_tokens": 8}
                }
                """;
        when(relayService.relayRequest(eq("sk-test-key"), eq("/v1/chat/completions"),
                anyString(), eq("gpt-4o"), isNull())).thenReturn(openAiResponse);

        String body = """
                {
                    "tokenKey": "sk-test-key",
                    "protocol": "openai",
                    "model": "GPT-4o",
                    "message": "hi"
                }
                """;

        mockMvc.perform(post("/api/tokens/test-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.content").value("Hello!"))
                .andExpect(jsonPath("$.data.protocol").value("openai"))
                .andExpect(jsonPath("$.data.usage.prompt_tokens").value(5))
                .andExpect(jsonPath("$.data.usage.completion_tokens").value(3))
                .andExpect(jsonPath("$.data.usage.total_tokens").value(8));
    }

    @Test
    void testChat_claude_success() throws Exception {
        setAuthentication(regularUser);
        Token tokenEntity = Token.builder().id(1L).name("test").userId(2L).build();
        when(tokenService.validateTokenKey("sk-test-key")).thenReturn(tokenEntity);
        when(relayService.resolveModelName("Claude-3-Opus")).thenReturn("claude-3-opus-20240229");
        String claudeResponse = """
                {
                    "content": [{"type": "text", "text": "Hi there!"}],
                    "usage": {"input_tokens": 10, "output_tokens": 5}
                }
                """;
        when(relayService.claudeRelayRequest(eq("sk-test-key"), anyString(),
                eq("claude-3-opus-20240229"), isNull())).thenReturn(claudeResponse);

        String body = """
                {
                    "tokenKey": "sk-test-key",
                    "protocol": "claude",
                    "model": "Claude-3-Opus",
                    "message": "hello"
                }
                """;

        mockMvc.perform(post("/api/tokens/test-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.content").value("Hi there!"))
                .andExpect(jsonPath("$.data.protocol").value("claude"))
                .andExpect(jsonPath("$.data.usage.input_tokens").value(10))
                .andExpect(jsonPath("$.data.usage.output_tokens").value(5));
    }

    @Test
    void testChat_missingTokenKey() throws Exception {
        String body = """
                {
                    "protocol": "openai",
                    "model": "gpt-4",
                    "message": "hi"
                }
                """;

        mockMvc.perform(post("/api/tokens/test-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("缺少 Token Key"));
    }

    @Test
    void testChat_blankTokenKey() throws Exception {
        String body = """
                {
                    "tokenKey": "  ",
                    "protocol": "openai",
                    "model": "gpt-4",
                    "message": "hi"
                }
                """;

        mockMvc.perform(post("/api/tokens/test-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("缺少 Token Key"));
    }

    @Test
    void testChat_missingModel() throws Exception {
        String body = """
                {
                    "tokenKey": "sk-test-key",
                    "protocol": "openai",
                    "message": "hi"
                }
                """;

        mockMvc.perform(post("/api/tokens/test-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请选择模型"));
    }

    @Test
    void testChat_businessException() throws Exception {
        setAuthentication(regularUser);
        Token tokenEntity = Token.builder().id(1L).name("test").userId(2L).build();
        when(tokenService.validateTokenKey("sk-invalid")).thenReturn(tokenEntity);
        when(relayService.resolveModelName("GPT-4o")).thenReturn("gpt-4o");
        when(relayService.relayRequest(eq("sk-invalid"), anyString(),
                anyString(), eq("gpt-4o"), isNull()))
                .thenThrow(new BusinessException(401, "无效的 Token"));

        String body = """
                {
                    "tokenKey": "sk-invalid",
                    "protocol": "openai",
                    "model": "GPT-4o",
                    "message": "hi"
                }
                """;

        mockMvc.perform(post("/api/tokens/test-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.error").value("无效的 Token"));
    }

    @Test
    void testChat_runtimeException() throws Exception {
        setAuthentication(regularUser);
        Token tokenEntity = Token.builder().id(1L).name("test").userId(2L).build();
        when(tokenService.validateTokenKey("sk-test-key")).thenReturn(tokenEntity);
        when(relayService.resolveModelName("GPT-4o")).thenReturn("gpt-4o");
        when(relayService.relayRequest(eq("sk-test-key"), anyString(),
                anyString(), eq("gpt-4o"), isNull()))
                .thenThrow(new RuntimeException("Connection timeout"));

        String body = """
                {
                    "tokenKey": "sk-test-key",
                    "protocol": "openai",
                    "model": "GPT-4o",
                    "message": "hi"
                }
                """;

        mockMvc.perform(post("/api/tokens/test-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.error").value("请求失败: Connection timeout"));
    }

    @Test
    void testChat_defaultMessage() throws Exception {
        setAuthentication(regularUser);
        Token tokenEntity = Token.builder().id(1L).name("test").userId(2L).build();
        when(tokenService.validateTokenKey("sk-test-key")).thenReturn(tokenEntity);
        when(relayService.resolveModelName("gpt-4")).thenReturn("gpt-4");
        String openAiResponse = """
                {
                    "choices": [{"message": {"content": "Hello!"}}],
                    "usage": {"prompt_tokens": 3, "completion_tokens": 2, "total_tokens": 5}
                }
                """;
        when(relayService.relayRequest(eq("sk-test-key"), eq("/v1/chat/completions"),
                anyString(), eq("gpt-4"), isNull())).thenReturn(openAiResponse);

        // message is null -> should default to "hi"
        String body = """
                {
                    "tokenKey": "sk-test-key",
                    "protocol": "openai",
                    "model": "gpt-4"
                }
                """;

        mockMvc.perform(post("/api/tokens/test-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true));
    }
}
