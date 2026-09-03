package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.ProtocolConverter;
import com.aiconnecting.common.SseUtils;
import com.aiconnecting.common.UpstreamErrorUtils;
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
    private final RelayProtocolAdapter protocolAdapter;

    /**
     * Claude Messages API 中转 (非流式) - 最多重试 3 次
     * 选中的渠道是 Claude 类型则直接发送，否则转换为 OpenAI 格式发送
     */
    public String claudeRelayRequest(String tokenKey, String requestBody,
                                     String model, HttpServletRequest httpRequest) {
        RelaySupport.RelayContext ctx = support.validateAndPrepare(tokenKey, model);
        return doClaudeRelayRequest(ctx, requestBody, model, httpRequest);
    }

    /** 供后台测试等已持有 Token 实体的内部调用方复用：跳过明文 key 反查（库中仅存哈希），其余校验完全一致 */
    public String claudeRelayRequestForToken(Token token, String requestBody,
                                             String model, HttpServletRequest httpRequest) {
        RelaySupport.RelayContext ctx = support.prepareContextForToken(token, model);
        return doClaudeRelayRequest(ctx, requestBody, model, httpRequest);
    }

    private String doClaudeRelayRequest(RelaySupport.RelayContext ctx, String requestBody,
                                        String model, HttpServletRequest httpRequest) {
        support.checkTextBalanceEstimate(ctx, model, requestBody);
        Set<Long> triedChannels = new HashSet<>();
        Long modelConfigId = ctx.modelConfig() != null ? ctx.modelConfig().getId() : null;
        long startTime = System.currentTimeMillis();
        long deadline = startTime + support.singleModelTextBudgetMs();
        String lastError = null;
        int attempt = 0;

        BusinessException lastFailure = null;
        while (attempt < RelaySupport.MAX_RETRIES && System.currentTimeMillis() < deadline) {
            Channel channel;
            try {
                channel = support.channelRouter.selectChannel(ctx.channelModelId(), triedChannels, ctx.userLevel());
            } catch (BusinessException e) {
                if (lastError != null) {
                    throw lastFailure != null
                            ? wrapFailure(lastFailure, "所有渠道均不可用，最后错误: " + lastError)
                            : new BusinessException(502, "所有渠道均不可用，最后错误: " + lastError,
                                    "All channels are unavailable, please try again later");
                }
                throw e;
            }
            triedChannels.add(channel.getId());

            if (support.isChannelRateLimited(channel, ctx.channelModelId())) {
                lastError = "上游渠道请求频率超限";
                log.warn("跳过限流渠道 {}: {}", channel.getId(), lastError);
                continue;
            }

            attempt++;
            try {
                long remainingMs = support.failoverAttemptTimeoutMs(deadline, attempt, RelaySupport.MAX_RETRIES);
                String response;
                if (support.isClaudeTypeChannel(channel)) {
                    response = support.forwardClaudeRequest(channel, requestBody, remainingMs);
                } else if (support.isGeminiTypeChannel(channel)) {
                    String geminiBody = ProtocolConverter.convertClaudeToGeminiRequest(requestBody);
                    response = support.forwardGeminiRequest(channel, geminiBody, remainingMs);
                    response = ProtocolConverter.convertGeminiToClaudeResponse(response);
                } else {
                    String openAiBody = ProtocolConverter.convertClaudeToOpenAiBody(requestBody);
                    String openAiResponse = support.forwardRequest(channel, "/v1/chat/completions", openAiBody, remainingMs);
                    response = ProtocolConverter.convertOpenAiToClaudeResponse(openAiResponse);
                }

                long duration = System.currentTimeMillis() - startTime;
                RelayServiceUtils.UsageInfo usage = RelayServiceUtils.parseClaudeUsage(support.objectMapper, response);
                support.recordStreamUsageWithFallback(ctx.token(), channel, model,
                        usage.promptTokens(), usage.completionTokens(),
                        usage.cachedTokens(), usage.cacheCreationTokens(), usage.cacheReadTokens(),
                        response != null ? response.length() : 0, requestBody, duration, httpRequest, "/v1/messages");
                return response;
            } catch (BusinessException e) {
                lastFailure = e;
                lastError = e.getMessage();
                log.error("Claude 渠道 {} 请求失败 (尝试 {}/{}): {}", channel.getId(), attempt, RelaySupport.MAX_RETRIES, e.getMessage());
                support.dispatchRelayFailure(channel.getId(), channel.getName(), modelConfigId, e);
                if (System.currentTimeMillis() >= deadline) throw wrapFailure(e, "单模型请求超过总耗时预算");
                if (attempt == RelaySupport.MAX_RETRIES) {
                    throw wrapFailure(e, "所有渠道均不可用，最后错误: " + lastError);
                }
            }
        }
        throw new BusinessException(502, "所有渠道均不可用，最后错误: " + lastError,
                "All channels are unavailable, please try again later");
    }

    /** 包裹重试耗尽后的汇总错误，保留原始异常的 upstreamResponse 标记，避免真实上游错误被误判为本地错误而向终端用户泄露细节 */
    private BusinessException wrapFailure(BusinessException cause, String message) {
        return new BusinessException(cause.getCode(), message,
                "All channels are unavailable, please try again later", cause, cause.getUpstreamResponseBody(),
                cause.getRetryAfterSeconds(), cause.isUpstreamResponse());
    }

    /**
     * Claude Messages API 中转 (流式 SSE) - 最多重试 3 次，仅在响应未提交前可重试
     */
    public void claudeRelayStreamRequest(String tokenKey, String requestBody,
                                          String model, HttpServletRequest httpRequest,
                                          HttpServletResponse httpResponse) throws IOException {
        log.info("[Claude流式] 开始处理, model={}", model);
        RelaySupport.RelayContext ctx = support.validateAndPrepare(tokenKey, model);
        doClaudeRelayStreamRequest(ctx, requestBody, model, httpRequest, httpResponse);
    }

    /** 供后台测试等已持有 Token 实体的内部调用方复用：跳过明文 key 反查（库中仅存哈希），其余校验完全一致 */
    public void claudeRelayStreamRequestForToken(Token token, String requestBody,
                                                 String model, HttpServletRequest httpRequest,
                                                 HttpServletResponse httpResponse) throws IOException {
        RelaySupport.RelayContext ctx = support.prepareContextForToken(token, model);
        doClaudeRelayStreamRequest(ctx, requestBody, model, httpRequest, httpResponse);
    }

    private void doClaudeRelayStreamRequest(RelaySupport.RelayContext ctx, String requestBody,
                                            String model, HttpServletRequest httpRequest,
                                            HttpServletResponse httpResponse) throws IOException {
        support.checkTextBalanceEstimate(ctx, model, requestBody);
        Set<Long> triedChannels = new HashSet<>();
        Long modelConfigId = ctx.modelConfig() != null ? ctx.modelConfig().getId() : null;
        long retryDeadline = System.currentTimeMillis() + support.singleModelTextBudgetMs();
        String lastError = null;
        int attempt = 0;

        while (attempt < RelaySupport.MAX_RETRIES && System.currentTimeMillis() < retryDeadline) {
            Channel channel;
            try {
                channel = support.channelRouter.selectChannel(ctx.channelModelId(), triedChannels, ctx.userLevel());
            } catch (BusinessException e) {
                if (!httpResponse.isCommitted()) {
                    protocolAdapter.writeError(RelayProtocol.CLAUDE, httpResponse, e.getCode(),
                            SseUtils.clientErrorMessage(e.getMessage(), e.getEnglishMessage(), e.isUpstreamResponse()),
                            e.isUpstreamResponse());
                }
                return;
            }
            triedChannels.add(channel.getId());

            if (support.isChannelRateLimited(channel, ctx.channelModelId())) {
                lastError = "上游渠道请求频率超限";
                log.warn("[Claude流式] 跳过限流渠道 {}: {}", channel.getId(), lastError);
                continue;
            }

            attempt++;
            log.info("[Claude流式] 尝试 {}/{}, channel={}", attempt, RelaySupport.MAX_RETRIES, channel.getId());

            try {
                long remainingMs = support.failoverAttemptTimeoutMs(
                        retryDeadline, attempt, RelaySupport.MAX_RETRIES);
                if (support.isClaudeTypeChannel(channel)) {
                    forwardClaudeStreamSingle(channel, requestBody, model, ctx.token(), httpRequest, httpResponse, remainingMs);
                } else if (support.isGeminiTypeChannel(channel)) {
                    forwardGeminiStreamAsClaudeSingle(channel, requestBody, model, ctx.token(), httpRequest, httpResponse, remainingMs);
                } else {
                    forwardOpenAiStreamAsClaudeSingle(channel, requestBody, model, ctx.token(), httpRequest, httpResponse, remainingMs);
                }
                log.info("[Claude流式] 处理完成, channel={}", channel.getId());
                return;
            } catch (Exception e) {
                lastError = e.getMessage();
                log.error("[Claude流式] 渠道 {} 失败 (尝试 {}/{}): {}", channel.getId(), attempt, RelaySupport.MAX_RETRIES, e.getMessage());
                BusinessException classifyError = (e instanceof BusinessException be) ? be
                        : new BusinessException(502, "渠道请求失败: " + e.getMessage(),
                                "Channel request failed: " + e.getMessage(), e);
                support.dispatchRelayFailure(channel.getId(), channel.getName(), modelConfigId, classifyError);
                if (attempt < RelaySupport.MAX_RETRIES && !httpResponse.isCommitted()) {
                    log.info("[Claude流式] 响应未提交，尝试下一个渠道");
                    continue;
                }
                if (!httpResponse.isCommitted()) {
                    boolean upstream = !(e instanceof BusinessException) || classifyError.isUpstreamResponse();
                    protocolAdapter.writeError(RelayProtocol.CLAUDE, httpResponse, classifyError.getCode(),
                            SseUtils.clientErrorMessage(classifyError.getMessage(),
                                    classifyError.getEnglishMessage(), upstream), upstream);
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
                                            HttpServletResponse httpResponse, long attemptTimeoutMs) throws IOException {
        SseUtils.setSseHeaders(httpResponse);
        long startTime = System.currentTimeMillis();

        HttpURLConnection conn = null;
        try {
            conn = support.createSseConnection(channel, "/v1/messages", requestBody, attemptTimeoutMs);
            int code = conn.getResponseCode();
            log.info("HTTP请求返回, code: {}, channel: {}", code, channel.getId());
            if (code != 200) {
                String errorBody = conn.getErrorStream() != null
                        ? new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8) : "";
                FailureLogContext.setChannelError(code, errorBody);
                log.warn("渠道 {} Claude 流式请求失败: {} - {}", channel.getId(), code, errorBody);
                if (SseUtils.isEndUserRelayPath()) {
                    protocolAdapter.writeError(RelayProtocol.CLAUDE, httpResponse, code,
                            UpstreamErrorUtils.clientFacingMessage(code, errorBody), true);
                } else {
                    httpResponse.setStatus(code);
                    httpResponse.setCharacterEncoding("UTF-8");
                    httpResponse.getWriter().write(errorBody.isEmpty()
                            ? "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"上游返回 HTTP " + code + "\"}}" : errorBody);
                }
                return;
            }
            RelaySupport.SseStreamResult streamResult;
            try {
                streamResult = support.streamSseResponseTracked(conn, httpResponse,
                        data -> data.contains("\"input_tokens\"") || data.contains("\"output_tokens\""));
            } catch (RelaySupport.SseStreamingException e) {
                // 流中断但已有数据写给客户端：按已解析 partial usage（缺失时按字符估算）计费后再抛出
                long duration = System.currentTimeMillis() - startTime;
                RelayServiceUtils.UsageInfo partialUsage = e.partialResult().partialUsage();
                support.recordStreamUsageWithFallback(token, channel, model,
                        partialUsage.promptTokens(), partialUsage.completionTokens(),
                        partialUsage.cachedTokens(), partialUsage.cacheCreationTokens(), partialUsage.cacheReadTokens(),
                        e.partialResult().charsWritten(), requestBody, duration, httpRequest, "/v1/messages");
                throw e;
            }
            var writer = httpResponse.getWriter();
            writer.write("data: [DONE]\n\n");
            writer.flush();

            long duration = System.currentTimeMillis() - startTime;
            RelayServiceUtils.UsageInfo usage = RelayServiceUtils.parseClaudeStreamUsage(support.objectMapper, streamResult.lastUsageData());
            support.recordStreamUsageWithFallback(token, channel, model,
                    usage.promptTokens(), usage.completionTokens(),
                    usage.cachedTokens(), usage.cacheCreationTokens(), usage.cacheReadTokens(),
                    streamResult.charsWritten(), requestBody, duration, httpRequest, "/v1/messages");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 非 Claude 渠道流式发送，将 OpenAI SSE 转为 Claude SSE 格式（单渠道不重试）
     */
    private void forwardOpenAiStreamAsClaudeSingle(Channel channel, String requestBody,
                                                    String model, Token token,
                                                    HttpServletRequest httpRequest,
                                                    HttpServletResponse httpResponse, long attemptTimeoutMs) throws IOException {
        String openAiBody = ProtocolConverter.convertClaudeToOpenAiBody(requestBody);
        openAiBody = support.injectStreamOptions(openAiBody, "/v1/chat/completions");

        long startTime = System.currentTimeMillis();
        int promptTokens = 0, completionTokens = 0, cachedTokens = 0;
        long writtenChars = 0;
        List<Map<String, Object>> toolCalls = new ArrayList<>();

        HttpURLConnection conn = null;
        try {
            conn = support.createSseConnection(channel, "/v1/chat/completions", openAiBody, attemptTimeoutMs);
            int code = conn.getResponseCode();
            log.info("HTTP请求返回, code: {}, channel: {}", code, channel.getId());
            if (code != 200) {
                String errorBody = conn.getErrorStream() != null
                        ? new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8) : "";
                FailureLogContext.setChannelError(code, errorBody);
                log.warn("渠道 {} OpenAI-as-Claude 流式请求失败: {}", channel.getId(), code);
                protocolAdapter.writeError(RelayProtocol.CLAUDE, httpResponse, code,
                        UpstreamErrorUtils.clientFacingMessage(code, errorBody), true);
                return;
            }
            SseUtils.setSseHeaders(httpResponse);
            var writer = httpResponse.getWriter();
            String msgId = "msg_" + System.currentTimeMillis();
            writer.write("data: {\"type\":\"message_start\",\"message\":{\"id\":\"" + msgId + "\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"" + model + "\",\"stop_reason\":null,\"stop_sequence\":null}}\n\n");
            writer.write("data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n");
            writer.flush();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                long deadline = support.sseDeadline();
                try {
                    while (true) {
                        support.prepareSseRead(conn, deadline);
                        line = reader.readLine();
                        if (line == null) break;
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
                                    writtenChars += text.length();
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
                } catch (IOException streamFailure) {
                    // 流中断但已有数据写给客户端：按已解析 partial usage（缺失时按字符估算）计费后再抛出
                    support.recordStreamUsageWithFallback(token, channel, model,
                            promptTokens, completionTokens, cachedTokens, 0, cachedTokens,
                            writtenChars, requestBody, System.currentTimeMillis() - startTime,
                            httpRequest, "/v1/messages");
                    throw streamFailure;
                }
            }
        } finally {
            if (conn != null) conn.disconnect();
        }

        var writer = httpResponse.getWriter();
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
        support.recordStreamUsageWithFallback(token, channel, model, promptTokens, completionTokens,
                cachedTokens, 0, cachedTokens, writtenChars, requestBody, duration, httpRequest, "/v1/messages");
    }

    /**
     * Gemini 渠道流式发送，Claude 请求 -> Gemini SSE -> Claude SSE（单渠道不重试）
     */
    private void forwardGeminiStreamAsClaudeSingle(Channel channel, String requestBody,
                                                    String model, Token token,
                                                    HttpServletRequest httpRequest,
                                                    HttpServletResponse httpResponse, long attemptTimeoutMs) throws IOException {
        String geminiBody = ProtocolConverter.convertClaudeToGeminiRequest(requestBody);
        long startTime = System.currentTimeMillis();
        int promptTokens = 0, completionTokens = 0;
        long writtenChars = 0;

        HttpURLConnection conn = null;
        try {
            conn = support.createSseConnection(channel,
                    "/v1/models/" + model + ":streamGenerateContent?alt=sse", geminiBody, attemptTimeoutMs);

            int code = conn.getResponseCode();
            if (code != 200) {
                String errorBody = conn.getErrorStream() != null
                        ? new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8) : "";
                FailureLogContext.setChannelError(code, errorBody);
                protocolAdapter.writeError(RelayProtocol.CLAUDE, httpResponse, code,
                        UpstreamErrorUtils.clientFacingMessage(code, errorBody), true);
                return;
            }
            SseUtils.setSseHeaders(httpResponse);
            var writer = httpResponse.getWriter();
            String msgId = "msg_" + System.currentTimeMillis();
            writer.write("data: {\"type\":\"message_start\",\"message\":{\"id\":\"" + msgId + "\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"" + model + "\",\"stop_reason\":null,\"stop_sequence\":null}}\n\n");
            writer.write("data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n");
            writer.flush();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                long deadline = support.sseDeadline();
                try {
                    while (true) {
                        support.prepareSseRead(conn, deadline);
                        line = reader.readLine();
                        if (line == null) break;
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
                                                    writtenChars += text.length();
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
                } catch (IOException streamFailure) {
                    // 流中断但已有数据写给客户端：按已解析 partial usage（缺失时按字符估算）计费后再抛出
                    support.recordStreamUsageWithFallback(token, channel, model,
                            promptTokens, completionTokens, 0, 0, 0,
                            writtenChars, requestBody, System.currentTimeMillis() - startTime,
                            httpRequest, "/v1/messages");
                    throw streamFailure;
                }
            }
        } finally {
            if (conn != null) conn.disconnect();
        }

        var writer = httpResponse.getWriter();
        writer.write("data: {\"type\":\"content_block_stop\",\"index\":0}\n\n");
        writer.write("data: {\"type\":\"message_stop\"}\n\n");
        writer.write("data: [DONE]\n\n");
        writer.flush();

        long duration = System.currentTimeMillis() - startTime;
        support.recordStreamUsageWithFallback(token, channel, model, promptTokens, completionTokens,
                0, 0, 0, writtenChars, requestBody, duration, httpRequest, "/v1/messages");
    }
}
