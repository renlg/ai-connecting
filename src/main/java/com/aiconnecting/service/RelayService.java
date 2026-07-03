package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.Token;
import com.aiconnecting.entity.UsageLog;
import com.aiconnecting.repository.ChannelRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
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
    private final ChannelRepository channelRepository;

    @Autowired(required = false)
    private RateLimitService rateLimitService;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 中转请求 (非流式)
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

        // 4. 选择渠道
        Channel channel = selectChannel(model);

        // 5. 渠道限流检查
        if (rateLimitService != null) {
            rateLimitService.checkChannelRateLimit(channel.getId(), channel.getRateLimit());
        }

        // 6. Token 限流检查
        if (rateLimitService != null) {
            rateLimitService.checkTokenRateLimit(token.getId(), token.getRateLimit());
        }

        // 7. 转发请求
        long startTime = System.currentTimeMillis();
        String response = forwardRequest(channel, path, requestBody);
        long duration = System.currentTimeMillis() - startTime;

        // 7. 记录日志和更新额度
        recordUsage(token, channel, model, response, duration, httpRequest);

        return response;
    }

    /**
     * 中转流式请求 (SSE)
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

        // 3. 选择渠道
        Channel channel = selectChannel(model);

        // 4. 渠道限流检查
        if (rateLimitService != null) {
            rateLimitService.checkChannelRateLimit(channel.getId(), channel.getRateLimit());
        }

        // 5. Token 限流检查
        if (rateLimitService != null) {
            rateLimitService.checkTokenRateLimit(token.getId(), token.getRateLimit());
        }

        // 6. 构建转发请求
        String url = channel.getBaseUrl().replaceAll("/+$", "") + path;

        RequestBody body = RequestBody.create(requestBody, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + channel.getApiKey())
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(body)
                .build();

        // 5. 设置 SSE 响应头
        httpResponse.setContentType("text/event-stream");
        httpResponse.setCharacterEncoding("UTF-8");
        httpResponse.setHeader("Cache-Control", "no-cache");
        httpResponse.setHeader("Connection", "keep-alive");

        long startTime = System.currentTimeMillis();

        // 6. 执行流式请求
        try (okhttp3.Response upstreamResponse = httpClient.newCall(request).execute()) {
            if (!upstreamResponse.isSuccessful()) {
                String errorBody = upstreamResponse.body() != null ? upstreamResponse.body().string() : "Unknown error";
                httpResponse.setStatus(upstreamResponse.code());
                httpResponse.getWriter().write(errorBody);
                return;
            }

            ResponseBody responseBody = upstreamResponse.body();
            if (responseBody != null) {
                byte[] buffer = new byte[4096];
                var inputStream = responseBody.byteStream();
                var outputStream = httpResponse.getOutputStream();
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    outputStream.flush();
                }
            }
        }

        long duration = System.currentTimeMillis() - startTime;

        // 7. 异步记录日志 (流式请求的 token 统计简化处理)
        UsageLog usageLog = UsageLog.builder()
                .tokenId(token.getId())
                .channelId(channel.getId())
                .model(model)
                .promptTokens(0)
                .completionTokens(0)
                .totalTokens(0)
                .ip(getClientIp(httpRequest))
                .duration(duration)
                .requestPath(path)
                .build();
        usageLogService.save(usageLog);

        // 8. 更新渠道已用额度
        channel.setUsedQuota(channel.getUsedQuota() + 1);
        channelRepository.save(channel);
    }

    /**
     * 选择最优渠道
     */
    private Channel selectChannel(String model) {
        List<Channel> channels = channelService.getActiveChannelsByModel(model);
        if (channels.isEmpty()) {
            throw new BusinessException(503, "没有可用的渠道支持模型: " + model);
        }
        return channels.get(0); // 返回优先级最高的
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
     * 记录使用情况和更新额度
     */
    private void recordUsage(Token token, Channel channel, String model,
                             String response, long duration, HttpServletRequest httpRequest) {
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

        // 保存使用日志
        UsageLog usageLog = UsageLog.builder()
                .tokenId(token.getId())
                .channelId(channel.getId())
                .model(model)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .ip(getClientIp(httpRequest))
                .duration(duration)
                .requestPath("/v1/chat/completions")
                .build();
        usageLogService.save(usageLog);

        // 更新 token 额度
        tokenService.addUsedQuota(token.getId(), totalTokens);

        // 更新渠道额度
        channel.setUsedQuota(channel.getUsedQuota() + totalTokens);
        channelRepository.save(channel);
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
