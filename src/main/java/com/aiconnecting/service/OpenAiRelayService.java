package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.ProtocolConverter;
import com.aiconnecting.common.SseUtils;
import com.aiconnecting.entity.Channel;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * OpenAI 兼容协议的中转服务
 * 处理 OpenAI / DeepSeek / Qwen 等 OpenAI 兼容协议的请求转发和 SSE 流式处理
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OpenAiRelayService {

    private final RelaySupport support;

    /**
     * 中转请求 (非流式) - 最多重试 3 次，每次选择不同渠道
     */
    public String relayRequest(String tokenKey, String path, String requestBody,
                               String model, HttpServletRequest httpRequest) {
        RelaySupport.RelayContext ctx = support.validateAndPrepare(tokenKey, model);
        Set<Long> triedChannels = new HashSet<>();
        long startTime = System.currentTimeMillis();
        String lastError = null;

        for (int attempt = 1; attempt <= RelaySupport.MAX_RETRIES; attempt++) {
            Channel channel;
            try {
                channel = support.channelRouter.selectChannel(ctx.channelModelId(), triedChannels);
            } catch (BusinessException e) {
                if (lastError != null) {
                    throw new BusinessException(502, "所有渠道均不可用，最后错误: " + lastError);
                }
                throw e;
            }
            triedChannels.add(channel.getId());

            if (support.isChannelRateLimited(channel)) {
                lastError = "渠道 " + channel.getId() + " 请求频率超限";
                log.warn("重试 {}/{}: {}", attempt, RelaySupport.MAX_RETRIES, lastError);
                continue;
            }

            try {
                String response;
                if (support.isGeminiTypeChannel(channel)) {
                    String geminiBody = ProtocolConverter.convertOpenAiToGeminiRequest(requestBody);
                    response = support.forwardGeminiRequest(channel, geminiBody);
                    response = ProtocolConverter.convertGeminiToOpenAiResponse(response);
                } else {
                    response = support.forwardRequest(channel, path, requestBody);
                }
                long duration = System.currentTimeMillis() - startTime;
                support.recordUsage(ctx.token(), channel, model, response, duration, httpRequest, path);
                support.channelHealthTracker.recordSuccess(channel.getId());
                return response;
            } catch (BusinessException e) {
                lastError = e.getMessage();
                log.error("渠道 {} 请求失败 (尝试 {}/{}): {}", channel.getId(), attempt, RelaySupport.MAX_RETRIES, e.getMessage());
                support.channelHealthTracker.recordFailure(channel.getId(), e.getMessage());
                if (attempt == RelaySupport.MAX_RETRIES) {
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
                                    HttpServletResponse httpResponse) throws IOException {
        RelaySupport.RelayContext ctx = support.validateAndPrepare(tokenKey, model);
        Set<Long> triedChannels = new HashSet<>();
        long startTime = System.currentTimeMillis();
        String lastError = null;

        for (int attempt = 1; attempt <= RelaySupport.MAX_RETRIES; attempt++) {
            Channel channel;
            try {
                channel = support.channelRouter.selectChannel(ctx.channelModelId(), triedChannels);
            } catch (BusinessException e) {
                if (lastError != null && !httpResponse.isCommitted()) {
                    RelayServiceUtils.writeOpenAiError(httpResponse, 502, "所有渠道均不可用: " + lastError);
                }
                return;
            }
            triedChannels.add(channel.getId());

            if (support.isChannelRateLimited(channel)) {
                lastError = "渠道 " + channel.getId() + " 请求频率超限";
                log.warn("重试 {}/{}: {}", attempt, RelaySupport.MAX_RETRIES, lastError);
                continue;
            }

            String modifiedBody = support.injectStreamOptions(requestBody, path);

            SseUtils.setSseHeaders(httpResponse);

            HttpURLConnection conn;
            try {
                if (support.isGeminiTypeChannel(channel)) {
                    String geminiBody = ProtocolConverter.convertOpenAiToGeminiRequest(modifiedBody);
                    conn = support.createSseConnection(channel, "/v1/models/" +
                            (model != null ? model : "default") + ":streamGenerateContent?alt=sse", geminiBody);
                } else {
                    conn = support.createSseConnection(channel, path, modifiedBody);
                }
            } catch (IOException e) {
                lastError = e.getMessage();
                log.error("渠道 {} 流式连接失败 (尝试 {}/{}): {}", channel.getId(), attempt, RelaySupport.MAX_RETRIES, e.getMessage());
                support.channelHealthTracker.recordFailure(channel.getId(), e.getMessage());
                if (attempt < RelaySupport.MAX_RETRIES && !httpResponse.isCommitted()) continue;
                if (!httpResponse.isCommitted()) {
                    RelayServiceUtils.writeOpenAiError(httpResponse, 502, "渠道请求失败: " + e.getMessage());
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
                    support.channelHealthTracker.recordFailure(channel.getId(), lastError);
                    conn.disconnect();
                    if (attempt < RelaySupport.MAX_RETRIES && !httpResponse.isCommitted()) continue;
                    httpResponse.setStatus(code);
                    httpResponse.getWriter().write(errorBody.isEmpty()
                            ? "{\"error\":{\"message\":\"上游返回 HTTP " + code + "\"}}" : errorBody);
                    return;
                }

                String lastUsageData;
                if (support.isGeminiTypeChannel(channel)) {
                    lastUsageData = streamGeminiResponseAsOpenAi(conn, httpResponse);
                } else {
                    lastUsageData = support.streamSseResponse(conn, httpResponse, null);
                }
                conn.disconnect();

                long duration = System.currentTimeMillis() - startTime;
                RelayServiceUtils.UsageInfo usage = RelayServiceUtils.parseOpenAiStreamUsage(support.objectMapper, lastUsageData);
                support.recordStreamUsage(ctx.token(), channel, model,
                        usage.promptTokens(), usage.completionTokens(),
                        usage.cachedTokens(), 0, usage.cachedTokens(),
                        duration, httpRequest, path);
                support.channelHealthTracker.recordSuccess(channel.getId());
                return;
            } catch (Exception e) {
                conn.disconnect();
                lastError = e.getMessage();
                log.error("渠道 {} 流式请求异常 (尝试 {}/{}): {}", channel.getId(), attempt, RelaySupport.MAX_RETRIES, e.getMessage());
                support.channelHealthTracker.recordFailure(channel.getId(), e.getMessage());
                if (attempt < RelaySupport.MAX_RETRIES && !httpResponse.isCommitted()) continue;
                if (!httpResponse.isCommitted()) {
                    RelayServiceUtils.writeOpenAiError(httpResponse, 502, "渠道请求失败: " + e.getMessage());
                }
                return;
            }
        }
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
                        JsonNode json = support.objectMapper.readTree(data);
                        JsonNode usageMeta = json.get("usageMetadata");
                        if (usageMeta != null) {
                            lastUsageData = data;
                        }

                        String openAiChunk = RelayServiceUtils.convertGeminiStreamChunkToOpenAiSse(support.objectMapper, json);
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
}
