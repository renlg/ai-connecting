package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.dto.ChannelRequest;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.repository.ChannelRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChannelService {

    private final ChannelRepository channelRepository;

    @Autowired(required = false)
    private okhttp3.Interceptor tracingInterceptor;

    private OkHttpClient httpClient;
    private OkHttpClient streamHttpClient;

    @jakarta.annotation.PostConstruct
    void initHttpClients() {
        OkHttpClient.Builder baseBuilder = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS);
        OkHttpClient.Builder streamBuilder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false);
        // 禁用连接池复用，防止陈旧连接导致请求卡死
        streamBuilder.connectionPool(new okhttp3.ConnectionPool(5, 10, TimeUnit.SECONDS));
        if (tracingInterceptor != null) {
            baseBuilder.addInterceptor(tracingInterceptor);
            streamBuilder.addInterceptor(tracingInterceptor);
        }
        httpClient = baseBuilder.build();
        streamHttpClient = streamBuilder.build();
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<Channel> listAll() {
        return channelRepository.findAll();
    }

    public Channel getById(Long id) {
        return channelRepository.findById(id)
                .orElseThrow(() -> new BusinessException("渠道不存在"));
    }

    public Channel create(ChannelRequest request) {
        Channel channel = Channel.builder()
                .name(request.getName())
                .type(request.getType())
                .baseUrl(request.getBaseUrl())
                .apiKey(request.getApiKey())
                .modelIds(request.getModelIds())
                .status(request.getStatus() != null ? request.getStatus() : 1)
                .priority(request.getPriority() != null ? request.getPriority() : 0)
                .rateLimit(request.getRateLimit() != null ? request.getRateLimit() : 0)
                .usedQuota(0L)
                .build();
        return channelRepository.save(channel);
    }

    public Channel update(Long id, ChannelRequest request) {
        Channel channel = getById(id);
        if (request.getName() != null) channel.setName(request.getName());
        if (request.getType() != null) channel.setType(request.getType());
        if (request.getBaseUrl() != null) channel.setBaseUrl(request.getBaseUrl());
        if (request.getApiKey() != null) channel.setApiKey(request.getApiKey());
        if (request.getModelIds() != null) channel.setModelIds(request.getModelIds());
        if (request.getStatus() != null) channel.setStatus(request.getStatus());
        if (request.getPriority() != null) channel.setPriority(request.getPriority());
        if (request.getRateLimit() != null) channel.setRateLimit(request.getRateLimit());
        return channelRepository.save(channel);
    }

    public void delete(Long id) {
        if (!channelRepository.existsById(id)) {
            throw new BusinessException("渠道不存在");
        }
        channelRepository.deleteById(id);
    }

    public void updateStatus(Long id, Integer status) {
        Channel channel = getById(id);
        channel.setStatus(status);
        channelRepository.save(channel);
    }

    /**
     * 根据模型ID获取可用的渠道列表 (按优先级排序)
     */
    public List<Channel> getActiveChannelsByModel(String modelId) {
        String modelIdPattern = "%," + modelId + ",%";
        return channelRepository.findActiveChannelsByModel(modelIdPattern);
    }

    /**
     * 测试渠道连通性
     */
    public boolean testChannel(Long id) {
        Channel channel = getById(id);
        return channel.getBaseUrl() != null && !channel.getBaseUrl().isEmpty()
                && channel.getApiKey() != null && !channel.getApiKey().isEmpty();
    }

    /**
     * 测试渠道聊天功能 - 发送一条消息并返回响应
     */
    public Map<String, Object> testChat(String baseUrl, String apiKey, String type, String model, String message) {
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new BusinessException("请先填写 Base URL 和 API Key");
        }
        if (model == null || model.isBlank()) {
            throw new BusinessException("请选择模型");
        }

        long startTime = System.currentTimeMillis();

        try {
            if ("claude".equalsIgnoreCase(type) || "anthropic".equalsIgnoreCase(type)) {
                return testClaudeChat(baseUrl, apiKey, model, message, startTime);
            } else {
                return testOpenAIChat(baseUrl, apiKey, model, message, startTime);
            }
        } catch (IOException e) {
            log.error("渠道测试请求失败: {}", e.getMessage());
            throw new BusinessException("连接上游失败: " + e.getMessage());
        }
    }

    private Map<String, Object> testOpenAIChat(String baseUrl, String apiKey, String model,
                                                String message, long startTime) throws IOException {
        String url = baseUrl.replaceAll("/+$", "") + "/v1/chat/completions";
        String jsonBody = objectMapper.writeValueAsString(Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", message != null ? message : "hi")),
                "max_tokens", 100
        ));

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            long duration = System.currentTimeMillis() - startTime;
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("success", response.isSuccessful());
            result.put("statusCode", response.code());
            result.put("duration", duration);

            if (response.isSuccessful()) {
                JsonNode root = objectMapper.readTree(body);
                String content = root.path("choices").path(0).path("message").path("content").asText("");
                result.put("content", content);
                JsonNode usage = root.get("usage");
                if (usage != null) {
                    Map<String, Object> usageMap = new java.util.LinkedHashMap<>();
                    usageMap.put("prompt_tokens", usage.path("prompt_tokens").asInt(0));
                    usageMap.put("completion_tokens", usage.path("completion_tokens").asInt(0));
                    usageMap.put("total_tokens", usage.path("total_tokens").asInt(0));
                    result.put("usage", usageMap);
                }
            } else {
                result.put("error", body.length() > 500 ? body.substring(0, 500) : body);
            }
            return result;
        }
    }

    /**
     * Claude 协议非流式测试 - 内部转换为 OpenAI 格式发送给上游，响应转换回 Claude 格式
     */
    private Map<String, Object> testClaudeChat(String baseUrl, String apiKey, String model,
                                                String message, long startTime) throws IOException {
        // 使用 OpenAI 格式发送请求
        String url = baseUrl.replaceAll("/+$", "") + "/v1/chat/completions";
        String jsonBody = objectMapper.writeValueAsString(Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", message != null ? message : "hi")),
                "max_tokens", 100
        ));

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            long duration = System.currentTimeMillis() - startTime;
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("success", response.isSuccessful());
            result.put("statusCode", response.code());
            result.put("duration", duration);

            if (response.isSuccessful()) {
                JsonNode root = objectMapper.readTree(body);
                // 将 OpenAI 响应转换为 Claude 格式
                String content = root.path("choices").path(0).path("message").path("content").asText("");
                // Claude 格式: content 是数组 [{type: "text", text: "..."}]
                result.put("content", content);
                JsonNode usage = root.get("usage");
                if (usage != null) {
                    Map<String, Object> usageMap = new java.util.LinkedHashMap<>();
                    usageMap.put("input_tokens", usage.path("prompt_tokens").asInt(0));
                    usageMap.put("output_tokens", usage.path("completion_tokens").asInt(0));
                    result.put("usage", usageMap);
                }
            } else {
                result.put("error", body.length() > 500 ? body.substring(0, 500) : body);
            }
            return result;
        }
    }

    /**
     * 测试渠道聊天功能（流式）- 发送一条消息并流式返回响应
     */
    public void testChatStream(Map<String, String> request, HttpServletResponse response) throws Exception {
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        String baseUrl = request.get("baseUrl");
        String apiKey = request.get("apiKey");
        String type = request.get("type");
        String model = request.get("model");
        String message = request.get("message");

        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new BusinessException("请先填写 Base URL 和 API Key");
        }
        if (model == null || model.isBlank()) {
            throw new BusinessException("请选择模型");
        }

        try {
            if ("claude".equalsIgnoreCase(type) || "anthropic".equalsIgnoreCase(type)) {
                testClaudeChatStream(baseUrl, apiKey, model, message, response);
            } else {
                testOpenAIChatStream(baseUrl, apiKey, model, message, response);
            }
        } catch (Exception e) {
            // 发送错误事件
            response.getWriter().write("data: {\"error\":\"" + escapeJson(e.getMessage()) + "\"}\n\n");
            response.getWriter().flush();
        }
    }

    private void testOpenAIChatStream(String baseUrl, String apiKey, String model,
                                       String message, HttpServletResponse response) throws Exception {
        String url = baseUrl.replaceAll("/+$", "") + "/v1/chat/completions";
        log.info("流式请求 OpenAI: url={}, model={}", url, model);
        String jsonBody = objectMapper.writeValueAsString(Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", message != null ? message : "hi")),
                "max_tokens", 4096,
                "stream", true
        ));

        // 使用 HttpURLConnection 避免 OkHttp 连接池问题
        java.net.URL urlObj = new java.net.URL(url);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) urlObj.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Connection", "close");

        log.info("准备发送HTTP请求");
        long httpStart = System.currentTimeMillis();
        try {
            conn.getOutputStream().write(jsonBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            conn.getOutputStream().flush();

            int code = conn.getResponseCode();
            long httpDuration = System.currentTimeMillis() - httpStart;
            log.info("HTTP请求返回, 耗时: {}ms, code: {}", httpDuration, code);

            if (code != 200) {
                String errorBody = new String(conn.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                log.error("上游返回错误: {}", errorBody);
                response.getWriter().write("data: {\"error\":\"HTTP " + code + ": " + escapeJson(errorBody) + "\"}\n\n");
                response.getWriter().flush();
                return;
            }

            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            String line;
            int chunkCount = 0;
            int lineCount = 0;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                line = line.trim();
                if (!line.startsWith("data:")) continue;

                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    response.getWriter().write("data: [DONE]\n\n");
                    response.getWriter().flush();
                    log.info("流式传输完成, 共 {} 个 chunk, {} 行数据", chunkCount, lineCount);
                    break;
                }

                try {
                    JsonNode json = objectMapper.readTree(data);
                    JsonNode delta = json.path("choices").path(0).path("delta");
                    String content = delta.path("content").asText("");
                    String reasoningContent = delta.path("reasoning_content").asText("");
                    String text = content.isEmpty() ? reasoningContent : content;
                    if (!text.isEmpty()) {
                        Map<String, Object> chunk = new java.util.LinkedHashMap<>();
                        chunk.put("content", text);
                        if (!reasoningContent.isEmpty()) {
                            chunk.put("reasoning", true);
                        }
                        response.getWriter().write("data: " + objectMapper.writeValueAsString(chunk) + "\n\n");
                        response.getWriter().flush();
                        chunkCount++;
                    }
                } catch (Exception e) {
                    log.warn("解析 SSE 数据失败: {}", data);
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Claude 协议流式测试 - 内部转换为 OpenAI 格式发送给上游，响应再转换回 Claude SSE 格式
     */
    private void testClaudeChatStream(String baseUrl, String apiKey, String model,
                                       String message, HttpServletResponse response) throws Exception {
        log.info("Claude 协议测试 (内部转 OpenAI): url={}, model={}", baseUrl, model);
        String url = baseUrl.replaceAll("/+$", "") + "/v1/chat/completions";
        String jsonBody = objectMapper.writeValueAsString(Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", message != null ? message : "hi")),
                "max_tokens", 4096,
                "stream", true
        ));

        // 使用 HttpURLConnection 避免 OkHttp 连接池问题
        java.net.URL urlObj = new java.net.URL(url);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) urlObj.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Connection", "close");

        log.info("Claude测试准备发送HTTP请求");
        long httpStart = System.currentTimeMillis();
        try {
            conn.getOutputStream().write(jsonBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            conn.getOutputStream().flush();

            int code = conn.getResponseCode();
            long httpDuration = System.currentTimeMillis() - httpStart;
            log.info("Claude测试HTTP请求返回, 耗时: {}ms, code: {}", httpDuration, code);

            if (code != 200) {
                String errorBody = new String(conn.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                log.error("Claude 测试上游返回错误: {}", errorBody);
                response.getWriter().write("data: {\"error\":\"HTTP " + code + ": " + escapeJson(errorBody) + "\"}\n\n");
                response.getWriter().flush();
                return;
            }

            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            String line;
            int chunkCount = 0;
            int lineCount = 0;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                line = line.trim();
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) continue;
                try {
                    JsonNode json = objectMapper.readTree(data);
                    JsonNode delta = json.path("choices").path(0).path("delta");
                    String content = delta.path("content").asText("");
                    String reasoningContent = delta.path("reasoning_content").asText("");
                    String text = content.isEmpty() ? reasoningContent : content;
                    if (!text.isEmpty()) {
                        String claudeEvt = "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":" + objectMapper.writeValueAsString(text) + "}}";
                        response.getWriter().write("data: " + claudeEvt + "\n\n");
                        response.getWriter().flush();
                        chunkCount++;
                    }
                } catch (Exception e) {
                    log.warn("转换 OpenAI SSE 为 Claude 格式失败: {}", data);
                }
            }
            log.info("Claude 测试流式传输完成, 共 {} 个 chunk, {} 行数据", chunkCount, lineCount);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 通过渠道对象进行流式 OpenAI 聊天（供 TokenController 调用）
     */
    public void streamOpenAIChatByChannel(Channel channel, String model, String message, HttpServletResponse response) throws Exception {
        testOpenAIChatStream(channel.getBaseUrl(), channel.getApiKey(), model, message, response);
    }

    /**
     * 通过渠道对象进行流式 Claude 协议聊天（供 TokenController 调用）
     * 内部将 Claude 格式转换为 OpenAI 格式发送给上游，响应转换回 Claude SSE 格式
     */
    public void streamClaudeChatByChannel(Channel channel, String model, String message, HttpServletResponse response) throws Exception {
        testClaudeChatStream(channel.getBaseUrl(), channel.getApiKey(), model, message, response);
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * 从上游渠道获取支持的模型列表
     */
    public List<String> fetchUpstreamModels(String baseUrl, String apiKey, String type) {
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new BusinessException("请先填写 Base URL 和 API Key");
        }
        String url = baseUrl.replaceAll("/+$", "") + "/v1/models";
        Request.Builder reqBuilder = new Request.Builder().url(url).get();

        // 根据渠道类型设置认证头
        if ("claude".equalsIgnoreCase(type) || "anthropic".equalsIgnoreCase(type)) {
            reqBuilder.addHeader("x-api-key", apiKey);
            reqBuilder.addHeader("anthropic-version", "2023-06-01");
        } else {
            reqBuilder.addHeader("Authorization", "Bearer " + apiKey);
        }

        try (Response response = httpClient.newCall(reqBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                throw new BusinessException("上游接口返回 " + response.code() + ": " + body);
            }
            String body = response.body() != null ? response.body().string() : "";
            JsonNode root = objectMapper.readTree(body);
            List<String> models = new ArrayList<>();
            JsonNode data = root.has("data") ? root.get("data") : root;
            if (data.isArray()) {
                for (JsonNode node : data) {
                    String id = node.has("id") ? node.get("id").asText() : null;
                    if (id != null && !id.isBlank()) {
                        models.add(id);
                    }
                }
            }
            return models;
        } catch (IOException e) {
            log.error("获取上游模型列表失败: {}", e.getMessage());
            throw new BusinessException("连接上游失败: " + e.getMessage());
        }
    }
}
