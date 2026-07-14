package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.ProtocolConverter;
import com.aiconnecting.common.SseUtils;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.entity.Token;
import com.aiconnecting.entity.User;
import com.aiconnecting.entity.UsageLog;
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
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * API 中转核心服务 - 负责将请求转发到实际的 AI 提供商
 * 使用 ChannelRouter 加权轮询选择渠道，最多重试 3 次
 * 集成 ChannelHealthTracker 追踪渠道健康状态
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RelayService {

    private final ChannelService channelService;
    private final ChannelRouter channelRouter;
    private final ChannelHealthTracker channelHealthTracker;
    private final TokenService tokenService;
    private final UsageLogService usageLogService;
    private final ModelConfigService modelConfigService;
    private final UserService userService;

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

    /** 模型名称解析缓存，避免每次请求查库，缓存 2 分钟 */
    private final ConcurrentHashMap<String, CachedValue> modelNameCache = new ConcurrentHashMap<>();
    private static final long MODEL_CACHE_TTL_MS = 2 * 60 * 1000L;

    private record CachedValue(String value, long cachedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > MODEL_CACHE_TTL_MS;
        }
    }

    /** 最大重试次数 */
    private static final int MAX_RETRIES = 3;

    /**
     * 中转预检结果，仅包含验证后的 token
     */
    private record RelayContext(Token token, String channelModelId) {}

    /**
     * 通用中转预检：验证 token、检查额度、检查模型权限（不选渠道，渠道在重试循环中选）
     */
    private RelayContext validateAndPrepare(String tokenKey, String model) {
        Token token = tokenService.validateTokenKey(tokenKey);
        if (token.getQuota() != -1 && token.getUsedQuota() >= token.getQuota()) {
            throw new BusinessException(429, "Token 额度已用完");
        }
        // 校验用户积分（admin 用户不受限制，使用缓存避免每次查库）
        User tokenUser = userService.getByIdCached(token.getUserId());
        if (!"admin".equals(tokenUser.getRole()) && tokenUser.getCredits() != null && tokenUser.getCredits().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(402, "用户积分不足，请先充值");
        }
        checkModelPermission(token, model);
        String channelModelId = resolveToChannelModelId(model);
        if (rateLimitService != null) {
            rateLimitService.checkTokenRateLimit(token.getId(), token.getRateLimit());
        }
        return new RelayContext(token, channelModelId);
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
                                    int cachedTokens, int cacheCreationTokens, int cacheReadTokens,
                                    long duration, HttpServletRequest httpRequest, String path) {
        int totalTokens = promptTokens + completionTokens;
        BigDecimal creditCost = usageLogService.calculateCreditCost(model, promptTokens, completionTokens, cachedTokens);
        UsageLog usageLog = UsageLog.builder()
                .tokenId(token.getId()).channelId(channel.getId()).model(model)
                .promptTokens(promptTokens).completionTokens(completionTokens).totalTokens(totalTokens)
                .promptTokensCacheHit(cachedTokens)
                .cachedTokensCacheCreation(cacheCreationTokens)
                .cachedTokensCacheRead(cacheReadTokens)
                .creditCost(creditCost).ip(getClientIp(httpRequest)).duration(duration)
                .requestPath(path).build();
        usageLogService.recordUsageAndQuotas(usageLog, token.getId(), channel.getId(), totalTokens, token.getUserId());
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
     * 判断渠道是否为 Claude/Anthropic 类型
     */
    private boolean isClaudeTypeChannel(Channel channel) {
        return "claude".equalsIgnoreCase(channel.getType()) || "anthropic".equalsIgnoreCase(channel.getType());
    }

    /**
     * 判断渠道是否为 Gemini 类型
     */
    private boolean isGeminiTypeChannel(Channel channel) {
        return "gemini".equalsIgnoreCase(channel.getType());
    }

    // ==================== 公开中转方法 ====================

    /**
     * 中转请求 (非流式) - 最多重试 3 次，每次选择不同渠道
     */
    public String relayRequest(String tokenKey, String path, String requestBody,
                               String model, HttpServletRequest httpRequest) {
        RelayContext ctx = validateAndPrepare(tokenKey, model);
        Set<Long> triedChannels = new HashSet<>();
        long startTime = System.currentTimeMillis();
        String lastError = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            Channel channel;
            try {
                channel = channelRouter.selectChannel(ctx.channelModelId(), triedChannels);
            } catch (BusinessException e) {
                if (lastError != null) {
                    throw new BusinessException(502, "所有渠道均不可用，最后错误: " + lastError);
                }
                throw e;
            }
            triedChannels.add(channel.getId());

            if (isChannelRateLimited(channel)) {
                lastError = "渠道 " + channel.getId() + " 请求频率超限";
                log.warn("重试 {}/{}: {}", attempt, MAX_RETRIES, lastError);
                continue;
            }

            try {
                String response;
                if (isGeminiTypeChannel(channel)) {
                    String geminiBody = ProtocolConverter.convertOpenAiToGeminiRequest(requestBody);
                    response = forwardGeminiRequest(channel, geminiBody);
                    response = ProtocolConverter.convertGeminiToOpenAiResponse(response);
                } else {
                    response = forwardRequest(channel, path, requestBody);
                }
                long duration = System.currentTimeMillis() - startTime;
                recordUsage(ctx.token(), channel, model, response, duration, httpRequest, path);
                channelHealthTracker.recordSuccess(channel.getId());
                return response;
            } catch (BusinessException e) {
                lastError = e.getMessage();
                log.error("渠道 {} 请求失败 (尝试 {}/{}): {}", channel.getId(), attempt, MAX_RETRIES, e.getMessage());
                channelHealthTracker.recordFailure(channel.getId(), e.getMessage());
                if (attempt == MAX_RETRIES) {
                    throw new BusinessException(e.getCode(),
                            "所有渠道均不可用，最后错误: " + lastError);
                }
            }
        }
        throw new BusinessException(502, "所有渠道均不可用，最后错误: " + lastError);
    }

    /**
     * 中转流式请求 (SSE) - 最多重试 3 次，仅在响应未提交前可重试
     */
    public void relayStreamRequest(String tokenKey, String path, String requestBody,
                                    String model, HttpServletRequest httpRequest,
                                    jakarta.servlet.http.HttpServletResponse httpResponse) throws IOException {
        RelayContext ctx = validateAndPrepare(tokenKey, model);
        Set<Long> triedChannels = new HashSet<>();
        long startTime = System.currentTimeMillis();
        String lastError = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            Channel channel;
            try {
                channel = channelRouter.selectChannel(ctx.channelModelId(), triedChannels);
            } catch (BusinessException e) {
                if (lastError != null && !httpResponse.isCommitted()) {
                    httpResponse.setStatus(502);
                    httpResponse.getWriter().write("{\"error\":{\"message\":\"所有渠道均不可用: " + lastError + "\"}}");
                }
                return;
            }
            triedChannels.add(channel.getId());

            if (isChannelRateLimited(channel)) {
                lastError = "渠道 " + channel.getId() + " 请求频率超限";
                log.warn("重试 {}/{}: {}", attempt, MAX_RETRIES, lastError);
                continue;
            }

            // 为 chat completions 注入 stream_options 以获取 usage
            String modifiedBody = injectStreamOptions(requestBody, path);

            SseUtils.setSseHeaders(httpResponse);

            HttpURLConnection conn;
            try {
                if (isGeminiTypeChannel(channel)) {
                    String geminiBody = ProtocolConverter.convertOpenAiToGeminiRequest(modifiedBody);
                    conn = createSseConnection(channel, "/v1/models/" +
                            (model != null ? model : "default") + ":streamGenerateContent?alt=sse", geminiBody);
                } else {
                    conn = createSseConnection(channel, path, modifiedBody);
                }
            } catch (IOException e) {
                lastError = e.getMessage();
                log.error("渠道 {} 流式连接失败 (尝试 {}/{}): {}", channel.getId(), attempt, MAX_RETRIES, e.getMessage());
                channelHealthTracker.recordFailure(channel.getId(), e.getMessage());
                if (attempt < MAX_RETRIES && !httpResponse.isCommitted()) continue;
                if (!httpResponse.isCommitted()) {
                    httpResponse.setStatus(502);
                    httpResponse.getWriter().write("{\"error\":{\"message\":\"渠道请求失败: " + e.getMessage() + "\"}}");
                }
                return;
            }

            try {
                int code = conn.getResponseCode();
                log.info("HTTP请求返回, code: {}, channel: {}", code, channel.getId());
                if (code != 200) {
                    String errorBody = conn.getErrorStream() != null
                            ? new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8) : "";
                    lastError = "HTTP " + code + " - " + errorBody;
                    log.warn("渠道 {} 流式请求失败: {}", channel.getId(), lastError);
                    channelHealthTracker.recordFailure(channel.getId(), lastError);
                    conn.disconnect();
                    if (attempt < MAX_RETRIES && !httpResponse.isCommitted()) continue;
                    httpResponse.setStatus(code);
                    httpResponse.getWriter().write(errorBody.isEmpty()
                            ? "{\"error\":{\"message\":\"上游返回 HTTP " + code + "\"}}" : errorBody);
                    return;
                }

                String lastUsageData;
                if (isGeminiTypeChannel(channel)) {
                    lastUsageData = streamGeminiResponseAsOpenAi(conn, httpResponse);
                } else {
                    lastUsageData = streamSseResponse(conn, httpResponse, null);
                }
                conn.disconnect();

                long duration = System.currentTimeMillis() - startTime;
                int promptTokens = 0, completionTokens = 0, cachedTokens = 0;
                if (lastUsageData != null) {
                    try {
                        JsonNode usageNode = objectMapper.readTree(lastUsageData).get("usage");
                        if (usageNode != null) {
                            promptTokens = usageNode.has("prompt_tokens") ? usageNode.get("prompt_tokens").asInt() : 0;
                            completionTokens = usageNode.has("completion_tokens") ? usageNode.get("completion_tokens").asInt() : 0;
                            JsonNode promptDetails = usageNode.path("prompt_tokens_details");
                            if (!promptDetails.isMissingNode()) {
                                cachedTokens = promptDetails.has("cached_tokens") ? promptDetails.get("cached_tokens").asInt() : 0;
                            }
                        }
                    } catch (Exception e) {
                        log.warn("解析流式响应 usage 数据失败: {}", e.getMessage());
                    }
                }
                recordStreamUsage(ctx.token(), channel, model, promptTokens, completionTokens,
                        cachedTokens, 0, cachedTokens, duration, httpRequest, path);
                channelHealthTracker.recordSuccess(channel.getId());
                return; // 成功，退出
            } catch (Exception e) {
                conn.disconnect();
                lastError = e.getMessage();
                log.error("渠道 {} 流式请求异常 (尝试 {}/{}): {}", channel.getId(), attempt, MAX_RETRIES, e.getMessage());
                channelHealthTracker.recordFailure(channel.getId(), e.getMessage());
                if (attempt < MAX_RETRIES && !httpResponse.isCommitted()) continue;
                if (!httpResponse.isCommitted()) {
                    httpResponse.setStatus(502);
                    httpResponse.getWriter().write("{\"error\":{\"message\":\"渠道请求失败: " + e.getMessage() + "\"}}");
                }
                return;
            }
        }
    }

    /**
     * Claude Messages API 中转 (非流式) - 最多重试 3 次
     * 选中的渠道是 Claude 类型则直接发送，否则转换为 OpenAI 格式发送
     */
    public String claudeRelayRequest(String tokenKey, String requestBody,
                                     String model, HttpServletRequest httpRequest) {
        RelayContext ctx = validateAndPrepare(tokenKey, model);
        Set<Long> triedChannels = new HashSet<>();
        long startTime = System.currentTimeMillis();
        String lastError = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            Channel channel;
            try {
                channel = channelRouter.selectChannel(ctx.channelModelId(), triedChannels);
            } catch (BusinessException e) {
                if (lastError != null) {
                    throw new BusinessException(502, "所有渠道均不可用，最后错误: " + lastError);
                }
                throw e;
            }
            triedChannels.add(channel.getId());

            try {
                String response;
                if (isClaudeTypeChannel(channel)) {
                    response = forwardClaudeRequest(channel, requestBody);
                } else if (isGeminiTypeChannel(channel)) {
                    String geminiBody = ProtocolConverter.convertClaudeToGeminiRequest(requestBody);
                    response = forwardGeminiRequest(channel, geminiBody);
                    response = ProtocolConverter.convertGeminiToClaudeResponse(response);
                } else {
                    String openAiBody = ProtocolConverter.convertClaudeToOpenAiBody(requestBody);
                    String openAiResponse = forwardRequest(channel, "/v1/chat/completions", openAiBody);
                    response = ProtocolConverter.convertOpenAiToClaudeResponse(openAiResponse);
                }

                long duration = System.currentTimeMillis() - startTime;
                int promptTokens = 0, completionTokens = 0;
                int cacheCreationTokens = 0, cacheReadTokens = 0;
                try {
                    JsonNode jsonNode = objectMapper.readTree(response);
                    JsonNode usage = jsonNode.get("usage");
                    if (usage != null) {
                        int inputTokens = usage.has("input_tokens") ? usage.get("input_tokens").asInt() : 0;
                        completionTokens = usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0;
                        cacheCreationTokens = usage.has("cache_creation_input_tokens") ? usage.get("cache_creation_input_tokens").asInt() : 0;
                        cacheReadTokens = usage.has("cache_read_input_tokens") ? usage.get("cache_read_input_tokens").asInt() : 0;
                        promptTokens = inputTokens + cacheReadTokens;
                    }
                } catch (Exception e) {
                    log.warn("解析 Claude 响应 usage 失败: {}", e.getMessage());
                }
                recordStreamUsage(ctx.token(), channel, model, promptTokens, completionTokens,
                        cacheReadTokens, cacheCreationTokens, cacheReadTokens, duration, httpRequest, "/v1/messages");
                channelHealthTracker.recordSuccess(channel.getId());
                return response;
            } catch (BusinessException e) {
                lastError = e.getMessage();
                log.error("Claude 渠道 {} 请求失败 (尝试 {}/{}): {}", channel.getId(), attempt, MAX_RETRIES, e.getMessage());
                channelHealthTracker.recordFailure(channel.getId(), e.getMessage());
                if (attempt == MAX_RETRIES) {
                    throw new BusinessException(e.getCode(),
                            "所有渠道均不可用，最后错误: " + lastError);
                }
            }
        }
        throw new BusinessException(502, "所有渠道均不可用，最后错误: " + lastError);
    }

    /**
     * Claude Messages API 中转 (流式 SSE) - 最多重试 3 次，仅在响应未提交前可重试
     * 选中的渠道是 Claude 类型则直接发送，否则转换为 OpenAI 格式发送
     */
    public void claudeRelayStreamRequest(String tokenKey, String requestBody,
                                          String model, HttpServletRequest httpRequest,
                                          HttpServletResponse httpResponse) throws IOException {
        log.info("[Claude流式] 开始处理, model={}", model);
        RelayContext ctx = validateAndPrepare(tokenKey, model);
        Set<Long> triedChannels = new HashSet<>();
        String lastError = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            Channel channel;
            try {
                channel = channelRouter.selectChannel(ctx.channelModelId(), triedChannels);
            } catch (BusinessException e) {
                if (!httpResponse.isCommitted()) {
                    httpResponse.setStatus(502);
                    httpResponse.getWriter().write("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"所有渠道均不可用\"}}");
                }
                return;
            }
            triedChannels.add(channel.getId());
            log.info("[Claude流式] 尝试 {}/{}, channel={}", attempt, MAX_RETRIES, channel.getId());

            try {
                if (isClaudeTypeChannel(channel)) {
                    forwardClaudeStreamSingle(channel, requestBody, model, ctx.token(), httpRequest, httpResponse);
                } else if (isGeminiTypeChannel(channel)) {
                    forwardGeminiStreamAsClaudeSingle(channel, requestBody, model, ctx.token(), httpRequest, httpResponse);
                } else {
                    forwardOpenAiStreamAsClaudeSingle(channel, requestBody, model, ctx.token(), httpRequest, httpResponse);
                }
                channelHealthTracker.recordSuccess(channel.getId());
                log.info("[Claude流式] 处理完成, channel={}", channel.getId());
                return; // 成功
            } catch (Exception e) {
                lastError = e.getMessage();
                log.error("[Claude流式] 渠道 {} 失败 (尝试 {}/{}): {}", channel.getId(), attempt, MAX_RETRIES, e.getMessage());
                channelHealthTracker.recordFailure(channel.getId(), e.getMessage());
                if (attempt < MAX_RETRIES && !httpResponse.isCommitted()) {
                    log.info("[Claude流式] 响应未提交，尝试下一个渠道");
                    continue;
                }
                // 响应已提交或已达最大重试
                if (!httpResponse.isCommitted()) {
                    httpResponse.setStatus(502);
                    httpResponse.getWriter().write("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"渠道请求失败\"}}");
                }
                return;
            }
        }
    }

    // ==================== Gemini 协议中转方法 ====================

    /**
     * Gemini API 中转 (非流式) - 最多重试 3 次
     * 根据渠道类型自动转换协议：Gemini 渠道直接发送，Claude 渠道转为 Claude 格式，OpenAI 渠道转为 OpenAI 格式
     */
    public String geminiRelayRequest(String tokenKey, String requestBody,
                                     String model, HttpServletRequest httpRequest) {
        RelayContext ctx = validateAndPrepare(tokenKey, model);
        Set<Long> triedChannels = new HashSet<>();
        long startTime = System.currentTimeMillis();
        String lastError = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            Channel channel;
            try {
                channel = channelRouter.selectChannel(ctx.channelModelId(), triedChannels);
            } catch (BusinessException e) {
                if (lastError != null) {
                    throw new BusinessException(502, "所有渠道均不可用，最后错误: " + lastError);
                }
                throw e;
            }
            triedChannels.add(channel.getId());

            try {
                String response;
                if (isGeminiTypeChannel(channel)) {
                    response = forwardGeminiRequest(channel, requestBody);
                } else if (isClaudeTypeChannel(channel)) {
                    String claudeBody = ProtocolConverter.convertGeminiToClaudeBody(requestBody);
                    String claudeResponse = forwardClaudeRequest(channel, claudeBody);
                    response = ProtocolConverter.convertClaudeToGeminiResponse(claudeResponse);
                } else {
                    String openAiBody = ProtocolConverter.convertGeminiToOpenAiBody(requestBody);
                    String openAiResponse = forwardRequest(channel, "/v1/chat/completions", openAiBody);
                    response = ProtocolConverter.convertOpenAiToGeminiResponse(openAiResponse);
                }

                long duration = System.currentTimeMillis() - startTime;
                int promptTokens = 0, completionTokens = 0;
                try {
                    JsonNode jsonNode = objectMapper.readTree(response);
                    JsonNode usage = jsonNode.get("usageMetadata");
                    if (usage != null) {
                        promptTokens = usage.has("promptTokenCount") ? usage.get("promptTokenCount").asInt() : 0;
                        completionTokens = usage.has("candidatesTokenCount") ? usage.get("candidatesTokenCount").asInt() : 0;
                    }
                } catch (Exception e) {
                    log.warn("解析 Gemini 响应 usage 失败: {}", e.getMessage());
                }
                recordStreamUsage(ctx.token(), channel, model, promptTokens, completionTokens,
                        0, 0, 0, duration, httpRequest, "/v1/models/" + model + ":generateContent");
                channelHealthTracker.recordSuccess(channel.getId());
                return response;
            } catch (BusinessException e) {
                lastError = e.getMessage();
                log.error("Gemini 渠道 {} 请求失败 (尝试 {}/{}): {}", channel.getId(), attempt, MAX_RETRIES, e.getMessage());
                channelHealthTracker.recordFailure(channel.getId(), e.getMessage());
                if (attempt == MAX_RETRIES) {
                    throw new BusinessException(e.getCode(),
                            "所有渠道均不可用，最后错误: " + lastError);
                }
            }
        }
        throw new BusinessException(502, "所有渠道均不可用，最后错误: " + lastError);
    }

    /**
     * Gemini API 中转 (流式 SSE) - 最多重试 3 次
     * 根据渠道类型自动转换协议，以 Gemini SSE 格式返回
     */
    public void geminiRelayStreamRequest(String tokenKey, String requestBody,
                                          String model, HttpServletRequest httpRequest,
                                          HttpServletResponse httpResponse) throws IOException {
        log.info("[Gemini流式] 开始处理, model={}", model);
        RelayContext ctx = validateAndPrepare(tokenKey, model);
        Set<Long> triedChannels = new HashSet<>();
        String lastError = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            Channel channel;
            try {
                channel = channelRouter.selectChannel(ctx.channelModelId(), triedChannels);
            } catch (BusinessException e) {
                if (!httpResponse.isCommitted()) {
                    httpResponse.setStatus(502);
                    httpResponse.getWriter().write("{\"error\":{\"message\":\"所有渠道均不可用\"}}");
                }
                return;
            }
            triedChannels.add(channel.getId());
            log.info("[Gemini流式] 尝试 {}/{}, channel={}", attempt, MAX_RETRIES, channel.getId());

            try {
                if (isGeminiTypeChannel(channel)) {
                    forwardGeminiStreamSingle(channel, requestBody, model, ctx.token(), httpRequest, httpResponse);
                } else if (isClaudeTypeChannel(channel)) {
                    forwardClaudeStreamAsGeminiSingle(channel, requestBody, model, ctx.token(), httpRequest, httpResponse);
                } else {
                    forwardOpenAiStreamAsGeminiSingle(channel, requestBody, model, ctx.token(), httpRequest, httpResponse);
                }
                channelHealthTracker.recordSuccess(channel.getId());
                log.info("[Gemini流式] 处理完成, channel={}", channel.getId());
                return;
            } catch (Exception e) {
                lastError = e.getMessage();
                log.error("[Gemini流式] 渠道 {} 失败 (尝试 {}/{}): {}", channel.getId(), attempt, MAX_RETRIES, e.getMessage());
                channelHealthTracker.recordFailure(channel.getId(), e.getMessage());
                if (attempt < MAX_RETRIES && !httpResponse.isCommitted()) {
                    log.info("[Gemini流式] 响应未提交，尝试下一个渠道");
                    continue;
                }
                if (!httpResponse.isCommitted()) {
                    httpResponse.setStatus(502);
                    httpResponse.getWriter().write("{\"error\":{\"message\":\"渠道请求失败\"}}");
                }
                return;
            }
        }
    }

    // ==================== 请求体处理 ====================

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

    // ==================== Claude 流式转发（单渠道） ====================

    /**
     * 使用 Claude 渠道直接流式发送（原生 Claude 协议，单渠道不重试）
     */
    private void forwardClaudeStreamSingle(Channel channel, String requestBody,
                                            String model, Token token,
                                            HttpServletRequest httpRequest,
                                            HttpServletResponse httpResponse) throws IOException {
        if (isChannelRateLimited(channel)) {
            throw new BusinessException(429, "渠道请求频率超限，请稍后重试");
        }

        SseUtils.setSseHeaders(httpResponse);
        long startTime = System.currentTimeMillis();

        HttpURLConnection conn = createSseConnection(channel, "/v1/messages", requestBody);
        try {
            int code = conn.getResponseCode();
            log.info("HTTP请求返回, code: {}, channel: {}", code, channel.getId());
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
            String lastUsageData = streamSseResponse(conn, httpResponse,
                    data -> data.contains("\"input_tokens\"") || data.contains("\"output_tokens\""));
            // 发送 [DONE] 标记以兼容前端 SSE 解析
            var writer = httpResponse.getWriter();
            writer.write("data: [DONE]\n\n");
            writer.flush();
            conn.disconnect();

            long duration = System.currentTimeMillis() - startTime;
            int promptTokens = 0, completionTokens = 0;
            int cacheCreationTokens = 0, cacheReadTokens = 0;
            if (lastUsageData != null) {
                try {
                    JsonNode lastJson = objectMapper.readTree(lastUsageData);
                    JsonNode usageNode = lastJson.get("usage");
                    if (usageNode != null) {
                        completionTokens = usageNode.has("output_tokens") ? usageNode.get("output_tokens").asInt() : 0;
                        cacheCreationTokens = usageNode.has("cache_creation_input_tokens") ? usageNode.get("cache_creation_input_tokens").asInt() : 0;
                        cacheReadTokens = usageNode.has("cache_read_input_tokens") ? usageNode.get("cache_read_input_tokens").asInt() : 0;
                    }
                    if (lastJson.has("message") && lastJson.get("message").has("usage")) {
                        JsonNode msgUsage = lastJson.get("message").get("usage");
                        int msgInputTokens = msgUsage.has("input_tokens") ? msgUsage.get("input_tokens").asInt() : 0;
                        promptTokens = msgInputTokens;
                        if (cacheReadTokens == 0) {
                            cacheReadTokens = msgUsage.has("cache_read_input_tokens") ? msgUsage.get("cache_read_input_tokens").asInt() : 0;
                        }
                        if (cacheCreationTokens == 0) {
                            cacheCreationTokens = msgUsage.has("cache_creation_input_tokens") ? msgUsage.get("cache_creation_input_tokens").asInt() : 0;
                        }
                    }
                    promptTokens += cacheReadTokens;
                } catch (Exception e) {
                    log.warn("解析 Claude 流式 usage 失败: {}", e.getMessage());
                }
            }
            recordStreamUsage(token, channel, model, promptTokens, completionTokens,
                    cacheReadTokens, cacheCreationTokens, cacheReadTokens, duration, httpRequest, "/v1/messages");
        } catch (Exception e) {
            conn.disconnect();
            log.error("渠道 {} Claude 流式请求异常: {}", channel.getId(), e.getMessage());
            if (!httpResponse.isCommitted()) {
                httpResponse.setStatus(502);
                httpResponse.getWriter().write("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"渠道请求失败\"}}");
            }
        }
    }

    /**
     * 非 Claude 渠道流式发送，将 OpenAI SSE 转为 Claude SSE 格式（单渠道不重试）
     */
    private void forwardOpenAiStreamAsClaudeSingle(Channel channel, String requestBody,
                                                    String model, Token token,
                                                    HttpServletRequest httpRequest,
                                                    HttpServletResponse httpResponse) throws IOException {
        if (isChannelRateLimited(channel)) {
            throw new BusinessException(429, "渠道请求频率超限，请稍后重试");
        }

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
        int promptTokens = 0, completionTokens = 0, cachedTokens = 0;
        List<Map<String, Object>> toolCalls = new ArrayList<>();

        HttpURLConnection conn = createSseConnection(channel, "/v1/chat/completions", openAiBody);
        try {
            int code = conn.getResponseCode();
            log.info("HTTP请求返回, code: {}, channel: {}", code, channel.getId());
            if (code != 200) {
                log.warn("渠道 {} OpenAI-as-Claude 流式请求失败: {}", channel.getId(), code);
                writer.write("data: {\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"上游错误\"}}\n\n");
                writer.flush();
                conn.disconnect();
                return;
            }
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
                                JsonNode promptDetails = usage.path("prompt_tokens_details");
                                if (!promptDetails.isMissingNode()) {
                                    cachedTokens = promptDetails.path("cached_tokens").asInt(0);
                                }
                            }
                            JsonNode delta = json.path("choices").path(0).path("delta");
                            String content = delta.path("content").asText("");
                            String reasoningContent = delta.path("reasoning_content").asText("");
                            String text = content.isEmpty() ? reasoningContent : content;
                            if (!text.isEmpty()) {
                                String claudeEvt = "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":" + objectMapper.writeValueAsString(text) + "}}";
                                writer.write("data: " + claudeEvt + "\n\n");
                                writer.flush();
                            }
                            // 处理 tool_calls 增量
                            JsonNode tcArray = delta.get("tool_calls");
                            if (tcArray != null && tcArray.isArray()) {
                                for (JsonNode tc : tcArray) {
                                    int idx = tc.path("index").asInt(0);
                                    while (toolCalls.size() <= idx) {
                                        toolCalls.add(new LinkedHashMap<>());
                                    }
                                    Map<String, Object> toolCall = toolCalls.get(idx);
                                    if (tc.has("id")) toolCall.put("id", tc.get("id").asText(""));
                                    if (tc.has("function")) {
                                        JsonNode fn = tc.get("function");
                                        if (fn.has("name")) toolCall.put("name", fn.get("name").asText(""));
                                        if (fn.has("arguments")) {
                                            String existing = (String) toolCall.getOrDefault("arguments", "");
                                            toolCall.put("arguments", existing + fn.get("arguments").asText(""));
                                        }
                                    }
                                }
                            }
                        } catch (Exception parseEx) {
                            log.warn("转换 OpenAI SSE 为 Claude 格式失败: {}", data);
                        }
                    }
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            conn.disconnect();
            log.error("渠道 {} OpenAI-as-Claude 流式请求异常: {}", channel.getId(), e.getMessage());
            writer.write("data: {\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"渠道请求失败\"}}\n\n");
            writer.flush();
            return;
        }

        writer.write("data: {\"type\":\"content_block_stop\",\"index\":0}\n\n");
        // 发送未完成的 tool_calls
        if (!toolCalls.isEmpty()) {
            int tcIndex = 1;
            for (Map<String, Object> tc : toolCalls) {
                String tcId = (String) tc.getOrDefault("id", "call_" + tcIndex);
                String tcName = (String) tc.getOrDefault("name", "unknown");
                String tcArgs = (String) tc.getOrDefault("arguments", "{}");
                writer.write("data: {\"type\":\"content_block_start\",\"index\":" + tcIndex + ",\"content_block\":{\"type\":\"tool_use\",\"id\":\"" + tcId + "\",\"name\":\"" + tcName + "\",\"input\":{}}}");
                writer.write("\n\n");
                writer.write("data: {\"type\":\"content_block_delta\",\"index\":" + tcIndex + ",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":" + objectMapper.writeValueAsString(tcArgs) + "}}");
                writer.write("\n\n");
                writer.write("data: {\"type\":\"content_block_stop\",\"index\":" + tcIndex + "}\n\n");
                tcIndex++;
            }
        }
        String stopReason = toolCalls.isEmpty() ? "end_turn" : "tool_use";
        writer.write("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":" + completionTokens + "}}\n\n");
        writer.write("data: {\"type\":\"message_stop\"}\n\n");
        writer.write("data: [DONE]\n\n");
        writer.flush();

        long duration = System.currentTimeMillis() - startTime;
        recordStreamUsage(token, channel, model, promptTokens, completionTokens,
                cachedTokens, 0, cachedTokens, duration, httpRequest, "/v1/messages");
    }

    // ==================== Gemini 流式转发 ====================

    /**
     * 使用 Gemini 渠道直接流式发送（原生 Gemini 协议，单渠道不重试）
     */
    private void forwardGeminiStreamSingle(Channel channel, String requestBody,
                                            String model, Token token,
                                            HttpServletRequest httpRequest,
                                            HttpServletResponse httpResponse) throws IOException {
        if (isChannelRateLimited(channel)) {
            throw new BusinessException(429, "渠道请求频率超限，请稍后重试");
        }

        SseUtils.setSseHeaders(httpResponse);
        long startTime = System.currentTimeMillis();

        String url = channel.getBaseUrl().replaceAll("/+$", "")
                + "/v1/models/" + model + ":streamGenerateContent?alt=sse&key=" + channel.getApiKey();
        java.net.URL urlObj = new java.net.URL(url);
        HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setRequestProperty("Connection", "close");
        conn.getOutputStream().write(requestBody.getBytes(StandardCharsets.UTF_8));
        conn.getOutputStream().flush();

        try {
            int code = conn.getResponseCode();
            log.info("HTTP请求返回, code: {}, channel: {}", code, channel.getId());
            if (code != 200) {
                String errorBody = conn.getErrorStream() != null
                        ? new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8) : "";
                httpResponse.setStatus(code);
                httpResponse.getWriter().write(errorBody.isEmpty()
                        ? "{\"error\":{\"message\":\"上游返回 HTTP " + code + "\"}}" : errorBody);
                conn.disconnect();
                return;
            }

            String lastUsageData = streamSseResponse(conn, httpResponse, null);
            conn.disconnect();

            long duration = System.currentTimeMillis() - startTime;
            int promptTokens = 0, completionTokens = 0;
            if (lastUsageData != null) {
                try {
                    JsonNode lastJson = objectMapper.readTree(lastUsageData);
                    JsonNode usageNode = lastJson.has("usageMetadata") ? lastJson.get("usageMetadata") : null;
                    if (usageNode != null) {
                        promptTokens = usageNode.has("promptTokenCount") ? usageNode.get("promptTokenCount").asInt() : 0;
                        completionTokens = usageNode.has("candidatesTokenCount") ? usageNode.get("candidatesTokenCount").asInt() : 0;
                    }
                } catch (Exception e) {
                    log.warn("解析 Gemini 流式 usage 失败: {}", e.getMessage());
                }
            }
            recordStreamUsage(token, channel, model, promptTokens, completionTokens,
                    0, 0, 0, duration, httpRequest, "/v1/models/" + model + ":streamGenerateContent");
        } catch (Exception e) {
            conn.disconnect();
            log.error("渠道 {} Gemini 流式请求异常: {}", channel.getId(), e.getMessage());
            if (!httpResponse.isCommitted()) {
                httpResponse.setStatus(502);
                httpResponse.getWriter().write("{\"error\":{\"message\":\"渠道请求失败\"}}");
            }
        }
    }

    /**
     * Gemini 渠道流式发送，Claude 请求 → Gemini SSE → Claude SSE（单渠道不重试）
     */
    private void forwardGeminiStreamAsClaudeSingle(Channel channel, String requestBody,
                                                    String model, Token token,
                                                    HttpServletRequest httpRequest,
                                                    HttpServletResponse httpResponse) throws IOException {
        if (isChannelRateLimited(channel)) {
            throw new BusinessException(429, "渠道请求频率超限，请稍后重试");
        }

        String geminiBody = ProtocolConverter.convertClaudeToGeminiRequest(requestBody);
        SseUtils.setSseHeaders(httpResponse);
        var writer = httpResponse.getWriter();
        String msgId = "msg_" + System.currentTimeMillis();
        writer.write("data: {\"type\":\"message_start\",\"message\":{\"id\":\"" + msgId + "\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"" + model + "\",\"stop_reason\":null,\"stop_sequence\":null}}");
        writer.write("\n\n");
        writer.write("data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
        writer.write("\n\n");
        writer.flush();

        long startTime = System.currentTimeMillis();
        int promptTokens = 0, completionTokens = 0;

        String url = channel.getBaseUrl().replaceAll("/+$", "")
                + "/v1/models/" + model + ":streamGenerateContent?alt=sse&key=" + channel.getApiKey();
        java.net.URL urlObj = new java.net.URL(url);
        HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setRequestProperty("Connection", "close");
        conn.getOutputStream().write(geminiBody.getBytes(StandardCharsets.UTF_8));
        conn.getOutputStream().flush();

        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                writer.write("data: {\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"上游错误\"}}\n\n");
                writer.flush();
                conn.disconnect();
                return;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6).trim();
                        try {
                            JsonNode json = objectMapper.readTree(data);
                            JsonNode candidates = json.get("candidates");
                            if (candidates != null && candidates.isArray() && candidates.size() > 0) {
                                JsonNode candidate = candidates.get(0);
                                JsonNode content = candidate.get("content");
                                if (content != null && content.has("parts")) {
                                    for (JsonNode part : content.get("parts")) {
                                        if (part.has("text")) {
                                            String text = part.get("text").asText("");
                                            if (!text.isEmpty()) {
                                                String claudeEvt = "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":" + objectMapper.writeValueAsString(text) + "}}";
                                                writer.write("data: " + claudeEvt + "\n\n");
                                                writer.flush();
                                            }
                                        }
                                    }
                                }
                                if (candidate.has("finishReason") && !candidate.get("finishReason").isNull()) {
                                    String finishReason = candidate.get("finishReason").asText("STOP");
                                    String stopReason = "STOP".equals(finishReason) ? "end_turn" : "max_tokens";
                                    writer.write("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":0}}\n\n");
                                    writer.flush();
                                }
                            }
                            JsonNode usageMeta = json.get("usageMetadata");
                            if (usageMeta != null) {
                                promptTokens = usageMeta.has("promptTokenCount") ? usageMeta.get("promptTokenCount").asInt() : 0;
                                completionTokens = usageMeta.has("candidatesTokenCount") ? usageMeta.get("candidatesTokenCount").asInt() : 0;
                            }
                        } catch (Exception parseEx) {
                            log.warn("转换 Gemini SSE 为 Claude 格式失败: {}", data);
                        }
                    }
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            conn.disconnect();
            log.error("渠道 {} Gemini-as-Claude 流式请求异常: {}", channel.getId(), e.getMessage());
        }

        writer.write("data: {\"type\":\"content_block_stop\",\"index\":0}\n\n");
        writer.write("data: {\"type\":\"message_stop\"}\n\n");
        writer.write("data: [DONE]\n\n");
        writer.flush();

        long duration = System.currentTimeMillis() - startTime;
        recordStreamUsage(token, channel, model, promptTokens, completionTokens,
                0, 0, 0, duration, httpRequest, "/v1/messages");
    }

    /**
     * OpenAI 渠道流式发送，Gemini 请求 → OpenAI SSE → Gemini SSE（单渠道不重试）
     */
    private void forwardOpenAiStreamAsGeminiSingle(Channel channel, String requestBody,
                                                    String model, Token token,
                                                    HttpServletRequest httpRequest,
                                                    HttpServletResponse httpResponse) throws IOException {
        if (isChannelRateLimited(channel)) {
            throw new BusinessException(429, "渠道请求频率超限，请稍后重试");
        }

        String openAiBody = ProtocolConverter.convertGeminiToOpenAiBody(requestBody);
        openAiBody = injectStreamOptions(openAiBody, "/v1/chat/completions");

        SseUtils.setSseHeaders(httpResponse);
        var writer = httpResponse.getWriter();

        long startTime = System.currentTimeMillis();
        int promptTokens = 0, completionTokens = 0, cachedTokens = 0;

        HttpURLConnection conn = createSseConnection(channel, "/v1/chat/completions", openAiBody);
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                writer.write("data: {\"error\":{\"message\":\"上游错误\"}}\n\n");
                writer.flush();
                conn.disconnect();
                return;
            }
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
                                JsonNode promptDetails = usage.path("prompt_tokens_details");
                                if (!promptDetails.isMissingNode()) {
                                    cachedTokens = promptDetails.path("cached_tokens").asInt(0);
                                }
                            }
                            String geminiChunk = ProtocolConverter.convertOpenAiStreamChunkToGemini(data);
                            if (geminiChunk != null) {
                                writer.write("data: " + geminiChunk + "\n\n");
                                writer.flush();
                            }
                        } catch (Exception parseEx) {
                            log.warn("转换 OpenAI SSE 为 Gemini 格式失败: {}", data);
                        }
                    }
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            conn.disconnect();
            log.error("渠道 {} OpenAI-as-Gemini 流式请求异常: {}", channel.getId(), e.getMessage());
        }

        long duration = System.currentTimeMillis() - startTime;
        recordStreamUsage(token, channel, model, promptTokens, completionTokens,
                cachedTokens, 0, cachedTokens, duration, httpRequest, "/v1/models/" + model + ":streamGenerateContent");
    }

    /**
     * Claude 渠道流式发送，Gemini 请求 → Claude SSE → Gemini SSE（单渠道不重试）
     */
    private void forwardClaudeStreamAsGeminiSingle(Channel channel, String requestBody,
                                                    String model, Token token,
                                                    HttpServletRequest httpRequest,
                                                    HttpServletResponse httpResponse) throws IOException {
        if (isChannelRateLimited(channel)) {
            throw new BusinessException(429, "渠道请求频率超限，请稍后重试");
        }

        String claudeBody = ProtocolConverter.convertGeminiToClaudeBody(requestBody);
        SseUtils.setSseHeaders(httpResponse);
        var writer = httpResponse.getWriter();

        long startTime = System.currentTimeMillis();
        int promptTokens = 0, completionTokens = 0;

        HttpURLConnection conn = createSseConnection(channel, "/v1/messages", claudeBody);
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                writer.write("data: {\"error\":{\"message\":\"上游错误\"}}\n\n");
                writer.flush();
                conn.disconnect();
                return;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) continue;
                        try {
                            JsonNode json = objectMapper.readTree(data);
                            String type = json.path("type").asText("");
                            if ("message_start".equals(type)) {
                                JsonNode msgUsage = json.path("message").path("usage");
                                if (!msgUsage.isMissingNode()) {
                                    promptTokens = msgUsage.path("input_tokens").asInt(0);
                                }
                            } else if ("content_block_delta".equals(type)) {
                                String geminiChunk = ProtocolConverter.convertClaudeStreamEventToGemini(data);
                                if (geminiChunk != null) {
                                    writer.write("data: " + geminiChunk + "\n\n");
                                    writer.flush();
                                }
                            } else if ("message_delta".equals(type)) {
                                JsonNode usage = json.get("usage");
                                if (usage != null) {
                                    completionTokens = usage.path("output_tokens").asInt(0);
                                }
                                String geminiChunk = ProtocolConverter.convertClaudeStreamEventToGemini(data);
                                if (geminiChunk != null) {
                                    writer.write("data: " + geminiChunk + "\n\n");
                                    writer.flush();
                                }
                            }
                        } catch (Exception parseEx) {
                            log.warn("转换 Claude SSE 为 Gemini 格式失败: {}", data);
                        }
                    }
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            conn.disconnect();
            log.error("渠道 {} Claude-as-Gemini 流式请求异常: {}", channel.getId(), e.getMessage());
        }

        long duration = System.currentTimeMillis() - startTime;
        recordStreamUsage(token, channel, model, promptTokens, completionTokens,
                0, 0, 0, duration, httpRequest, "/v1/models/" + model + ":streamGenerateContent");
    }

    /**
     * 读取 Gemini SSE 响应并转换为 OpenAI SSE 格式输出
     * 返回最后包含 usage 的数据行
     */
    private String streamGeminiResponseAsOpenAi(HttpURLConnection conn, HttpServletResponse httpResponse) throws IOException {
        String lastUsageData = null;
        var writer = httpResponse.getWriter();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    try {
                        JsonNode json = objectMapper.readTree(data);
                        JsonNode usageMeta = json.get("usageMetadata");
                        if (usageMeta != null) {
                            lastUsageData = data;
                        }

                        String openAiChunk = convertGeminiStreamChunkToOpenAiSse(json);
                        if (openAiChunk != null) {
                            writer.write("data: " + openAiChunk + "\n\n");
                            writer.flush();
                        }
                    } catch (Exception parseEx) {
                        log.warn("转换 Gemini SSE 为 OpenAI 格式失败: {}", data);
                    }
                }
            }
        }
        writer.write("data: [DONE]\n\n");
        writer.flush();
        return lastUsageData;
    }

    private String convertGeminiStreamChunkToOpenAiSse(JsonNode json) {
        try {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("id", "chatcmpl-" + System.currentTimeMillis());
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", json.path("modelVersion").asText(""));

            JsonNode candidates = json.get("candidates");
            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("role", "assistant");
            String finishReason = null;

            if (candidates != null && candidates.isArray() && candidates.size() > 0) {
                JsonNode candidate = candidates.get(0);
                JsonNode content = candidate.get("content");
                if (content != null && content.has("parts")) {
                    StringBuilder textBuf = new StringBuilder();
                    for (JsonNode part : content.get("parts")) {
                        if (part.has("text")) {
                            textBuf.append(part.get("text").asText());
                        }
                    }
                    if (textBuf.length() > 0) {
                        delta.put("content", textBuf.toString());
                    }
                }
                if (candidate.has("finishReason") && !candidate.get("finishReason").isNull()) {
                    String gr = candidate.get("finishReason").asText("STOP");
                    finishReason = switch (gr) {
                        case "STOP" -> "stop";
                        case "MAX_TOKENS" -> "length";
                        default -> "stop";
                    };
                }
            }

            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("delta", delta);
            choice.put("finish_reason", finishReason);
            chunk.put("choices", List.of(choice));

            JsonNode usageMeta = json.get("usageMetadata");
            if (usageMeta != null) {
                Map<String, Object> usage = new LinkedHashMap<>();
                usage.put("prompt_tokens", usageMeta.path("promptTokenCount").asInt(0));
                usage.put("completion_tokens", usageMeta.path("candidatesTokenCount").asInt(0));
                usage.put("total_tokens", usageMeta.path("totalTokenCount").asInt(0));
                chunk.put("usage", usage);
            }

            return objectMapper.writeValueAsString(chunk);
        } catch (Exception e) {
            log.warn("转换 Gemini stream chunk 为 OpenAI SSE 失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 渠道认证 ====================

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

    // ==================== 上游请求转发 ====================

    /**
     * Claude 协议请求转发
     */
    private String forwardClaudeRequest(Channel channel, String requestBody) {
        if (isChannelRateLimited(channel)) {
            throw new BusinessException(429, "渠道请求频率超限，请稍后重试");
        }

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
                throw new BusinessException(response.code(), "上游 API 错误: " + responseBody);
            }
            return responseBody;
        } catch (IOException e) {
            throw new BusinessException(502, "渠道请求失败: " + e.getMessage());
        }
    }

    /**
     * Gemini 协议请求转发
     */
    private String forwardGeminiRequest(Channel channel, String requestBody) {
        if (isChannelRateLimited(channel)) {
            throw new BusinessException(429, "渠道请求频率超限，请稍后重试");
        }

        String model = "default";
        try {
            JsonNode node = objectMapper.readTree(requestBody);
            if (node.has("model")) model = node.get("model").asText();
        } catch (Exception e) {
            log.warn("解析 Gemini 请求 model 失败，使用默认值");
        }

        String url = channel.getBaseUrl().replaceAll("/+$", "")
                + "/v1/models/" + model + ":generateContent?key=" + channel.getApiKey();
        RequestBody body = RequestBody.create(requestBody, MediaType.parse("application/json"));
        Request.Builder reqBuilder = new Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(body);

        try (okhttp3.Response response = httpClient.newCall(reqBuilder.build()).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("Gemini upstream API error: {} - {}", response.code(), responseBody);
                throw new BusinessException(response.code(), "上游 API 错误: " + responseBody);
            }
            return responseBody;
        } catch (IOException e) {
            throw new BusinessException(502, "渠道请求失败: " + e.getMessage());
        }
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

    // ==================== 模型名称解析 ====================

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

    // ==================== 模型权限检查 ====================

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

    // ==================== 使用记录 ====================

    /**
     * 记录使用情况和更新额度（非流式）
     */
    private void recordUsage(Token token, Channel channel, String model,
                             String response, long duration, HttpServletRequest httpRequest, String path) {
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;
        int cachedTokens = 0;
        int cacheCreationTokens = 0;
        int cacheReadTokens = 0;

        try {
            JsonNode jsonNode = objectMapper.readTree(response);
            JsonNode usage = jsonNode.get("usage");
            if (usage != null) {
                promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : 0;
                completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : 0;
                totalTokens = usage.has("total_tokens") ? usage.get("total_tokens").asInt() : 0;
                // OpenAI format: prompt_tokens_details.cached_tokens
                JsonNode promptDetails = usage.path("prompt_tokens_details");
                if (!promptDetails.isMissingNode()) {
                    cachedTokens = promptDetails.has("cached_tokens") ? promptDetails.get("cached_tokens").asInt() : 0;
                }
                // Claude format: cache_read_input_tokens, cache_creation_input_tokens
                cacheCreationTokens = usage.has("cache_creation_input_tokens") ? usage.get("cache_creation_input_tokens").asInt() : 0;
                cacheReadTokens = usage.has("cache_read_input_tokens") ? usage.get("cache_read_input_tokens").asInt() : 0;
                if (cachedTokens == 0 && cacheReadTokens > 0) {
                    cachedTokens = cacheReadTokens;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse usage from response");
        }

        BigDecimal creditCost = usageLogService.calculateCreditCost(model, promptTokens, completionTokens, cachedTokens);

        UsageLog usageLog = UsageLog.builder()
                .tokenId(token.getId())
                .channelId(channel.getId())
                .model(model)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .promptTokensCacheHit(cachedTokens)
                .cachedTokensCacheCreation(cacheCreationTokens)
                .cachedTokensCacheRead(cacheReadTokens)
                .creditCost(creditCost)
                .ip(getClientIp(httpRequest))
                .duration(duration)
                .requestPath(path)
                .build();

        // 事务性记录日志并更新额度
        usageLogService.recordUsageAndQuotas(usageLog, token.getId(), channel.getId(), totalTokens, token.getUserId());
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
