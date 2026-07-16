package com.aiconnecting.controller;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.GlobalExceptionHandler;
import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.entity.Token;
import com.aiconnecting.entity.User;
import com.aiconnecting.repository.ChannelRepository;
import com.aiconnecting.repository.ModelConfigRepository;
import com.aiconnecting.repository.UserRepository;
import com.aiconnecting.service.ModelConfigService;
import com.aiconnecting.service.RelayService;
import com.aiconnecting.service.TokenService;
import com.aiconnecting.service.UserService;
import com.aiconnecting.security.JwtAuthenticationFilter;
import com.aiconnecting.security.JwtUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RelayController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class RelayControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RelayService relayService;

    @MockBean
    private TokenService tokenService;

    @MockBean
    private ModelConfigService modelConfigService;

    @MockBean
    private UserService userService;

    @MockBean
    private ModelConfigRepository modelConfigRepository;

    @MockBean
    private ChannelRepository channelRepository;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private JwtUtils jwtUtils;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    // ==================== Chat Completions ====================

    @Test
    void chatCompletions_success() throws Exception {
        Token token = Token.builder().id(1L).tokenKey("sk-test").userId(1L).status(1).quota(-1L).usedQuota(0L).build();
        when(tokenService.validateTokenKey("sk-test")).thenReturn(token);
        when(relayService.resolveModelName("gpt-4")).thenReturn("gpt-4");

        String upstreamResponse = """
                {
                    "choices": [{"message": {"content": "Hello!"}}],
                    "usage": {"prompt_tokens": 5, "completion_tokens": 3, "total_tokens": 8}
                }
                """;
        when(relayService.relayRequest(eq("sk-test"), eq("/v1/chat/completions"),
                anyString(), eq("gpt-4"), any())).thenReturn(upstreamResponse);

        String body = """
                {
                    "model": "gpt-4",
                    "messages": [{"role": "user", "content": "hi"}]
                }
                """;

        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer sk-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.content").value("Hello!"));
    }

    @Test
    void chatCompletions_missingAuth() throws Exception {
        String body = """
                {
                    "model": "gpt-4",
                    "messages": [{"role": "user", "content": "hi"}]
                }
                """;

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is5xxServerError());
    }

    @Test
    void chatCompletions_invalidTokenFormat() throws Exception {
        String body = """
                {
                    "model": "gpt-4",
                    "messages": [{"role": "user", "content": "hi"}]
                }
                """;

        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Basic xxx")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("缺少 Authorization header 或格式不正确"));
    }

    @Test
    void chatCompletions_displayNameConversion() throws Exception {
        Token token = Token.builder().id(1L).tokenKey("sk-test").userId(1L).status(1).quota(-1L).usedQuota(0L).build();
        when(tokenService.validateTokenKey("sk-test")).thenReturn(token);
        when(relayService.resolveModelName("GPT-4o")).thenReturn("gpt-4o");

        String upstreamResponse = """
                {
                    "choices": [{"message": {"content": "OK"}}],
                    "usage": {"prompt_tokens": 3, "completion_tokens": 2, "total_tokens": 5}
                }
                """;
        when(relayService.relayRequest(eq("sk-test"), eq("/v1/chat/completions"),
                argThat(s -> s.contains("\"gpt-4o\"")), eq("gpt-4o"), any())).thenReturn(upstreamResponse);

        String body = """
                {
                    "model": "GPT-4o",
                    "messages": [{"role": "user", "content": "test"}]
                }
                """;

        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer sk-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.content").value("OK"));
    }

    @Test
    void chatCompletions_tokenExhausted() throws Exception {
        when(relayService.resolveModelName("gpt-4")).thenReturn("gpt-4");
        when(relayService.relayRequest(eq("sk-test"), eq("/v1/chat/completions"),
                anyString(), eq("gpt-4"), any()))
                .thenThrow(new BusinessException(429, "Token 额度已用完"));

        String body = "{\"model\":\"gpt-4\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer sk-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Token 额度已用完"));
    }

    // ==================== Completions ====================

    @Test
    void completions_success() throws Exception {
        Token token = Token.builder().id(1L).tokenKey("sk-test").userId(1L).status(1).quota(-1L).usedQuota(0L).build();
        when(tokenService.validateTokenKey("sk-test")).thenReturn(token);
        when(relayService.resolveModelName("gpt-3.5-turbo")).thenReturn("gpt-3.5-turbo");

        String upstreamResponse = """
                {
                    "choices": [{"text": "Hello!"}],
                    "usage": {"prompt_tokens": 5, "completion_tokens": 3, "total_tokens": 8}
                }
                """;
        when(relayService.relayRequest(eq("sk-test"), eq("/v1/completions"),
                anyString(), eq("gpt-3.5-turbo"), any())).thenReturn(upstreamResponse);

        String body = """
                {
                    "model": "gpt-3.5-turbo",
                    "prompt": "Hello"
                }
                """;

        mockMvc.perform(post("/v1/completions")
                        .header("Authorization", "Bearer sk-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].text").value("Hello!"));
    }

    // ==================== Embeddings ====================

    @Test
    void embeddings_success() throws Exception {
        Token token = Token.builder().id(1L).tokenKey("sk-test").userId(1L).status(1).quota(-1L).usedQuota(0L).build();
        when(tokenService.validateTokenKey("sk-test")).thenReturn(token);
        when(relayService.resolveModelName("text-embedding-ada-002")).thenReturn("text-embedding-ada-002");

        String upstreamResponse = """
                {
                    "data": [{"embedding": [0.1, 0.2, 0.3]}],
                    "usage": {"prompt_tokens": 5, "total_tokens": 5}
                }
                """;
        when(relayService.relayRequest(eq("sk-test"), eq("/v1/embeddings"),
                anyString(), eq("text-embedding-ada-002"), any())).thenReturn(upstreamResponse);

        String body = """
                {
                    "model": "text-embedding-ada-002",
                    "input": "Hello"
                }
                """;

        mockMvc.perform(post("/v1/embeddings")
                        .header("Authorization", "Bearer sk-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].embedding").isArray());
    }

    // ==================== Models List ====================

    @Test
    void listModels_admin() throws Exception {
        Token token = Token.builder().id(1L).tokenKey("sk-test").userId(1L).status(1).build();
        when(tokenService.validateTokenKey("sk-test")).thenReturn(token);
        when(userService.isAdmin(1L)).thenReturn(true);

        ModelConfig m1 = ModelConfig.builder().id(1L).name("gpt-4").displayName("GPT-4").status(1)
                .createdAt(LocalDateTime.now()).build();
        when(modelConfigService.getAvailableModels(true)).thenReturn(List.of(m1));

        mockMvc.perform(get("/v1/models")
                        .header("Authorization", "Bearer sk-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value("GPT-4"))
                .andExpect(jsonPath("$.data[0].object").value("model"));
    }

    @Test
    void listModels_regularUser() throws Exception {
        Token token = Token.builder().id(2L).tokenKey("sk-test").userId(2L).status(1).build();
        when(tokenService.validateTokenKey("sk-test")).thenReturn(token);
        when(userService.isAdmin(2L)).thenReturn(false);

        ModelConfig m1 = ModelConfig.builder().id(1L).name("gpt-4").displayName("GPT-4")
                .adminOnly(false).status(1).createdAt(LocalDateTime.now()).build();
        when(modelConfigService.getAvailableModels(false)).thenReturn(List.of(m1));

        mockMvc.perform(get("/v1/models")
                        .header("Authorization", "Bearer sk-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value("GPT-4"));
    }

    @Test
    void listModels_noChannelModels() throws Exception {
        Token token = Token.builder().id(1L).tokenKey("sk-test").userId(1L).status(1).build();
        when(tokenService.validateTokenKey("sk-test")).thenReturn(token);
        when(userService.isAdmin(1L)).thenReturn(true);

        when(modelConfigService.getAvailableModels(true)).thenReturn(List.of());

        mockMvc.perform(get("/v1/models")
                        .header("Authorization", "Bearer sk-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void listModels_invalidToken() throws Exception {
        when(tokenService.validateTokenKey("sk-invalid"))
                .thenThrow(new BusinessException(401, "无效的 Token"));

        mockMvc.perform(get("/v1/models")
                        .header("Authorization", "Bearer sk-invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("无效的 Token"));
    }

    @Test
    void listModels_multipleChannels() throws Exception {
        Token token = Token.builder().id(1L).tokenKey("sk-test").userId(1L).status(1).build();
        when(tokenService.validateTokenKey("sk-test")).thenReturn(token);
        when(userService.isAdmin(1L)).thenReturn(true);

        ModelConfig m1 = ModelConfig.builder().id(1L).name("gpt-4").displayName("GPT-4").status(1)
                .createdAt(LocalDateTime.now()).build();
        ModelConfig m2 = ModelConfig.builder().id(2L).name("claude-3").displayName("Claude-3").status(1)
                .createdAt(LocalDateTime.now()).build();
        when(modelConfigService.getAvailableModels(true)).thenReturn(List.of(m1, m2));

        mockMvc.perform(get("/v1/models")
                        .header("Authorization", "Bearer sk-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value("GPT-4"))
                .andExpect(jsonPath("$.data[1].id").value("Claude-3"));
    }
}
