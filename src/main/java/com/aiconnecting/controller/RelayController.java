package com.aiconnecting.controller;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.service.ChannelService;
import com.aiconnecting.service.RelayService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OpenAI 兼容 API 中转控制器
 * 兼容 /v1/chat/completions, /v1/completions, /v1/embeddings 等接口
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class RelayController {

    private final RelayService relayService;
    private final ChannelService channelService;
    private final ObjectMapper objectMapper;

    /**
     * Chat Completions API (兼容 OpenAI 格式)
     */
    @PostMapping("/v1/chat/completions")
    public Object chatCompletions(@RequestHeader("Authorization") String authHeader,
                                  @RequestBody String requestBody,
                                  HttpServletRequest request,
                                  HttpServletResponse response) throws IOException {
        String tokenKey = extractTokenKey(authHeader);

        // 解析请求获取模型名
        JsonNode jsonBody = objectMapper.readTree(requestBody);
        String model = jsonBody.has("model") ? jsonBody.get("model").asText() : "";
        boolean stream = jsonBody.has("stream") && jsonBody.get("stream").asBoolean();

        if (stream) {
            relayService.relayStreamRequest(tokenKey, "/v1/chat/completions",
                    requestBody, model, request, response);
            return null;
        }

        String result = relayService.relayRequest(tokenKey, "/v1/chat/completions",
                requestBody, model, request);
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
        String tokenKey = extractTokenKey(authHeader);
        JsonNode jsonBody = objectMapper.readTree(requestBody);
        String model = jsonBody.has("model") ? jsonBody.get("model").asText() : "";
        boolean stream = jsonBody.has("stream") && jsonBody.get("stream").asBoolean();

        if (stream) {
            relayService.relayStreamRequest(tokenKey, "/v1/completions",
                    requestBody, model, request, response);
            return null;
        }

        String result = relayService.relayRequest(tokenKey, "/v1/completions",
                requestBody, model, request);
        return objectMapper.readTree(result);
    }

    /**
     * Embeddings API
     */
    @PostMapping("/v1/embeddings")
    public Object embeddings(@RequestHeader(value = "Authorization", required = false) String authHeader,
                             @RequestBody String requestBody,
                             HttpServletRequest request) throws IOException {
        String tokenKey = extractTokenKey(authHeader);
        JsonNode jsonBody = objectMapper.readTree(requestBody);
        String model = jsonBody.has("model") ? jsonBody.get("model").asText() : "";

        String result = relayService.relayRequest(tokenKey, "/v1/embeddings",
                requestBody, model, request);
        return objectMapper.readTree(result);
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
        List<Channel> channels = channelService.listAll().stream()
                .filter(c -> c.getStatus() == 1)
                .collect(Collectors.toList());

        Set<String> modelSet = new LinkedHashSet<>();
        for (Channel channel : channels) {
            if (channel.getModels() != null && !channel.getModels().isEmpty()) {
                for (String model : channel.getModels().split(",")) {
                    modelSet.add(model.trim());
                }
            }
        }

        List<Map<String, Object>> modelList = new ArrayList<>();
        for (String model : modelSet) {
            Map<String, Object> modelObj = new LinkedHashMap<>();
            modelObj.put("id", model);
            modelObj.put("object", "model");
            modelObj.put("created", System.currentTimeMillis() / 1000);
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
}
