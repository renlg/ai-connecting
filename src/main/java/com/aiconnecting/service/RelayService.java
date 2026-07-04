package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.ProtocolConverter;
import com.aiconnecting.common.SseUtils;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.entity.Token;
import com.aiconnecting.entity.UsageLog;
import com.aiconnecting.service.ModelConfigService;
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
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
    private final ModelConfigService modelConfigService;

    @Autowired(required = false)
    private RateLimitService rateLimitService;

    @Autowired(required = false)
    private okhttp3.Interceptor tracingInterceptor;

    private OkHttpClient httpClient;

    @jakarta.annotation.PostConstruct
    void initHttpClient() {
        // 禁用 HttpURLConnection 的 keep-alive，防止复用陈旧连接导致卡死
        System.setProperty("http.keepAlive", "false");

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

    /** 模型名称解析缓存，避免每次请求查库，缓存 5 分钟 */
    private final ConcurrentHashMap<String, CachedValue> modelNameCache = new ConcurrentHashMap<>();
    private static final long MODEL_CACHE_TTL_MS = 5 * 60 * 1000L;

    private record CachedValue(String value, long cachedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > MODEL_CACHE_TTL_MS;
        }
    }

    /**
     * 转发结果，包含响应体和实际使用的渠道
     */
    private record ForwardResult(String response, Channel channel) {}

    /**
     * 中转预检结果，包含验证后的 token 和可用渠道列表
     */
    private record RelayContext(Token token, List<Channel> channels) {}

    /**
     * 通用中转预检：验证 token、检查额度、检查模型权限、获取渠道、限流检查
     */
    private RelayContext validateAndPrepare(String tokenKey, String model) {
        Token token = tokenService.validateTokenKey(tokenKey);
        if (token.getQuota() != -1 && token.getUsedQuota() >= token.getQuota()) {
            throw new BusinessException(429, "Token 额度已用完");
        }
        checkModelPermission(token, model);
        String channelModelId = resolveToChannelModelId(model);
        List<Channel> channels = channelService.getActiveChannelsByModel(channelModelId);
        if (channels.isEmpty()) {
            throw new BusinessException(503, "没有可用的渠道支持模型: " + model);
        }
        if (rateLimitService != null) {
            rateLimitService.checkTokenRateLimit(token.getId(), token.getRateLimit());
        }
        return new RelayContext(token, channels);
    }

    /**
     * 创建 SSE 流式连接的 HttpURLConnection
     */
    private HttpURLConnection createSseConnection(Channel channel, String path, String requestBody) throws IOException {
        String url = channel.getBaseUrl().replaceAll("/+$", "") + path;
        log.info("流式请求: url={}, channel={}", url, channel.getId());
        java.net.URL urlObj = new java.net.URL(url);
        HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setRequestProperty("Connection", "close");
        applyChannelAuthToConnection(conn, channel);
        conn.getOutputStream().write(requestBody.getBytes(StandardCharsets.UTF_8));
        conn.getOutputStream().flush();
        return conn;
    }

    /**
     * 记录流式请求的使用日志并更新额度
     */
    private void recordStreamUsage(Token token, Channel channel, String model,
                                    int promptTokens, int completionTokens,
                                    long duration, HttpServletRequest httpRequest, String path) {
        int totalTokens = promptTokens + completionTokens;
        double creditCost = usageLogService.calculateCreditCost(model, promptTokens, completionTokens);
        UsageLog usageLog = UsageLog.builder()
                .tokenId(token.getId()).channelId(channel.getId()).model(model)
                .promptTokens(promptTokens).completionTokens(completionTokens).totalTokens(totalTokens)
                .creditCost(creditCost).ip(getClientIp(httpRequest)).duration(duration)
                .requestPath(path).build();
        usageLogService.recordUsageAndQuotas(usageLog, token.getId(), channel.getId(), totalTokens, token.getUserId());
        tokenService.addUsedQuota(token.getId(), totalTokens);
    }

    /**
     * 检查渠道限流，如果被限流返回 true
     */
    private boolean isChannelRateLimited(Channel channel) {
        if (rateLimitService != null) {
            try {
                rateLimitService.checkChannelRateLimit(channel.getId(), channel.getRateLimit());
            } catch (BusinessException e) {
                return true;
            }
        }
        return false;
    }

    /**
     * 中转请求 (非流式) - 支持渠道容错
     */
    public String relayRequest(String tokenKey, String path, String requestBody,
                               String model, HttpServletRequest httpRequest) {
        RelayContext ctx = validateAndPrepare(tokenKey, model);
        long startTime = System.currentTimeMillis();
        ForwardResult result = forwardRequestWithFailover(ctx.channels(), path, requestBody);
        long duration = System.currentTimeMillis() - startTime;
        recordUsage(ctx.token(), result.channel(), model, result.response(), duration, httpRequest, path);
        return result.response();
    }

    /**
     * 中转流式请求 (SSE) - 支持积分计算和渠道容错
     */
    public void relayStreamRequest(String tokenKey, String path, String requestBody,
                                    String model, HttpServletRequest httpRequest,
                                    jakarta.servlet.http.HttpServletResponse httpResponse) throws IOException {
        RelayContext ctx = validateAndPrepare(tokenKey, model);

        // 为 chat completions 注入 stream_options 以获取 usage
        String modifiedBody = injectStreamOptions(requestBody, path);

        SseUtils.setSseHeaders(httpResponse);
        long startTime = System.currentTimeMillis();
        Channel usedChannel = null;
        String lastUsageData = null;

        for (Channel channel : ctx.channels()) {
            if (isChannelRateLimited(channel)) continue;
            try {
                HttpURLConnection conn = createSseConnection(channel, path, modifiedBody);
                try {
                    int code = conn.getResponseCode();
                    log.info("HTTP请求返回, code: {}, channel: {}", code, channel.getId());
                    if (code >= 500) { conn.disconnect(); continue; }
                    if (code != 200) {
                        String errorBody = conn.getErrorStream() != null
                                ? new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8) : "";
                        log.warn("渠道 {} 流式请求失败: {} - {}", channel.getId(), code, errorBody);
                        httpResponse.setStatus(code);
                        httpResponse.getWriter().write(errorBody.isEmpty()
                                ? "{\"error\":{\"message\":\"上游返回 HTTP " + code + "\"}}" : errorBody);
                        conn.disconnect();
                        return;
                    }
                    usedChannel = channel;
                    lastUsageData = streamSseResponse(conn, httpResponse, null);
                } finally {
                    conn.disconnect();
                }
                break;
            } catch (Exception e) {
                log.warn("渠道 {} 流式请求异常: {}", channel.getId(), e.getMessage());
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

        int promptTokens = 0, completionTokens = 0;
        if (lastUsageData != null) {
            try {
                JsonNode usageNode = objectMapper.readTree(lastUsageData).get("usage");
                if (usageNode != null) {
                    promptTokens = usageNode.has("prompt_tokens") ? usageNode.get("prompt_tokens").asInt() : 0;
                    completionTokens = usageNode.has("completion_tokens") ? usageNode.get("completion_tokens").asInt() : 0;
                }
            } catch (Exception e) {
                log.warn("解析流式响应 usage 数据失败: {}", e.getMessage());
            }
        }
        recordStreamUsage(ctx.token(), usedChannel, model, promptTokens, completionTokens, duration, httpRequest, path);
    }

    /**
     * Claude Messages API 中转 (非流式)
     * 优先使用 Claude 类型渠道直接发送，没有则转换为 OpenAI 格式发给其他渠道
     */
    public String claudeRelayRequest(String tokenKey, String requestBody,
                                     String model, HttpServletRequest httpRequest) {
        RelayContext ctx = validateAndPrepare(tokenKey, model);
        List<Channel> claudeChannels = filterClaudeChannels(ctx.channels());
        long startTime = System.currentTimeMillis();

        if (!claudeChannels.isEmpty()) {
            ForwardResult result = forwardClaudeRequestWithFailover(claudeChannels, requestBody);
            long duration = System.currentTimeMillis() - startTime;
            int promptTokens = 0, completionTokens = 0;
            try {
                JsonNode jsonNode = objectMapper.readTree(result.response());
                JsonNode usage = jsonNode.get("usage");
                if (usage != null) {
                    promptTokens = usage.has("input_tokens") ? usage.get("input_tokens").asInt() : 0;
                    completionTokens = usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0;
                }
            } catch (Exception e) {
                log.warn("解析 Claude 响应 usage 失败: {}", e.getMessage());
            }
            recordStreamUsage(ctx.token(), result.channel(), model, promptTokens, completionTokens, duration, httpRequest, "/v1/messages");
            return result.response();
        } else {
            String openAiBody = ProtocolConverter.convertClaudeToOpenAiBody(requestBody);
            String openAiResponse = relayRequest(tokenKey, "/v1/chat/completions", openAiBody, model, httpRequest);
            return ProtocolConverter.convertOpenAiToClaudeResponse(openAiResponse);
        }
    }

    /**
     * Claude Messages API 中转 (流式 SSE)
     * 优先使用 Claude 类型渠道直接发送，没有则转换为 OpenAI 格式发给其他渠道
     */
    public void claudeRelayStreamRequest(String tokenKey, String requestBody,
                                          String model, HttpServletRequest httpRequest,
                                          HttpServletResponse httpResponse) throws IOException {
        log.info("[Claude流式] 开始处理, model={}", model);
        RelayContext ctx = validateAndPrepare(tokenKey, model);
        log.info("[Claude流式] Token验证通过, id={}, 渠道数={}", ctx.token().getId(), ctx.channels().size());

        List<Channel> claudeChannels = filterClaudeChannels(ctx.channels());
        if (!claudeChannels.isEmpty()) {
            log.info("[Claude流式] 使用 Claude 渠道直接发送, {} 个Claude渠道", claudeChannels.size());
            forwardClaudeStreamDirect(claudeChannels, requestBody, model, ctx.token(), httpRequest, httpResponse);
        } else {
            log.info("[Claude流式] 无 Claude 渠道，转 OpenAI 格式发送");
            forwardOpenAiStreamAsClaude(requestBody, model, ctx.token(), ctx.channels(), httpRequest, httpResponse);
        }
        log.info("[Claude流式] 处理完成");
    }

    /**
     * 过滤出 Claude/Anthropic 类型的渠道
     */
    private List<Channel> filterClaudeChannels(List<Channel> channels) {
        return channels.stream()
                .filter(c -> "claude".equalsIgnoreCase(c.getType()) || "anthropic".equalsIgnoreCase(c.getType()))
                .toList();
    }

    /**
     * 为请求体注入 stream_options 以获取 usage
     */
    private String injectStreamOptions(String requestBody, String path) {
        if (!path.contains("/chat/completions")) return requestBody;
        try {
            JsonNode jsonBody = objectMapper.readTree(requestBody);
            if (jsonBody.isObject()) {
                ObjectNode streamOptions = objectMapper.createObjectNode();
                streamOptions.put("include_usage", true);
                ((ObjectNode) jsonBody).set("stream_options", streamOptions);
                return objectMapper.writeValueAsString(jsonBody);
            }
        } catch (Exception e) {
            log.warn("注入 stream_options 失败，使用原始请求体");
        }
        return requestBody;
    }

    /**
     * 流式读取上游 SSE 响应并透传给客户端，返回最后包含 usage 的 data 行
     */
    private String streamSseResponse(HttpURLConnection conn, HttpServletResponse httpResponse,
                                      java.util.function.Predicate<String> usageFilter) throws IOException {
        String lastUsageData = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            var writer = httpResponse.getWriter();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    writer.write("\n");
                } else {
                    writer.write(line);
                    writer.write("\n");
                }
                writer.flush();
                if (line.startsWith("data: ") && !line.equals("data: [DONE]")) {
                    String data = line.substring(6);
                    if (usageFilter != null ? usageFilter.test(data) : data.contains("\"usage\"")) {
                        lastUsageData = data;
                    }
                }
            }
        }
        return lastUsageData;
    }

    /**
     * 使用 Claude 渠道直接流式发送（原生 Claude 协议）
     */
    private void forwardClaudeStreamDirect(List<Channel> claudeChannels, String requestBody,
                                            String model, Token token,
                                            HttpServletRequest httpRequest,
                                            HttpServletResponse httpResponse) throws IOException {
        SseUtils.setSseHeaders(httpResponse);

        long startTime = System.currentTimeMillis();
        Channel usedChannel = null;
        String lastUsageData = null;

        for (Channel channel : claudeChannels) {
            if (isChannelRateLimited(channel)) continue;
            try {
                HttpURLConnection conn = createSseConnection(channel, "/v1/messages", requestBody);
                try {
                    int code = conn.getResponseCode();
                    log.info("HTTP请求返回, code: {}, channel: {}", code, channel.getId());
                    if (code >= 500) { conn.disconnect(); continue; }
                    if (code != 200) {
                        String errorBody = conn.getErrorStream() != null
                                ? new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8) : "";
                        log.warn("渠道 {} Claude 流式请求失败: {} - {}", channel.getId(), code, errorBody);
                        httpResponse.setStatus(code);
                        httpResponse.getWriter().write(errorBody.isEmpty()
                                ? "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"上游返回 HTTP " + code + "\"}}" : errorBody);
                        conn.disconnect();
                        return;
                    }
                    usedChannel = channel;
                    lastUsageData = streamSseResponse(conn, httpResponse,
                            data -> data.contains("\"output_tokens\""));
                    // 发送 [DONE] 标记以兼容前端 SSE 解析
                    var writer = httpResponse.getWriter();
                    writer.write("data: [DONE]\n\n");
                    writer.flush();
                } finally {
                    conn.disconnect();
                }
                break;
            } catch (Exception e) {
                log.warn("渠道 {} Claude 流式请求异常: {}", channel.getId(), e.getMessage());
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
        recordStreamUsage(token, usedChannel, model, promptTokens, completionTokens, duration, httpRequest, "/v1/messages");
    }

    /**
     * 没有 Claude 渠道时，将请求转为 OpenAI 格式发送，SSE 响应转回 Claude SSE 格式
     */
    private void forwardOpenAiStreamAsClaude(String requestBody, String model, Token token,
                                              List<Channel> channels,
                                              HttpServletRequest httpRequest,
                                              HttpServletResponse httpResponse) throws IOException {
        String openAiBody = ProtocolConverter.convertClaudeToOpenAiBody(requestBody);
        openAiBody = injectStreamOptions(openAiBody, "/v1/chat/completions");

        SseUtils.setSseHeaders(httpResponse);
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
            if (isChannelRateLimited(channel)) continue;
            try {
                HttpURLConnection conn = createSseConnection(channel, "/v1/chat/completions", openAiBody);
                try {
                    int code = conn.getResponseCode();
                    log.info("HTTP请求返回, code: {}, channel: {}", code, channel.getId());
                    if (code >= 500) { conn.disconnect(); continue; }
                    if (code != 200) {
                        log.warn("渠道 {} OpenAI-as-Claude 流式请求失败: {}", channel.getId(), code);
                        writer.write("data: {\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"上游错误\"}}\n\n");
                        writer.flush();
                        conn.disconnect();
                        return;
                    }
                    usedChannel = channel;
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
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
                                        writer.write("data: " + claudeEvt + "\n\n");
                                        writer.flush();
                                    }
                                } catch (Exception parseEx) {
                                    log.warn("转换 OpenAI SSE 为 Claude 格式失败: {}", data);
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect();
                }
                break;
            } catch (Exception e) {
                log.warn("渠道 {} OpenAI-as-Claude 流式请求异常: {}", channel.getId(), e.getMessage());
            }
        }

        if (usedChannel == null) {
            writer.write("data: {\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"所有渠道均不可用\"}}\n\n");
            writer.flush();
            return;
        }

        writer.write("data: {\"type\":\"content_block_stop\",\"index\":0}\n\n");
        writer.write("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":" + completionTokens + "}}\n\n");
        writer.write("data: {\"type\":\"message_stop\"}\n\n");
        writer.write("data: [DONE]\n\n");
        writer.flush();

        long duration = System.currentTimeMillis() - startTime;
        recordStreamUsage(token, usedChannel, model, promptTokens, completionTokens, duration, httpRequest, "/v1/messages");
    }

    /**
     * 根据渠道类型为 OkHttp Request.Builder 设置认证头
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
     * 根据渠道类型为 HttpURLConnection 设置认证头
     */
    private void applyChannelAuthToConnection(HttpURLConnection conn, Channel channel) {
        if ("claude".equalsIgnoreCase(channel.getType()) || "anthropic".equalsIgnoreCase(channel.getType())) {
            conn.setRequestProperty("x-api-key", channel.getApiKey());
            conn.setRequestProperty("anthropic-version", "2023-06-01");
        } else {
            conn.setRequestProperty("Authorization", "Bearer " + channel.getApiKey());
        }
    }

    /**
     * Claude 协议带容错的请求转发
     */
    private ForwardResult forwardClaudeRequestWithFailover(List<Channel> channels, String requestBody) {
        for (Channel channel : channels) {
            if (isChannelRateLimited(channel)) continue;
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
     * 解析模型名称：如果传入的是 displayName，转换为实际的 name（带缓存）
     */
    public String resolveModelName(String model) {
        if (model == null || model.isEmpty()) {
            return model;
        }
        String cacheKey = "resolve:" + model;
        CachedValue cached = modelNameCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.value();
        }
        // 先尝试按 name 查找，如果存在则直接返回
        if (!modelConfigService.findByName(model).isEmpty()) {
            modelNameCache.put(cacheKey, new CachedValue(model, System.currentTimeMillis()));
            return model;
        }
        // 否则尝试按 displayName 查找
        List<ModelConfig> byDisplayName = modelConfigService.findByDisplayName(model);
        if (!byDisplayName.isEmpty()) {
            String resolved = byDisplayName.get(0).getName();
            modelNameCache.put(cacheKey, new CachedValue(resolved, System.currentTimeMillis()));
            return resolved;
        }
        return model; // 都找不到则保持原值
    }

    /**
     * 将实际模型名转换为渠道中存储的模型ID（用于渠道查找，带缓存）
     */
    public String resolveToChannelModelId(String modelName) {
        if (modelName == null || modelName.isEmpty()) return modelName;
        String cacheKey = "channelId:" + modelName;
        CachedValue cached = modelNameCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.value();
        }
        List<ModelConfig> models = modelConfigService.findByName(modelName);
        if (!models.isEmpty()) {
            String id = String.valueOf(models.get(0).getId());
            modelNameCache.put(cacheKey, new CachedValue(id, System.currentTimeMillis()));
            return id;
        }
        return modelName;
    }

    /**
     * 清除模型名称解析缓存（模型配置变更时调用）
     */
    public void clearModelNameCache() {
        modelNameCache.clear();
    }

    /** 允许模型解析缓存，带 TTL 避免模型权限变更后不生效 */
    private final ConcurrentHashMap<String, CachedAllowedModels> allowedModelsCache = new ConcurrentHashMap<>();
    private static final long ALLOWED_MODELS_CACHE_TTL_MS = 2 * 60 * 1000L;

    private record CachedAllowedModels(Set<String> models, long cachedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > ALLOWED_MODELS_CACHE_TTL_MS;
        }
    }

    /**
     * 检查 Token 的模型权限（缓存解析结果，带 TTL）
     */
    private void checkModelPermission(Token token, String model) {
        if (token.getAllowedModels() != null && !token.getAllowedModels().isEmpty()) {
            CachedAllowedModels cached = allowedModelsCache.get(token.getAllowedModels());
            Set<String> allowed;
            if (cached != null && !cached.isExpired()) {
                allowed = cached.models();
            } else {
                allowed = Arrays.stream(token.getAllowedModels().split(","))
                        .map(String::trim).collect(Collectors.toSet());
                allowedModelsCache.put(token.getAllowedModels(),
                        new CachedAllowedModels(allowed, System.currentTimeMillis()));
            }
            if (!allowed.contains(model)) {
                throw new BusinessException(403, "该 Token 无权使用模型: " + model);
            }
        }
    }

    /**
     * 带容错的请求转发 - 失败自动切换渠道，同时检查渠道限流
     */
    private ForwardResult forwardRequestWithFailover(List<Channel> channels, String path, String requestBody) {
        for (Channel channel : channels) {
            if (isChannelRateLimited(channel)) continue;
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
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(body);
        applyChannelAuth(requestBuilder, channel);
        Request request = requestBuilder.build();

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
        usageLogService.recordUsageAndQuotas(usageLog, token.getId(), channel.getId(), totalTokens, token.getUserId());
        tokenService.addUsedQuota(token.getId(), totalTokens);
    }

    private String getClientIp(HttpServletRequest request) {
        if (request == null) return "";
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
}
