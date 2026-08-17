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
import com.aiconnecting.service.ModelGroupService;
import com.aiconnecting.service.ModelGroupRoutingService;
import com.aiconnecting.service.PassthroughRelayService;
import com.aiconnecting.service.RelayService;
import com.aiconnecting.service.TokenService;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private PassthroughRelayService passthroughRelayService;

    @MockBean
    private TokenService tokenService;

    @MockBean
    private ModelConfigService modelConfigService;

    @MockBean
    private ModelGroupService modelGroupService;

    @MockBean
    private ModelGroupRoutingService modelGroupRoutingService;

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

    @BeforeEach
    void bridgeResponseAwareRelayOverloadToExistingStubs() {
        lenient().when(relayService.relayRequest(
                        anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> relayService.relayRequest(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                        invocation.getArgument(3), invocation.getArgument(4)));
    }

    // ==================== Chat Completions ====================

    @Test
    void passthroughRunsBeforeNormalDisplayNameRewrite() throws Exception {
        when(passthroughRelayService.tryPassthrough(anyString(), any(), any())).thenReturn(true);

        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer sk-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"Display Name\",\"messages\":[]}"))
                .andExpect(status().isOk());

        verify(passthroughRelayService).tryPassthrough(
                eq("{\"model\":\"Display Name\",\"messages\":[]}"), any(), any());
        verifyNoInteractions(relayService);
    }

    @Test
    void arbitraryVendorPostUsesPassthroughFallbackWithOriginalQuery() throws Exception {
        when(passthroughRelayService.tryPassthrough(anyString(), any(), any())).thenAnswer(invocation -> {
            jakarta.servlet.http.HttpServletRequest request = invocation.getArgument(1);
            assertEquals("/v1/vendor/path", request.getRequestURI());
            assertEquals("x=1", request.getQueryString());
            return true;
        });

        mockMvc.perform(post("/v1/vendor/path?x=1")
                        .header("Authorization", "Bearer sk-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"vendor-model\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void malformedOrMissingTopLevelModelReturns400BeforeNormalRelay() throws Exception {
        when(passthroughRelayService.tryPassthrough(anyString(), any(), any()))
                .thenThrow(new BusinessException(400, "请求格式错误", "Request must contain a model"));

        mockMvc.perform(post("/v1/completions")
                        .header("Authorization", "Bearer sk-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(400));

        verifyNoInteractions(relayService);
    }

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
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.error.message").value("Internal server error, please try again later"));
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
                .andExpect(jsonPath("$.error.message").value("Missing or malformed Authorization header"))
                .andExpect(jsonPath("$.error.traceId").doesNotExist());
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
                .thenThrow(new BusinessException(429, "Token 额度已用完", "Token quota exhausted"));

        String body = "{\"model\":\"gpt-4\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer sk-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.message").value("Token quota exhausted"))
                .andExpect(jsonPath("$.error.traceId").doesNotExist());
    }

    @Test
    void chatCompletions_streamingModelNotFound_returnsJson404WithoutTraceId() throws Exception {
        when(relayService.resolveModelName("free1"))
                .thenThrow(new BusinessException(404, "模型不存在: free1", "Model not found: free1"));

        String body = "{\"model\":\"free1\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer sk-test")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string("event: error\ndata: {\"error\":{\"message\":\"Model not found: free1\",\"type\":\"invalid_request_error\",\"code\":404}}\n\n"));
    }

    @Test
    void chatCompletions_directGroupUnsupportedLevel_returnsModelNotFoundShape() throws Exception {
        when(relayService.resolveModelName("bailian-good")).thenReturn("bailian-good");
        when(relayService.relayRequest(eq("sk-level-one"), eq("/v1/chat/completions"),
                anyString(), eq("bailian-good"), any()))
                .thenThrow(new BusinessException(404,
                        "模型组不存在或未启用: bailian-good",
                        "Model group not found or disabled: bailian-good"));

        String body = "{\"model\":\"bailian-good\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer sk-level-one")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(404))
                .andExpect(jsonPath("$.error.message")
                        .value("Model group not found or disabled: bailian-good"))
                .andExpect(jsonPath("$.error.traceId").doesNotExist());
    }

    @Test
    void chatCompletions_streamingDirectGroupUnsupportedLevel_returnsSameJson404Shape() throws Exception {
        when(relayService.resolveModelName("bailian-good")).thenReturn("bailian-good");
        doThrow(new BusinessException(404,
                "模型组不存在或未启用: bailian-good",
                "Model group not found or disabled: bailian-good"))
                .when(relayService).relayStreamRequest(eq("sk-level-one"), eq("/v1/chat/completions"),
                        anyString(), eq("bailian-good"), any(), any());

        String body = "{\"model\":\"bailian-good\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer sk-level-one")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string("event: error\ndata: {\"error\":{\"message\":\"Model group not found or disabled: bailian-good\",\"type\":\"invalid_request_error\",\"code\":404}}\n\n"));
    }

    @Test
    void chatCompletions_unexpectedStreamingError_returnsGenericSseWithTraceId() throws Exception {
        when(relayService.resolveModelName("gpt-4")).thenReturn("gpt-4");
        doThrow(new IllegalStateException("unexpected failure")).when(relayService)
                .relayStreamRequest(eq("sk-test"), eq("/v1/chat/completions"), anyString(),
                        eq("gpt-4"), any(), any());

        String body = "{\"model\":\"gpt-4\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer sk-test")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.matchesPattern(
                        "event: error\\ndata: \\{\\\"error\\\":\\{\\\"message\\\":\\\"Internal server error, please try again later\\\",\\\"type\\\":\\\"api_error\\\",\\\"code\\\":500}}\\n\\n")));
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

    // ==================== Video Generation ====================

    @Test
    void videoGenerations_normalizesBothEntryPathsForUpstream() throws Exception {
        when(relayService.resolveModelName("sora-2")).thenReturn("sora-2");
        when(relayService.relayMediaRequest(eq("sk-test"), eq("/v1/videos"),
                anyString(), eq("sora-2"), any(), eq("video")))
                .thenReturn("{\"id\":\"video-123\"}");

        for (String entryPath : List.of("/v1/videos", "/v1/videos/generations")) {
            mockMvc.perform(post(entryPath)
                            .header("Authorization", "Bearer sk-test")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\":\"sora-2\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("video-123"));
        }

        verify(relayService, times(2)).relayMediaRequest(eq("sk-test"), eq("/v1/videos"),
                anyString(), eq("sora-2"), any(), eq("video"));
    }

    // ==================== Models List ====================

    @Test
    void listModels_admin() throws Exception {
        Token token = Token.builder().id(1L).tokenKey("sk-test").userId(1L).status(1).build();
        when(tokenService.validateTokenKey("sk-test")).thenReturn(token);
        when(userService.getByIdCached(1L)).thenReturn(User.builder().id(1L).status(1).role("admin").build());
        when(userService.isAdmin(1L)).thenReturn(true);

        ModelConfig m1 = ModelConfig.builder().id(1L).name("gpt-4").displayName("GPT-4").status(1)
                .createdAt(LocalDateTime.now()).build();
        when(modelConfigService.getAvailableModels(true, null)).thenReturn(List.of(m1));

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
        when(userService.getByIdCached(2L)).thenReturn(User.builder().id(2L).status(1).role("user").build());
        when(userService.isAdmin(2L)).thenReturn(false);

        ModelConfig m1 = ModelConfig.builder().id(1L).name("gpt-4").displayName("GPT-4")
                .adminOnly(false).status(1).createdAt(LocalDateTime.now()).build();
        when(modelConfigService.getAvailableModels(false, null)).thenReturn(List.of(m1));

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
        when(userService.getByIdCached(1L)).thenReturn(User.builder().id(1L).status(1).role("admin").build());
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
                .thenThrow(new BusinessException(401, "无效的 Token", "Invalid token"));

        mockMvc.perform(get("/v1/models")
                        .header("Authorization", "Bearer sk-invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message").value("Invalid token"))
                .andExpect(jsonPath("$.error.traceId").doesNotExist());
    }

    @Test
    void listModels_multipleChannels() throws Exception {
        Token token = Token.builder().id(1L).tokenKey("sk-test").userId(1L).status(1).build();
        when(tokenService.validateTokenKey("sk-test")).thenReturn(token);
        when(userService.getByIdCached(1L)).thenReturn(User.builder().id(1L).status(1).role("admin").build());
        when(userService.isAdmin(1L)).thenReturn(true);

        ModelConfig m1 = ModelConfig.builder().id(1L).name("gpt-4").displayName("GPT-4").status(1)
                .createdAt(LocalDateTime.now()).build();
        ModelConfig m2 = ModelConfig.builder().id(2L).name("claude-3").displayName("Claude-3").status(1)
                .createdAt(LocalDateTime.now()).build();
        when(modelConfigService.getAvailableModels(true, null)).thenReturn(List.of(m1, m2));

        mockMvc.perform(get("/v1/models")
                        .header("Authorization", "Bearer sk-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value("GPT-4"))
                .andExpect(jsonPath("$.data[1].id").value("Claude-3"));
    }
}
