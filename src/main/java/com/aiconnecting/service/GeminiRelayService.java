package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.ProtocolConverter;
import com.aiconnecting.common.SseUtils;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.Token;
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
 * Google Gemini 协议的中转服务
 * 处理 Gemini 协议的请求构建、转发和 SSE 流式响应处理
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiRelayService {

    private final RelaySupport support;

    /**
     * Gemini API 中转 (非流式) - 最多重试 3 次
     * 根据渠道类型自动转换协议：Gemini 渠道直接发送，Claude 渠道转为 Claude 格式，OpenAI 渠道转为 OpenAI 格式
     */
    public String geminiRelayRequest(String tokenKey, String requestBody,
                                     String model, HttpServletRequest httpRequest) {
        RelaySupport.RelayContext ctx = support.validateAndPrepare(tokenKey, model);
        Set<Long> triedChannels = new HashSet<>();
        long startTime = System.currentTimeMillis();
        String lastError = null;
        int attempt = 0;

        while (attempt < RelaySupport.MAX_RETRIES) {
            Channel channel;
            try {
                channel = support.channelRouter.selectChannel(ctx.channelModelId(), triedChannels, ctx.userLevel());
            } catch (BusinessException e) {
                if (lastError != null) {
                    throw new BusinessException(502, "所有渠道均不可用，最后错误: " + lastError);
                }
                throw e;
            }
            triedChannels.add(channel.getId());

            if (support.isChannelRateLimited(channel)) {
                lastError = "渠道 " + channel.getId() + " 请求频率超限";
                log.warn("跳过限流渠道 {}: {}", channel.getId(), lastError);
                continue;
            }

            attempt++;
            try {
                String response;
                if (RelayServiceUtils.isGeminiTypeChannel(channel)) {
                    response = support.forwardGeminiRequest(channel, requestBody);
                } else if (RelayServiceUtils.isClaudeTypeChannel(channel)) {
                    String claudeBody = ProtocolConverter.convertGeminiToClaudeBody(requestBody);
                    String claudeResponse = support.forwardClaudeRequest(channel, claudeBody);
                    response = ProtocolConverter.convertClaudeToGeminiResponse(claudeResponse);
                } else {
                    String openAiBody = ProtocolConverter.convertGeminiToOpenAiBody(requestBody);
                    String openAiResponse = support.forwardRequest(channel, "/v1/chat/completions", openAiBody);
                    response = ProtocolConverter.convertOpenAiToGeminiResponse(openAiResponse);
                }

                long duration = System.currentTimeMillis() - startTime;
                RelayServiceUtils.UsageInfo usage = RelayServiceUtils.parseGeminiUsage(support.objectMapper, response);
                support.recordStreamUsage(ctx.token(), channel, model,
                        usage.promptTokens(), usage.completionTokens(),
                        0, 0, 0, duration, httpRequest,
                        "/v1/models/" + model + ":generateContent");
                support.channelHealthTracker.recordSuccess(channel.getId());
                return response;
            } catch (BusinessException e) {
                lastError = e.getMessage();
                log.error("Gemini 渠道 {} 请求失败 (尝试 {}/{}): {}", channel.getId(), attempt, RelaySupport.MAX_RETRIES, e.getMessage());
                support.channelHealthTracker.recordFailure(channel.getId(),
                        ChannelHealthTracker.ErrorCategory.fromStatusCode(e.getCode()), e.getMessage());
                if (attempt == RelaySupport.MAX_RETRIES) {
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
        RelaySupport.RelayContext ctx = support.validateAndPrepare(tokenKey, model);
        Set<Long> triedChannels = new HashSet<>();
        String lastError = null;
        int attempt = 0;

        while (attempt < RelaySupport.MAX_RETRIES) {
            Channel channel;
            try {
                channel = support.channelRouter.selectChannel(ctx.channelModelId(), triedChannels, ctx.userLevel());
            } catch (BusinessException e) {
                if (!httpResponse.isCommitted()) {
                    RelayServiceUtils.writeGeminiError(httpResponse, 502, "所有渠道均不可用");
                }
                return;
            }
            triedChannels.add(channel.getId());

            if (support.isChannelRateLimited(channel)) {
                lastError = "渠道 " + channel.getId() + " 请求频率超限";
                log.warn("[Gemini流式] 跳过限流渠道 {}: {}", channel.getId(), lastError);
                continue;
            }

            attempt++;
            log.info("[Gemini流式] 尝试 {}/{}, channel={}", attempt, RelaySupport.MAX_RETRIES, channel.getId());

            try {
                if (RelayServiceUtils.isGeminiTypeChannel(channel)) {
                    forwardGeminiStreamSingle(channel, requestBody, model, ctx.token(), httpRequest, httpResponse);
                } else if (RelayServiceUtils.isClaudeTypeChannel(channel)) {
                    forwardClaudeStreamAsGeminiSingle(channel, requestBody, model, ctx.token(), httpRequest, httpResponse);
                } else {
                    forwardOpenAiStreamAsGeminiSingle(channel, requestBody, model, ctx.token(), httpRequest, httpResponse);
                }
                support.channelHealthTracker.recordSuccess(channel.getId());
                log.info("[Gemini流式] 处理完成, channel={}", channel.getId());
                return;
            } catch (Exception e) {
                lastError = e.getMessage();
                log.error("[Gemini流式] 渠道 {} 失败 (尝试 {}/{}): {}", channel.getId(), attempt, RelaySupport.MAX_RETRIES, e.getMessage());
                ChannelHealthTracker.ErrorCategory category = (e instanceof BusinessException be)
                        ? ChannelHealthTracker.ErrorCategory.fromStatusCode(be.getCode())
                        : ChannelHealthTracker.ErrorCategory.fromException(e);
                support.channelHealthTracker.recordFailure(channel.getId(), category, e.getMessage());
                if (attempt < RelaySupport.MAX_RETRIES && !httpResponse.isCommitted()) {
                    log.info("[Gemini流式] 响应未提交，尝试下一个渠道");
                    continue;
                }
                if (!httpResponse.isCommitted()) {
                    RelayServiceUtils.writeGeminiError(httpResponse, 502, "渠道请求失败");
                }
                return;
            }
        }
    }

    // ==================== Gemini 流式转发 ====================

    /**
     * 使用 Gemini 渠道直接流式发送（原生 Gemini 协议，单渠道不重试）
     */
    private void forwardGeminiStreamSingle(Channel channel, String requestBody,
                                            String model, Token token,
                                            HttpServletRequest httpRequest,
                                            HttpServletResponse httpResponse) throws IOException {
        if (support.isChannelRateLimited(channel)) {
            throw new BusinessException(429, "渠道请求频率超限，请稍后重试");
        }

        SseUtils.setSseHeaders(httpResponse);
        long startTime = System.currentTimeMillis();

        String url = channel.getBaseUrl().replaceAll("/+$", "")
                + "/v1/models/" + model + ":streamGenerateContent?alt=sse";
        java.net.URL urlObj = new java.net.URL(url);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) urlObj.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Connection", "close");
            conn.setRequestProperty("X-Goog-Api-Key", channel.getApiKey());
            conn.getOutputStream().write(requestBody.getBytes(StandardCharsets.UTF_8));
            conn.getOutputStream().flush();

            int code = conn.getResponseCode();
            log.info("HTTP请求返回, code: {}, channel: {}", code, channel.getId());
            if (code != 200) {
                String errorBody = conn.getErrorStream() != null
                        ? new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8) : "";
                httpResponse.setStatus(code);
                httpResponse.getWriter().write(errorBody.isEmpty()
                        ? "{\"error\":{\"message\":\"上游返回 HTTP " + code + "\"}}" : errorBody);
                return;
            }

            String lastUsageData = support.streamSseResponse(conn, httpResponse, null);

            long duration = System.currentTimeMillis() - startTime;
            RelayServiceUtils.UsageInfo usage = RelayServiceUtils.parseGeminiStreamUsage(support.objectMapper, lastUsageData);
            support.recordStreamUsage(token, channel, model,
                    usage.promptTokens(), usage.completionTokens(),
                    0, 0, 0, duration, httpRequest,
                    "/v1/models/" + model + ":streamGenerateContent");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * OpenAI 渠道流式发送，Gemini 请求 -> OpenAI SSE -> Gemini SSE（单渠道不重试）
     */
    private void forwardOpenAiStreamAsGeminiSingle(Channel channel, String requestBody,
                                                    String model, Token token,
                                                    HttpServletRequest httpRequest,
                                                    HttpServletResponse httpResponse) throws IOException {
        if (support.isChannelRateLimited(channel)) {
            throw new BusinessException(429, "渠道请求频率超限，请稍后重试");
        }

        String openAiBody = ProtocolConverter.convertGeminiToOpenAiBody(requestBody);
        openAiBody = support.injectStreamOptions(openAiBody, "/v1/chat/completions");

        SseUtils.setSseHeaders(httpResponse);
        var writer = httpResponse.getWriter();

        long startTime = System.currentTimeMillis();
        int promptTokens = 0, completionTokens = 0, cachedTokens = 0;

        HttpURLConnection conn = null;
        try {
            conn = support.createSseConnection(channel, "/v1/chat/completions", openAiBody);
            int code = conn.getResponseCode();
            if (code != 200) {
                writer.write("data: {\"error\":{\"message\":\"上游错误\"}}\n\n");
                writer.flush();
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
                            JsonNode json = support.objectMapper.readTree(data);
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
        } finally {
            if (conn != null) conn.disconnect();
        }

        long duration = System.currentTimeMillis() - startTime;
        support.recordStreamUsage(token, channel, model, promptTokens, completionTokens,
                cachedTokens, 0, cachedTokens, duration, httpRequest,
                "/v1/models/" + model + ":streamGenerateContent");
    }

    /**
     * Claude 渠道流式发送，Gemini 请求 -> Claude SSE -> Gemini SSE（单渠道不重试）
     */
    private void forwardClaudeStreamAsGeminiSingle(Channel channel, String requestBody,
                                                    String model, Token token,
                                                    HttpServletRequest httpRequest,
                                                    HttpServletResponse httpResponse) throws IOException {
        if (support.isChannelRateLimited(channel)) {
            throw new BusinessException(429, "渠道请求频率超限，请稍后重试");
        }

        String claudeBody = ProtocolConverter.convertGeminiToClaudeBody(requestBody);
        SseUtils.setSseHeaders(httpResponse);
        var writer = httpResponse.getWriter();

        long startTime = System.currentTimeMillis();
        int promptTokens = 0, completionTokens = 0;

        HttpURLConnection conn = null;
        try {
            conn = support.createSseConnection(channel, "/v1/messages", claudeBody);
            int code = conn.getResponseCode();
            if (code != 200) {
                writer.write("data: {\"error\":{\"message\":\"上游错误\"}}\n\n");
                writer.flush();
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
                            JsonNode json = support.objectMapper.readTree(data);
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
        } finally {
            if (conn != null) conn.disconnect();
        }

        long duration = System.currentTimeMillis() - startTime;
        support.recordStreamUsage(token, channel, model, promptTokens, completionTokens,
                0, 0, 0, duration, httpRequest,
                "/v1/models/" + model + ":streamGenerateContent");
    }

    // ==================== Gemini → OpenAI 流式转换 ====================

    /**
     * 读取 Gemini SSE 响应并转换为 OpenAI SSE 格式输出
     * 返回最后包含 usage 的数据行
     */
    public String streamGeminiResponseAsOpenAi(HttpURLConnection conn, HttpServletResponse httpResponse) throws IOException {
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
