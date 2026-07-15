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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Anthropic Claude 协议的中转服务
 * 处理 Claude Messages API 的请求构建、转发和 SSE 流式响应处理
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClaudeRelayService {

    private final RelaySupport support;

    /**
     * Claude Messages API 中转 (非流式) - 最多重试 3 次
     * 选中的渠道是 Claude 类型则直接发送，否则转换为 OpenAI 格式发送
     */
    public String claudeRelayRequest(String tokenKey, String requestBody,
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

            try {
                String response;
                if (support.isClaudeTypeChannel(channel)) {
                    response = support.forwardClaudeRequest(channel, requestBody);
                } else if (support.isGeminiTypeChannel(channel)) {
                    String geminiBody = ProtocolConverter.convertClaudeToGeminiRequest(requestBody);
                    response = support.forwardGeminiRequest(channel, geminiBody);
                    response = ProtocolConverter.convertGeminiToClaudeResponse(response);
                } else {
                    String openAiBody = ProtocolConverter.convertClaudeToOpenAiBody(requestBody);
                    String openAiResponse = support.forwardRequest(channel, "/v1/chat/completions", openAiBody);
                    response = ProtocolConverter.convertOpenAiToClaudeResponse(openAiResponse);
                }

                long duration = System.currentTimeMillis() - startTime;
                RelayServiceUtils.UsageInfo usage = RelayServiceUtils.parseClaudeUsage(support.objectMapper, response);
                support.recordStreamUsage(ctx.token(), channel, model,
                        usage.promptTokens(), usage.completionTokens(),
                        usage.cachedTokens(), usage.cacheCreationTokens(), usage.cacheReadTokens(),
                        duration, httpRequest, "/v1/messages");
                support.channelHealthTracker.recordSuccess(channel.getId());
                return response;
            } catch (BusinessException e) {
                lastError = e.getMessage();
                log.error("Claude 渠道 {} 请求失败 (尝试 {}/{}): {}", channel.getId(), attempt, RelaySupport.MAX_RETRIES, e.getMessage());
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
     * Claude Messages API 中转 (流式 SSE) - 最多重试 3 次，仅在响应未提交前可重试
     */
    public void claudeRelayStreamRequest(String tokenKey, String requestBody,
                                          String model, HttpServletRequest httpRequest,
                                          HttpServletResponse httpResponse) throws IOException {
        log.info("[Claude流式] 开始处理, model={}", model);
        RelaySupport.RelayContext ctx = support.validateAndPrepare(tokenKey, model);
        Set<Long> triedChannels = new HashSet<>();
        String lastError = null;

        for (int attempt = 1; attempt <= RelaySupport.MAX_RETRIES; attempt++) {
            Channel channel;
            try {
                channel = support.channelRouter.selectChannel(ctx.channelModelId(), triedChannels);
            } catch (BusinessException e) {
                if (!httpResponse.isCommitted()) {
                    RelayServiceUtils.writeClaudeError(httpResponse, 502, "所有渠道均不可用");
                }
                return;
            }
            triedChannels.add(channel.getId());
            log.info("[Claude流式] 尝试 {}/{}, channel={}", attempt, RelaySupport.MAX_RETRIES, channel.getId());

            try {
                if (support.isClaudeTypeChannel(channel)) {
                    forwardClaudeStreamSingle(channel, requestBody, model, ctx.token(), httpRequest, httpResponse);
                } else if (support.isGeminiTypeChannel(channel)) {
                    forwardGeminiStreamAsClaudeSingle(channel, requestBody, model, ctx.token(), httpRequest, httpResponse);
                } else {
                    forwardOpenAiStreamAsClaudeSingle(channel, requestBody, model, ctx.token(), httpRequest, httpResponse);
                }
                support.channelHealthTracker.recordSuccess(channel.getId());
                log.info("[Claude流式] 处理完成, channel={}", channel.getId());
                return;
            } catch (Exception e) {
                lastError = e.getMessage();
                log.error("[Claude流式] 渠道 {} 失败 (尝试 {}/{}): {}", channel.getId(), attempt, RelaySupport.MAX_RETRIES, e.getMessage());
                support.channelHealthTracker.recordFailure(channel.getId(), e.getMessage());
                if (attempt < RelaySupport.MAX_RETRIES && !httpResponse.isCommitted()) {
                    log.info("[Claude流式] 响应未提交，尝试下一个渠道");
                    continue;
                }
                if (!httpResponse.isCommitted()) {
                    RelayServiceUtils.writeClaudeError(httpResponse, 502, "渠道请求失败");
                }
                return;
            }
        }
    }

    // ==================== Claude 流式转发（单渠道） ====================

    /**
     * 使用 Claude 渠道直接流式发送（原生 Claude 协议，单渠道不重试）
     */
    private void forwardClaudeStreamSingle(Channel channel, String requestBody,
                                            String model, Token token,
                                            HttpServletRequest httpRequest,
                                            HttpServletResponse httpResponse) throws IOException {
        if (support.isChannelRateLimited(channel)) {
            throw new BusinessException(429, "渠道请求频率超限，请稍后重试");
        }

        SseUtils.setSseHeaders(httpResponse);
        long startTime = System.currentTimeMillis();

        HttpURLConnection conn = support.createSseConnection(channel, "/v1/messages", requestBody);
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
            String lastUsageData = support.streamSseResponse(conn, httpResponse,
                    data -> data.contains("\"input_tokens\"") || data.contains("\"output_tokens\""));
            var writer = httpResponse.getWriter();
            writer.write("data: [DONE]\n\n");
            writer.flush();
            conn.disconnect();

            long duration = System.currentTimeMillis() - startTime;
            RelayServiceUtils.UsageInfo usage = RelayServiceUtils.parseClaudeStreamUsage(support.objectMapper, lastUsageData);
            support.recordStreamUsage(token, channel, model,
                    usage.promptTokens(), usage.completionTokens(),
                    usage.cachedTokens(), usage.cacheCreationTokens(), usage.cacheReadTokens(),
                    duration, httpRequest, "/v1/messages");
        } catch (Exception e) {
            conn.disconnect();
            log.error("渠道 {} Claude 流式请求异常: {}", channel.getId(), e.getMessage());
            if (!httpResponse.isCommitted()) {
                RelayServiceUtils.writeClaudeError(httpResponse, 502, "渠道请求失败");
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
        if (support.isChannelRateLimited(channel)) {
            throw new BusinessException(429, "渠道请求频率超限，请稍后重试");
        }

        String openAiBody = ProtocolConverter.convertClaudeToOpenAiBody(requestBody);
        openAiBody = support.injectStreamOptions(openAiBody, "/v1/chat/completions");

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

        HttpURLConnection conn = support.createSseConnection(channel, "/v1/chat/completions", openAiBody);
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
                            JsonNode delta = json.path("choices").path(0).path("delta");
                            String content = delta.path("content").asText("");
                            String reasoningContent = delta.path("reasoning_content").asText("");
                            String text = content.isEmpty() ? reasoningContent : content;
                            if (!text.isEmpty()) {
                                String claudeEvt = "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":" + support.objectMapper.writeValueAsString(text) + "}}";
                                writer.write("data: " + claudeEvt + "\n\n");
                                writer.flush();
                            }
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
        if (!toolCalls.isEmpty()) {
            int tcIndex = 1;
            for (Map<String, Object> tc : toolCalls) {
                String tcId = (String) tc.getOrDefault("id", "call_" + tcIndex);
                String tcName = (String) tc.getOrDefault("name", "unknown");
                String tcArgs = (String) tc.getOrDefault("arguments", "{}");
                writer.write("data: {\"type\":\"content_block_start\",\"index\":" + tcIndex + ",\"content_block\":{\"type\":\"tool_use\",\"id\":\"" + tcId + "\",\"name\":\"" + tcName + "\",\"input\":{}}}");
                writer.write("\n\n");
                writer.write("data: {\"type\":\"content_block_delta\",\"index\":" + tcIndex + ",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":" + support.objectMapper.writeValueAsString(tcArgs) + "}}");
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
        support.recordStreamUsage(token, channel, model, promptTokens, completionTokens,
                cachedTokens, 0, cachedTokens, duration, httpRequest, "/v1/messages");
    }

    /**
     * Gemini 渠道流式发送，Claude 请求 -> Gemini SSE -> Claude SSE（单渠道不重试）
     */
    private void forwardGeminiStreamAsClaudeSingle(Channel channel, String requestBody,
                                                    String model, Token token,
                                                    HttpServletRequest httpRequest,
                                                    HttpServletResponse httpResponse) throws IOException {
        if (support.isChannelRateLimited(channel)) {
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
                            JsonNode json = support.objectMapper.readTree(data);
                            JsonNode candidates = json.get("candidates");
                            if (candidates != null && candidates.isArray() && candidates.size() > 0) {
                                JsonNode candidate = candidates.get(0);
                                JsonNode content = candidate.get("content");
                                if (content != null && content.has("parts")) {
                                    for (JsonNode part : content.get("parts")) {
                                        if (part.has("text")) {
                                            String text = part.get("text").asText("");
                                            if (!text.isEmpty()) {
                                                String claudeEvt = "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":" + support.objectMapper.writeValueAsString(text) + "}}";
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
        support.recordStreamUsage(token, channel, model, promptTokens, completionTokens,
                0, 0, 0, duration, httpRequest, "/v1/messages");
    }
}
