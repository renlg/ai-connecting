package com.aiconnecting.controller;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.entity.Token;
import com.aiconnecting.entity.User;
import com.aiconnecting.repository.ChannelRepository;
import com.aiconnecting.repository.ModelConfigRepository;
import com.aiconnecting.repository.UserRepository;
import com.aiconnecting.service.RelayService;
import com.aiconnecting.service.TokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;

/**
 * OpenAI 兼容 API 中转控制器
 * 兼容 /v1/chat/completions, /v1/completions, /v1/embeddings 等接口
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class RelayController {

    private final RelayService relayService;
    private final TokenService tokenService;
    private final ModelConfigRepository modelConfigRepository;
    private final ChannelRepository channelRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    /**
     * Chat Completions API (兼容 OpenAI 格式)
     */
    @PostMapping("/v1/chat/completions")
    public Object chatCompletions(@RequestHeader("Authorization") String authHeader,
                                  @RequestBody String requestBody,
                                  HttpServletRequest request,
                                  HttpServletResponse response) throws IOException {
        return handleRelayRequest(authHeader, requestBody, "/v1/chat/completions", request, response);
    }

    /**
     * Claude Messages API (兼容 CC 协议)
     * 接收 Claude 格式请求，内部转换为 OpenAI 格式发送给上游，响应转换回 Claude 格式
     */
    @PostMapping("/v1/messages")
    public Object claudeMessages(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                  @RequestHeader(value = "x-api-key", required = false) String apiKey,
                                  @RequestBody String requestBody,
                                  HttpServletRequest request,
                                  HttpServletResponse response) throws IOException {
        // 支持 Bearer Token 和 x-api-key 两种认证方式
        String tokenKey;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            tokenKey = authHeader.substring(7);
        } else if (apiKey != null && !apiKey.isBlank()) {
            tokenKey = apiKey;
        } else {
            throw new BusinessException(401, "缺少认证信息，请使用 Authorization: Bearer <token> 或 x-api-key: <token>");
        }

        JsonNode jsonBody = objectMapper.readTree(requestBody);
        String model = jsonBody.has("model") ? jsonBody.get("model").asText() : "";
        // 将 displayName 转换为实际模型名
        String resolvedModel = relayService.resolveModelName(model);
        if (!resolvedModel.equals(model)) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) jsonBody).put("model", resolvedModel);
            requestBody = objectMapper.writeValueAsString(jsonBody);
        }

        boolean stream = jsonBody.has("stream") && jsonBody.get("stream").asBoolean();

        if (stream) {
            relayService.claudeRelayStreamRequest(tokenKey, requestBody, resolvedModel, request, response);
            return null;
        }

        String result = relayService.claudeRelayRequest(tokenKey, requestBody, resolvedModel, request);
        return objectMapper.readTree(result);
    }

    /**
     * Completions API
     */
    @PostMapping("/v1/completions")
    public Object completions(@RequestHeader(value = "Authorization", required = false) String authHeader,
                              @RequestBody String requestBody,
                              HttpServletRequest request,
                              HttpServletResponse response) throws IOException {
        return handleRelayRequest(authHeader, requestBody, "/v1/completions", request, response);
    }

    /**
     * Embeddings API
     */
    @PostMapping("/v1/embeddings")
    public Object embeddings(@RequestHeader(value = "Authorization", required = false) String authHeader,
                             @RequestBody String requestBody,
                             HttpServletRequest request) throws IOException {
        return handleRelayRequest(authHeader, requestBody, "/v1/embeddings", request, null);
    }

    /**
     * Images Generation API
     */
    @PostMapping("/v1/images/generations")
    public Object imageGenerations(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                   @RequestBody String requestBody,
                                   HttpServletRequest request) throws IOException {
        String tokenKey = extractTokenKey(authHeader);
        String result = relayService.relayRequest(tokenKey, "/v1/images/generations",
                requestBody, "dall-e", request);
        return objectMapper.readTree(result);
    }

    /**
     * Audio Transcription API
     */
    @PostMapping("/v1/audio/transcriptions")
    public Object audioTranscriptions(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                      @RequestBody String requestBody,
                                      HttpServletRequest request) throws IOException {
        String tokenKey = extractTokenKey(authHeader);
        String result = relayService.relayRequest(tokenKey, "/v1/audio/transcriptions",
                requestBody, "whisper-1", request);
        return objectMapper.readTree(result);
    }

    /**
     * Models List API - 返回所有可用模型
     */
    @GetMapping("/v1/models")
    public Map<String, Object> listModels(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String tokenKey = extractTokenKey(authHeader);
        Token token = tokenService.validateTokenKey(tokenKey);

        boolean isAdmin = userRepository.findById(token.getUserId())
                .map(u -> "admin".equals(u.getRole()))
                .orElse(false);

        List<ModelConfig> models = isAdmin
                ? modelConfigRepository.findByStatusOrderByStatusDescNameAsc(1)
                : modelConfigRepository.findByStatusAndAdminOnlyFalseOrderByStatusDescNameAsc(1);

        // 收集所有启用渠道配置的模型ID
        Set<String> channelModelIds = new LinkedHashSet<>();
        for (Channel channel : channelRepository.findByStatusOrderByPriorityDesc(1)) {
            if (channel.getModelIds() != null && !channel.getModelIds().isEmpty()) {
                for (String modelId : channel.getModelIds().split(",")) {
                    channelModelIds.add(modelId.trim());
                }
            }
        }

        List<Map<String, Object>> modelList = new ArrayList<>();
        for (ModelConfig model : models) {
            // 检查模型ID是否在渠道配置中
            if (!channelModelIds.contains(String.valueOf(model.getId()))) {
                continue;
            }
            Map<String, Object> modelObj = new LinkedHashMap<>();
            modelObj.put("id", model.getDisplayName());
            modelObj.put("object", "model");
            modelObj.put("created", model.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toEpochSecond());
            modelObj.put("owned_by", "ai-connecting");
            modelList.add(modelObj);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("object", "list");
        result.put("data", modelList);
        return result;
    }

    private String extractTokenKey(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new BusinessException(401, "缺少 Authorization header 或格式不正确");
        }
        return authHeader.substring(7);
    }

    /**
     * 通用中转处理：解析请求、判断流式/非流式、调用 RelayService
     */
    private Object handleRelayRequest(String authHeader, String requestBody,
                                       String path, HttpServletRequest request,
                                       HttpServletResponse response) throws IOException {
        String tokenKey = extractTokenKey(authHeader);
        JsonNode jsonBody = objectMapper.readTree(requestBody);
        String model = jsonBody.has("model") ? jsonBody.get("model").asText() : "";

        // 将 displayName 转换为实际模型名
        String resolvedModel = relayService.resolveModelName(model);
        if (!resolvedModel.equals(model)) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) jsonBody).put("model", resolvedModel);
            requestBody = objectMapper.writeValueAsString(jsonBody);
        }
        model = resolvedModel;

        boolean stream = jsonBody.has("stream") && jsonBody.get("stream").asBoolean();

        if (stream && response != null) {
            relayService.relayStreamRequest(tokenKey, path, requestBody, model, request, response);
            return null;
        }

        String result = relayService.relayRequest(tokenKey, path, requestBody, model, request);
        return objectMapper.readTree(result);
    }
}
