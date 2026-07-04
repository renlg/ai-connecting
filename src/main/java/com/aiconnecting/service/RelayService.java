package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.entity.Token;
import com.aiconnecting.entity.UsageLog;
import com.aiconnecting.repository.ModelConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * API 中转核心服务 - 负责将请求转发到实际的 AI 提供商
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RelayService {

    private final ChannelService channelService;
    private final TokenService tokenService;
    private final UsageLogService usageLogService;
    private final ModelConfigRepository modelConfigRepository;

    @Autowired(required = false)
    private RateLimitService rateLimitService;

    @Autowired(required = false)
    private okhttp3.Interceptor tracingInterceptor;

    private OkHttpClient httpClient;

    @jakarta.annotation.PostConstruct
    void initHttpClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS);
        if (tracingInterceptor != null) {
            builder.addInterceptor(tracingInterceptor);
        }
        httpClient = builder.build();
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 转发结果，包含响应体和实际使用的渠道
     */
    private record ForwardResult(String response, Channel channel) {}

    /**
     * 中转请求 (非流式) - 支持渠道容错
     */
    public String relayRequest(String tokenKey, String path, String requestBody,
                               String model, HttpServletRequest httpRequest) {
        // 1. 验证 token
        Token token = tokenService.validateTokenKey(tokenKey);

        // 2. 检查额度
        if (token.getQuota() != -1 && token.getUsedQuota() >= token.getQuota()) {
            throw new BusinessException(429, "Token 额度已用完");
        }

        // 3. 检查模型权限
        checkModelPermission(token, model);

        // 4. 获取可用渠道列表（渠道中存储的是模型ID，需要转换）
        String channelModelId = resolveToChannelModelId(model);
        List<Channel> channels = channelService.getActiveChannelsByModel(channelModelId);
        if (channels.isEmpty()) {
            throw new BusinessException(503, "没有可用的渠道支持模型: " + model);
        }

        // 5. Token 限流检查
        if (rateLimitService != null) {
            rateLimitService.checkTokenRateLimit(token.getId(), token.getRateLimit());
        }

        // 6. 转发请求（支持渠道容错，渠道限流在循环内逐个检查）
        long startTime = System.currentTimeMillis();
        ForwardResult result = forwardRequestWithFailover(channels, path, requestBody);
        long duration = System.currentTimeMillis() - startTime;

        // 7. 记录日志和更新额度
        recordUsage(token, result.channel(), model, result.response(), duration, httpRequest, path);

        return result.response();
    }

    /**
     * 中转流式请求 (SSE) - 支持积分计算和渠道容错
     */
    public void relayStreamRequest(String tokenKey, String path, String requestBody,
                                    String model, HttpServletRequest httpRequest,
                                    jakarta.servlet.http.HttpServletResponse httpResponse) throws IOException {
        // 1. 验证 token
        Token token = tokenService.validateTokenKey(tokenKey);

        // 2. 检查额度
        if (token.getQuota() != -1 && token.getUsedQuota() >= token.getQuota()) {
            throw new BusinessException(429, "Token 额度已用完");
        }

        // 3. 检查模型权限
        checkModelPermission(token, model);

        // 4. 获取可用渠道列表（渠道中存储的是模型ID，需要转换）
        String channelModelId = resolveToChannelModelId(model);
        List<Channel> channels = channelService.getActiveChannelsByModel(channelModelId);
        if (channels.isEmpty()) {
            throw new BusinessException(503, "没有可用的渠道支持模型: " + model);
        }

        // 5. 限流检查
        if (rateLimitService != null) {
            rateLimitService.checkTokenRateLimit(token.getId(), token.getRateLimit());
        }

        // 6. 为 chat completions 注入 stream_options 以获取 usage
        String modifiedBody = requestBody;
        if (path.contains("/chat/completions")) {
            try {
                JsonNode jsonBody = objectMapper.readTree(requestBody);
                if (jsonBody.isObject()) {
                    ObjectNode streamOptions = objectMapper.createObjectNode();
                    streamOptions.put("include_usage", true);
                    ((ObjectNode) jsonBody).set("stream_options", streamOptions);
                    modifiedBody = objectMapper.writeValueAsString(jsonBody);
                }
            } catch (Exception e) {
                log.warn("注入 stream_options 失败，使用原始请求体");
            }
        }

        // 7. 设置 SSE 响应头
        httpResponse.setContentType("text/event-stream");
        httpResponse.setCharacterEncoding("UTF-8");
        httpResponse.setHeader("Cache-Control", "no-cache");
        httpResponse.setHeader("Connection", "keep-alive");

        long startTime = System.currentTimeMillis();
        Channel usedChannel = null;
        String lastUsageData = null;

        // 8. 尝试每个渠道直到成功（容错降级）
        for (Channel channel : channels) {
            if (rateLimitService != null) {
                try {
                    rateLimitService.checkChannelRateLimit(channel.getId(), channel.getRateLimit());
                } catch (BusinessException e) {
                    continue; // 该渠道限流，尝试下一个
                }
            }

            try {
                String url = channel.getBaseUrl().replaceAll("/+$", "") + path;
                RequestBody body = RequestBody.create(modifiedBody, MediaType.parse("application/json"));
                Request request = new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer " + channel.getApiKey())
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Accept", "text/event-stream")
                        .post(body)
                        .build();

                try (okhttp3.Response upstreamResponse = httpClient.newCall(request).execute()) {
                    if (!upstreamResponse.isSuccessful()) {
                        String errorBody = upstreamResponse.body() != null ? upstreamResponse.body().string() : "Unknown error";
                        log.warn("渠道 {} 流式请求失败: {} - {}", channel.getId(), upstreamResponse.code(), errorBody);
                        if (upstreamResponse.code() >= 500) {
                            continue; // 服务端错误，尝试下一个渠道
                        }
                        // 客户端错误（4xx）直接返回
                        httpResponse.setStatus(upstreamResponse.code());
                        httpResponse.getWriter().write(errorBody);
                        return;
                    }

                    usedChannel = channel;

                    // 逐行读取 SSE 流并转发，同时捕获 usage 数据
                    ResponseBody responseBody = upstreamResponse.body();
                    if (responseBody != null) {
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(responseBody.byteStream(), StandardCharsets.UTF_8))) {
                            var writer = httpResponse.getWriter();
                            String line;
                            while ((line = reader.readLine()) != null) {
                                writer.write(line);
                                writer.write("\n");
                                writer.flush();

                                // 捕获包含 usage 的 SSE data 行
                                if (line.startsWith("data: ") && !line.equals("data: [DONE]")) {
                                    String data = line.substring(6);
                                    if (data.contains("\"usage\"")) {
                                        lastUsageData = data;
                                    }
                                }
                            }
                        }
                    }
                    break; // 成功，退出渠道循环

                } catch (IOException e) {
                    log.warn("渠道 {} 流式请求异常: {}", channel.getId(), e.getMessage());
                    continue; // 网络异常，尝试下一个渠道
                }
            } catch (Exception e) {
                log.warn("渠道 {} 处理异常: {}", channel.getId(), e.getMessage());
                continue;
            }
        }

        long duration = System.currentTimeMillis() - startTime;

        if (usedChannel == null) {
            if (!httpResponse.isCommitted()) {
                httpResponse.setStatus(503);
                httpResponse.getWriter().write("{\"error\":{\"message\":\"所有渠道均不可用\"}}");
            }
            return;
        }

        // 9. 解析 usage 数据并记录日志
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;

        if (lastUsageData != null) {
            try {
                JsonNode usageNode = objectMapper.readTree(lastUsageData).get("usage");
                if (usageNode != null) {
                    promptTokens = usageNode.has("prompt_tokens") ? usageNode.get("prompt_tokens").asInt() : 0;
                    completionTokens = usageNode.has("completion_tokens") ? usageNode.get("completion_tokens").asInt() : 0;
                    totalTokens = usageNode.has("total_tokens") ? usageNode.get("total_tokens").asInt() : 0;
                }
            } catch (Exception e) {
                log.warn("解析流式响应 usage 数据失败: {}", e.getMessage());
            }
        }

        // 10. 记录使用日志
        double creditCost = usageLogService.calculateCreditCost(model, promptTokens, completionTokens);

        UsageLog usageLog = UsageLog.builder()
                .tokenId(token.getId())
                .channelId(usedChannel.getId())
                .model(model)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .creditCost(creditCost)
                .ip(getClientIp(httpRequest))
                .duration(duration)
                .requestPath(path)
                .build();

        // 11. 事务性记录日志并更新额度
        usageLogService.recordUsageAndQuotas(usageLog, token.getId(), usedChannel.getId(), totalTokens);
        tokenService.addUsedQuota(token.getId(), totalTokens);
    }

    /**
     * Claude Messages API 中转 (非流式)
     * 优先使用 Claude 类型渠道直接发送，没有则转换为 OpenAI 格式发给其他渠道
     */
    public String claudeRelayRequest(String tokenKey, String requestBody,
                                     String model, HttpServletRequest httpRequest) {
        Token token = tokenService.validateTokenKey(tokenKey);
        if (token.getQuota() != -1 && token.getUsedQuota() >= token.getQuota()) {
            throw new BusinessException(429, "Token 额度已用完");
        }
        checkModelPermission(token, model);

        String channelModelId = resolveToChannelModelId(model);
        List<Channel> allChannels = channelService.getActiveChannelsByModel(channelModelId);
        if (allChannels.isEmpty()) {
            throw new BusinessException(503, "没有可用的渠道支持模型: " + model);
        }
        if (rateLimitService != null) {
            rateLimitService.checkTokenRateLimit(token.getId(), token.getRateLimit());
        }

        // 优先找 Claude/Anthropic 类型渠道
        List<Channel> claudeChannels = allChannels.stream()
                .filter(c -> "claude".equalsIgnoreCase(c.getType()) || "anthropic".equalsIgnoreCase(c.getType()))
                .toList();

        long startTime = System.currentTimeMillis();

        if (!claudeChannels.isEmpty()) {
            // 有 Claude 渠道，直接用 Claude 协议发送
            ForwardResult result = forwardClaudeRequestWithFailover(claudeChannels, requestBody);
            long duration = System.currentTimeMillis() - startTime;
            // 解析 Claude 原生响应的 usage
            int promptTokens = 0, completionTokens = 0, totalTokens = 0;
            try {
                JsonNode jsonNode = objectMapper.readTree(result.response());
                JsonNode usage = jsonNode.get("usage");
                if (usage != null) {
                    promptTokens = usage.has("input_tokens") ? usage.get("input_tokens").asInt() : 0;
                    completionTokens = usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0;
                    totalTokens = promptTokens + completionTokens;
                }
            } catch (Exception e) {
                log.warn("解析 Claude 响应 usage 失败: {}", e.getMessage());
            }
            double creditCost = usageLogService.calculateCreditCost(model, promptTokens, completionTokens);
            UsageLog usageLog = UsageLog.builder()
                    .tokenId(token.getId()).channelId(result.channel().getId()).model(model)
                    .promptTokens(promptTokens).completionTokens(completionTokens).totalTokens(totalTokens)
                    .creditCost(creditCost).ip(getClientIp(httpRequest)).duration(duration)
                    .requestPath("/v1/messages").build();
            usageLogService.recordUsageAndQuotas(usageLog, token.getId(), result.channel().getId(), totalTokens);
            tokenService.addUsedQuota(token.getId(), totalTokens);
            return result.response();
        } else {
            // 没有 Claude 渠道，转换为 OpenAI 格式
            String openAiBody = convertClaudeToOpenAiBody(requestBody);
            String openAiResponse = relayRequest(tokenKey, "/v1/chat/completions", openAiBody, model, httpRequest);
            return convertOpenAiToClaudeResponse(openAiResponse);
        }
    }

    /**
     * Claude Messages API 中转 (流式 SSE)
     * 优先使用 Claude 类型渠道直接发送，没有则转换为 OpenAI 格式发给其他渠道
     */
    public void claudeRelayStreamRequest(String tokenKey, String requestBody,
                                          String model, HttpServletRequest httpRequest,
                                          HttpServletResponse httpResponse) throws IOException {
        Token token = tokenService.validateTokenKey(tokenKey);
        if (token.getQuota() != -1 && token.getUsedQuota() >= token.getQuota()) {
            throw new BusinessException(429, "Token 额度已用完");
        }
        checkModelPermission(token, model);

        String channelModelId = resolveToChannelModelId(model);
        List<Channel> allChannels = channelService.getActiveChannelsByModel(channelModelId);
        if (allChannels.isEmpty()) {
            throw new BusinessException(503, "没有可用的渠道支持模型: " + model);
        }
        if (rateLimitService != null) {
            rateLimitService.checkTokenRateLimit(token.getId(), token.getRateLimit());
        }

        // 优先找 Claude/Anthropic 类型渠道
        List<Channel> claudeChannels = allChannels.stream()
                .filter(c -> "claude".equalsIgnoreCase(c.getType()) || "anthropic".equalsIgnoreCase(c.getType()))
                .toList();

        if (!claudeChannels.isEmpty()) {
            // 有 Claude 渠道，直接用 Claude 协议流式发送
            forwardClaudeStreamDirect(claudeChannels, requestBody, model, token, httpRequest, httpResponse);
        } else {
            // 没有 Claude 渠道，转换为 OpenAI 格式
            forwardOpenAiStreamAsClaude(requestBody, model, token, allChannels, httpRequest, httpResponse);
        }
    }

    /**
     * 使用 Claude 渠道直接流式发送（原生 Claude 协议）
     */
    private void forwardClaudeStreamDirect(List<Channel> claudeChannels, String requestBody,
                                            String model, Token token,
                                            HttpServletRequest httpRequest,
                                            HttpServletResponse httpResponse) throws IOException {
        httpResponse.setContentType("text/event-stream");
        httpResponse.setCharacterEncoding("UTF-8");
        httpResponse.setHeader("Cache-Control", "no-cache");
        httpResponse.setHeader("Connection", "keep-alive");

        long startTime = System.currentTimeMillis();
        Channel usedChannel = null;
        String lastUsageData = null;

        for (Channel channel : claudeChannels) {
            if (rateLimitService != null) {
                try {
                    rateLimitService.checkChannelRateLimit(channel.getId(), channel.getRateLimit());
                } catch (BusinessException e) {
                    continue;
                }
            }
            try {
                String url = channel.getBaseUrl().replaceAll("/+$", "") + "/v1/messages";
                RequestBody body = RequestBody.create(requestBody, MediaType.parse("application/json"));
                Request.Builder reqBuilder = new Request.Builder()
                        .url(url)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Accept", "text/event-stream")
                        .post(body);
                applyChannelAuth(reqBuilder, channel);

                try (okhttp3.Response upstreamResponse = httpClient.newCall(reqBuilder.build()).execute()) {
                    if (!upstreamResponse.isSuccessful()) {
                        String errorBody = upstreamResponse.body() != null ? upstreamResponse.body().string() : "Unknown error";
                        log.warn("渠道 {} Claude 流式请求失败: {} - {}", channel.getId(), upstreamResponse.code(), errorBody);
                        if (upstreamResponse.code() >= 500) continue;
                        httpResponse.setStatus(upstreamResponse.code());
                        httpResponse.getWriter().write(errorBody);
                        return;
                    }
                    usedChannel = channel;
                    ResponseBody responseBody = upstreamResponse.body();
                    if (responseBody != null) {
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(responseBody.byteStream(), StandardCharsets.UTF_8))) {
                            var writer = httpResponse.getWriter();
                            String line;
                            while ((line = reader.readLine()) != null) {
                                writer.write(line);
                                writer.write("\n");
                                writer.flush();
                                if (line.startsWith("data: ") && line.contains("\"usage\"")) {
                                    String data = line.substring(6);
                                    if (data.contains("\"output_tokens\"")) {
                                        lastUsageData = data;
                                    }
                                }
                            }
                        }
                    }
                    break;
                }
            } catch (IOException e) {
                log.warn("渠道 {} Claude 流式请求异常: {}", channel.getId(), e.getMessage());
                continue;
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        if (usedChannel == null) {
            if (!httpResponse.isCommitted()) {
                httpResponse.setStatus(503);
                httpResponse.getWriter().write("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"所有渠道均不可用\"}}");
            }
            return;
        }

        int promptTokens = 0, completionTokens = 0;
        if (lastUsageData != null) {
            try {
                JsonNode usageNode = objectMapper.readTree(lastUsageData).get("usage");
                if (usageNode != null) {
                    completionTokens = usageNode.has("output_tokens") ? usageNode.get("output_tokens").asInt() : 0;
                }
            } catch (Exception e) {
                log.warn("解析 Claude 流式 usage 失败: {}", e.getMessage());
            }
        }
        int totalTokens = promptTokens + completionTokens;
        double creditCost = usageLogService.calculateCreditCost(model, promptTokens, completionTokens);
        UsageLog usageLog = UsageLog.builder()
                .tokenId(token.getId()).channelId(usedChannel.getId()).model(model)
                .promptTokens(promptTokens).completionTokens(completionTokens).totalTokens(totalTokens)
                .creditCost(creditCost).ip(getClientIp(httpRequest)).duration(duration)
                .requestPath("/v1/messages").build();
        usageLogService.recordUsageAndQuotas(usageLog, token.getId(), usedChannel.getId(), totalTokens);
        tokenService.addUsedQuota(token.getId(), totalTokens);
    }

    /**
     * 没有 Claude 渠道时，将请求转为 OpenAI 格式发送，SSE 响应转回 Claude SSE 格式
     */
    private void forwardOpenAiStreamAsClaude(String requestBody, String model, Token token,
                                              List<Channel> channels,
                                              HttpServletRequest httpRequest,
                                              HttpServletResponse httpResponse) throws IOException {
        String openAiBody = convertClaudeToOpenAiBody(requestBody);
        try {
            JsonNode jsonBody = objectMapper.readTree(openAiBody);
            ((ObjectNode) jsonBody).put("stream", true);
            ObjectNode streamOptions = objectMapper.createObjectNode();
            streamOptions.put("include_usage", true);
            ((ObjectNode) jsonBody).set("stream_options", streamOptions);
            openAiBody = objectMapper.writeValueAsString(jsonBody);
        } catch (Exception e) {
            log.warn("注入 stream_options 失败: {}", e.getMessage());
        }

        httpResponse.setContentType("text/event-stream");
        httpResponse.setCharacterEncoding("UTF-8");
        httpResponse.setHeader("Cache-Control", "no-cache");
        httpResponse.setHeader("Connection", "keep-alive");

        var writer = httpResponse.getWriter();
        String msgId = "msg_" + System.currentTimeMillis();
        writer.write("data: {\"type\":\"message_start\",\"message\":{\"id\":\"" + msgId + "\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"" + model + "\",\"stop_reason\":null,\"stop_sequence\":null}}");
        writer.write("\n\n");
        writer.write("data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
        writer.write("\n\n");
        writer.flush();

        long startTime = System.currentTimeMillis();
        Channel usedChannel = null;
        int promptTokens = 0, completionTokens = 0;

        for (Channel channel : channels) {
            if (rateLimitService != null) {
                try {
                    rateLimitService.checkChannelRateLimit(channel.getId(), channel.getRateLimit());
                } catch (BusinessException e) {
                    continue;
                }
            }
            try {
                String url = channel.getBaseUrl().replaceAll("/+$", "") + "/v1/chat/completions";
                RequestBody body = RequestBody.create(openAiBody, MediaType.parse("application/json"));
                Request request = new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer " + channel.getApiKey())
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Accept", "text/event-stream")
                        .post(body)
                        .build();

                try (okhttp3.Response upstreamResponse = httpClient.newCall(request).execute()) {
                    if (!upstreamResponse.isSuccessful()) {
                        String errorBody = upstreamResponse.body() != null ? upstreamResponse.body().string() : "Unknown error";
                        log.warn("渠道 {} OpenAI-as-Claude 流式请求失败: {} - {}", channel.getId(), upstreamResponse.code(), errorBody);
                        if (upstreamResponse.code() >= 500) continue;
                        writer.write("data: {\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"上游错误\"}}");
                        writer.write("\n\n");
                        writer.flush();
                        return;
                    }
                    usedChannel = channel;
                    ResponseBody responseBody = upstreamResponse.body();
                    if (responseBody != null) {
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(responseBody.byteStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.startsWith("data: ")) {
                                    String data = line.substring(6).trim();
                                    if ("[DONE]".equals(data)) continue;
                                    try {
                                        JsonNode json = objectMapper.readTree(data);
                                        if (json.has("usage")) {
                                            JsonNode usage = json.get("usage");
                                            promptTokens = usage.path("prompt_tokens").asInt(0);
                                            completionTokens = usage.path("completion_tokens").asInt(0);
                                        }
                                        String content = json.path("choices").path(0).path("delta").path("content").asText("");
                                        String reasoningContent = json.path("choices").path(0).path("delta").path("reasoning_content").asText("");
                                        String text = content.isEmpty() ? reasoningContent : content;
                                        if (!text.isEmpty()) {
                                            String claudeEvt = "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":" + objectMapper.writeValueAsString(text) + "}}";
                                            writer.write("data: " + claudeEvt);
                                            writer.write("\n\n");
                                            writer.flush();
                                        }
                                    } catch (Exception parseEx) {
                                        log.warn("转换 OpenAI SSE 为 Claude 格式失败: {}", data);
                                    }
                                }
                            }
                        }
                    }
                    break;
                }
            } catch (IOException e) {
                log.warn("渠道 {} OpenAI-as-Claude 流式请求异常: {}", channel.getId(), e.getMessage());
                continue;
            }
        }

        if (usedChannel == null) {
            writer.write("data: {\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"所有渠道均不可用\"}}");
            writer.write("\n\n");
            writer.flush();
            return;
        }

        writer.write("data: {\"type\":\"content_block_stop\",\"index\":0}");
        writer.write("\n\n");
        writer.write("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":" + completionTokens + "}}");
        writer.write("\n\n");
        writer.write("data: {\"type\":\"message_stop\"}");
        writer.write("\n\n");
        writer.flush();

        long duration = System.currentTimeMillis() - startTime;
        int totalTokens = promptTokens + completionTokens;
        double creditCost = usageLogService.calculateCreditCost(model, promptTokens, completionTokens);
        UsageLog usageLog = UsageLog.builder()
                .tokenId(token.getId()).channelId(usedChannel.getId()).model(model)
                .promptTokens(promptTokens).completionTokens(completionTokens).totalTokens(totalTokens)
                .creditCost(creditCost).ip(getClientIp(httpRequest)).duration(duration)
                .requestPath("/v1/messages").build();
        usageLogService.recordUsageAndQuotas(usageLog, token.getId(), usedChannel.getId(), totalTokens);
        tokenService.addUsedQuota(token.getId(), totalTokens);
    }

    /**
     * 根据渠道类型设置认证头
     */
    private void applyChannelAuth(Request.Builder builder, Channel channel) {
        if ("claude".equalsIgnoreCase(channel.getType()) || "anthropic".equalsIgnoreCase(channel.getType())) {
            builder.addHeader("x-api-key", channel.getApiKey());
            builder.addHeader("anthropic-version", "2023-06-01");
        } else {
            builder.addHeader("Authorization", "Bearer " + channel.getApiKey());
        }
    }

    /**
     * Claude 协议带容错的请求转发
     */
    private ForwardResult forwardClaudeRequestWithFailover(List<Channel> channels, String requestBody) {
        for (Channel channel : channels) {
            if (rateLimitService != null) {
                try {
                    rateLimitService.checkChannelRateLimit(channel.getId(), channel.getRateLimit());
                } catch (BusinessException e) {
                    continue;
                }
            }
            try {
                String url = channel.getBaseUrl().replaceAll("/+$", "") + "/v1/messages";
                RequestBody body = RequestBody.create(requestBody, MediaType.parse("application/json"));
                Request.Builder reqBuilder = new Request.Builder()
                        .url(url)
                        .addHeader("Content-Type", "application/json")
                        .post(body);
                applyChannelAuth(reqBuilder, channel);

                try (okhttp3.Response response = httpClient.newCall(reqBuilder.build()).execute()) {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        log.error("Claude upstream API error: {} - {}", response.code(), responseBody);
                        if (response.code() >= 500) {
                            continue;
                        }
                        throw new BusinessException(response.code(), "上游 API 错误: " + responseBody);
                    }
                    return new ForwardResult(responseBody, channel);
                }
            } catch (BusinessException e) {
                if (e.getCode() >= 500 || e.getCode() == 502) {
                    log.warn("渠道 {} Claude 请求失败 ({}), 尝试下一个渠道", channel.getId(), e.getCode());
                    continue;
                }
                throw e;
            } catch (IOException e) {
                log.warn("渠道 {} Claude 请求异常: {}", channel.getId(), e.getMessage());
                continue;
            }
        }
        throw new BusinessException(503, "所有渠道均不可用");
    }

    /**
     * 解析模型名称：如果传入的是 displayName，转换为实际的 name
     */
    public String resolveModelName(String model) {
        if (model == null || model.isEmpty()) {
            return model;
        }
        // 先尝试按 name 查找，如果存在则直接返回
        if (!modelConfigRepository.findByName(model).isEmpty()) {
            return model;
        }
        // 否则尝试按 displayName 查找
        List<ModelConfig> byDisplayName = modelConfigRepository.findByDisplayName(model);
        if (!byDisplayName.isEmpty()) {
            return byDisplayName.get(0).getName();
        }
        return model; // 都找不到则保持原值
    }

    /**
     * 将实际模型名转换为渠道中存储的模型ID（用于渠道查找）
     */
    public String resolveToChannelModelId(String modelName) {
        if (modelName == null || modelName.isEmpty()) return modelName;
        List<ModelConfig> models = modelConfigRepository.findByName(modelName);
        if (!models.isEmpty()) {
            return String.valueOf(models.get(0).getId());
        }
        return modelName;
    }

    /**
     * 检查 Token 的模型权限
     */
    private void checkModelPermission(Token token, String model) {
        if (token.getAllowedModels() != null && !token.getAllowedModels().isEmpty()) {
            String[] allowed = token.getAllowedModels().split(",");
            boolean found = false;
            for (String m : allowed) {
                if (m.trim().equals(model)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new BusinessException(403, "该 Token 无权使用模型: " + model);
            }
        }
    }

    /**
     * 带容错的请求转发 - 失败自动切换渠道，同时检查渠道限流
     */
    private ForwardResult forwardRequestWithFailover(List<Channel> channels, String path, String requestBody) {
        for (Channel channel : channels) {
            // 在循环内逐个检查渠道限流，被限流的渠道直接跳过
            if (rateLimitService != null) {
                try {
                    rateLimitService.checkChannelRateLimit(channel.getId(), channel.getRateLimit());
                } catch (BusinessException e) {
                    continue; // 该渠道限流，尝试下一个
                }
            }
            try {
                String response = forwardRequest(channel, path, requestBody);
                return new ForwardResult(response, channel);
            } catch (BusinessException e) {
                if (e.getCode() >= 500 || e.getCode() == 502) {
                    log.warn("渠道 {} 请求失败 ({}), 尝试下一个渠道", channel.getId(), e.getCode());
                    continue;
                }
                throw e; // 4xx 错误不重试
            }
        }
        throw new BusinessException(503, "所有渠道均不可用");
    }

    /**
     * 转发请求到上游
     */
    private String forwardRequest(Channel channel, String path, String requestBody) {
        String url = channel.getBaseUrl().replaceAll("/+$", "") + path;

        RequestBody body = RequestBody.create(requestBody, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + channel.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (okhttp3.Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.error("Upstream API error: {} - {}", response.code(), responseBody);
                throw new BusinessException(response.code(), "上游 API 错误: " + responseBody);
            }

            return responseBody;
        } catch (IOException e) {
            log.error("Failed to forward request to channel {}: {}", channel.getId(), e.getMessage());
            throw new BusinessException(502, "渠道请求失败: " + e.getMessage());
        }
    }

    /**
     * 记录使用情况和更新额度（非流式）
     */
    private void recordUsage(Token token, Channel channel, String model,
                             String response, long duration, HttpServletRequest httpRequest, String path) {
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;

        try {
            JsonNode jsonNode = objectMapper.readTree(response);
            JsonNode usage = jsonNode.get("usage");
            if (usage != null) {
                promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : 0;
                completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : 0;
                totalTokens = usage.has("total_tokens") ? usage.get("total_tokens").asInt() : 0;
            }
        } catch (Exception e) {
            log.warn("Failed to parse usage from response");
        }

        double creditCost = usageLogService.calculateCreditCost(model, promptTokens, completionTokens);

        UsageLog usageLog = UsageLog.builder()
                .tokenId(token.getId())
                .channelId(channel.getId())
                .model(model)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .creditCost(creditCost)
                .ip(getClientIp(httpRequest))
                .duration(duration)
                .requestPath(path)
                .build();

        // 事务性记录日志并更新额度
        usageLogService.recordUsageAndQuotas(usageLog, token.getId(), channel.getId(), totalTokens);
        tokenService.addUsedQuota(token.getId(), totalTokens);
    }

    private String getClientIp(HttpServletRequest request) {
        if (request == null) return "test";
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    /**
     * 将 Claude 格式的请求体转换为 OpenAI 格式
     * 处理 Claude 的 system 字段和 content 数组格式
     */
    private String convertClaudeToOpenAiBody(String claudeBody) {
        try {
            JsonNode node = objectMapper.readTree(claudeBody);
            Map<String, Object> openAiMap = new java.util.LinkedHashMap<>();
            if (node.has("model")) {
                openAiMap.put("model", node.get("model").asText());
            }
            // 构建 messages 数组
            List<Map<String, Object>> messages = new ArrayList<>();
            // Claude 的 system 字段转为 system message
            if (node.has("system")) {
                JsonNode systemNode = node.get("system");
                String systemText;
                if (systemNode.isTextual()) {
                    systemText = systemNode.asText();
                } else if (systemNode.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode block : systemNode) {
                        if (block.has("text")) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append(block.get("text").asText());
                        }
                    }
                    systemText = sb.toString();
                } else {
                    systemText = systemNode.toString();
                }
                if (!systemText.isEmpty()) {
                    messages.add(Map.of("role", "system", "content", systemText));
                }
            }
            // Claude 的 messages 数组，处理 content 可以是字符串或数组
            if (node.has("messages")) {
                for (JsonNode msg : node.get("messages")) {
                    String role = msg.path("role").asText("");
                    JsonNode contentNode = msg.get("content");
                    String contentText;
                    if (contentNode == null || contentNode.isNull()) {
                        contentText = "";
                    } else if (contentNode.isTextual()) {
                        contentText = contentNode.asText();
                    } else if (contentNode.isArray()) {
                        StringBuilder sb = new StringBuilder();
                        for (JsonNode block : contentNode) {
                            if ("text".equals(block.path("type").asText()) && block.has("text")) {
                                if (sb.length() > 0) sb.append("\n");
                                sb.append(block.get("text").asText());
                            }
                        }
                        contentText = sb.toString();
                    } else {
                        contentText = contentNode.toString();
                    }
                    messages.add(Map.of("role", role, "content", contentText));
                }
            }
            openAiMap.put("messages", messages);
            if (node.has("max_tokens")) {
                openAiMap.put("max_tokens", node.get("max_tokens").asInt());
            }
            if (node.has("stream")) {
                openAiMap.put("stream", node.get("stream").asBoolean());
            }
            if (node.has("temperature")) {
                openAiMap.put("temperature", node.get("temperature").asDouble());
            }
            if (node.has("top_p")) {
                openAiMap.put("top_p", node.get("top_p").asDouble());
            }
            return objectMapper.writeValueAsString(openAiMap);
        } catch (Exception e) {
            log.warn("转换 Claude 请求体失败，使用原始 body: {}", e.getMessage());
            return claudeBody;
        }
    }

    /**
     * 将 OpenAI 格式的响应转换为 Claude Messages API 格式
     */
    private String convertOpenAiToClaudeResponse(String openAiResponse) {
        try {
            JsonNode node = objectMapper.readTree(openAiResponse);
            Map<String, Object> claudeMap = new java.util.LinkedHashMap<>();
            claudeMap.put("id", "msg_" + System.currentTimeMillis());
            claudeMap.put("type", "message");
            claudeMap.put("role", "assistant");
            String content = node.path("choices").path(0).path("message").path("content").asText("");
            claudeMap.put("content", List.of(Map.of("type", "text", "text", content)));
            if (node.has("model")) {
                claudeMap.put("model", node.get("model").asText());
            }
            if (node.has("usage")) {
                JsonNode usage = node.get("usage");
                Map<String, Object> claudeUsage = new java.util.LinkedHashMap<>();
                claudeUsage.put("input_tokens", usage.path("prompt_tokens").asInt(0));
                claudeUsage.put("output_tokens", usage.path("completion_tokens").asInt(0));
                claudeMap.put("usage", claudeUsage);
            }
            claudeMap.put("stop_reason", "end_turn");
            claudeMap.put("stop_sequence", null);
            return objectMapper.writeValueAsString(claudeMap);
        } catch (Exception e) {
            log.warn("转换 OpenAI 响应为 Claude 格式失败，返回原始响应: {}", e.getMessage());
            return openAiResponse;
        }
    }
}
