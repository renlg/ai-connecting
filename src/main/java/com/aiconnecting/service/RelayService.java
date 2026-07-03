package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.Token;
import com.aiconnecting.entity.UsageLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

    @Autowired(required = false)
    private RateLimitService rateLimitService;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

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

        // 4. 获取可用渠道列表
        List<Channel> channels = channelService.getActiveChannelsByModel(model);
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

        // 4. 获取可用渠道列表
        List<Channel> channels = channelService.getActiveChannelsByModel(model);
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
