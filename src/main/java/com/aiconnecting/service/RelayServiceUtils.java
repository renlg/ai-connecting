package com.aiconnecting.service;

import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.Token;
import com.aiconnecting.entity.UsageLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 中转服务公共工具类，提取所有 RelayService / RelaySupport / 各协议 RelayService 中可复用的静态方法。
 * <p>
 * 涵盖：客户端 IP 提取、渠道类型判断、渠道认证头设置、模型权限解析、
 * 各协议 usage 解析、UsageLog 构建、SSE 流式读取、请求体处理、错误响应写入、
 * Gemini 流式 chunk 转换等。
 */
@Slf4j
public final class RelayServiceUtils {

    private RelayServiceUtils() {
    }

    public static final int MAX_RETRIES = 3;

    // ==================== 使用量数据载体 ====================

    /**
     * 各协议 usage 解析的统一结果
     */
    public record UsageInfo(
            int promptTokens,
            int completionTokens,
            int totalTokens,
            int cachedTokens,
            int cacheCreationTokens,
            int cacheReadTokens
    ) {
        public static final UsageInfo ZERO = new UsageInfo(0, 0, 0, 0, 0, 0);
    }

    // ==================== 客户端 IP 提取 ====================

    /**
     * 从 HttpServletRequest 中提取客户端真实 IP
     * 依次检查 X-Forwarded-For → X-Real-IP → remoteAddr
     */
    public static String getClientIp(HttpServletRequest request) {
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

    // ==================== 渠道类型判断 ====================

    /**
     * 判断渠道是否为 Claude/Anthropic 类型
     */
    public static boolean isClaudeTypeChannel(Channel channel) {
        return "claude".equalsIgnoreCase(channel.getType())
                || "anthropic".equalsIgnoreCase(channel.getType());
    }

    /**
     * 判断渠道是否为 Gemini 类型
     */
    public static boolean isGeminiTypeChannel(Channel channel) {
        return "gemini".equalsIgnoreCase(channel.getType());
    }

    // ==================== 渠道认证 ====================

    /**
     * 根据渠道类型为 OkHttp Request.Builder 设置认证头
     */
    public static void applyChannelAuth(Request.Builder builder, Channel channel) {
        if (isClaudeTypeChannel(channel)) {
            builder.addHeader("x-api-key", channel.getApiKey());
            builder.addHeader("anthropic-version", "2023-06-01");
        } else {
            builder.addHeader("Authorization", "Bearer " + channel.getApiKey());
        }
    }

    /**
     * 根据渠道类型为 HttpURLConnection 设置认证头
     */
    public static void applyChannelAuthToConnection(HttpURLConnection conn, Channel channel) {
        if (isClaudeTypeChannel(channel)) {
            conn.setRequestProperty("x-api-key", channel.getApiKey());
            conn.setRequestProperty("anthropic-version", "2023-06-01");
        } else {
            conn.setRequestProperty("Authorization", "Bearer " + channel.getApiKey());
        }
    }

    // ==================== 模型权限解析 ====================

    /**
     * 将逗号分隔的允许模型字符串解析为 Set
     */
    public static Set<String> parseAllowedModels(String allowedModels) {
        if (allowedModels == null || allowedModels.isEmpty()) {
            return Set.of();
        }
        return Arrays.stream(allowedModels.split(","))
                .map(String::trim)
                .collect(Collectors.toSet());
    }

    // ==================== Usage 解析（各协议） ====================

    /**
     * 解析 OpenAI 格式的非流式响应 usage
     * 支持 prompt_tokens_details.cached_tokens 和 Claude 格式的 cache 字段
     */
    public static UsageInfo parseOpenAiUsage(ObjectMapper mapper, String response) {
        try {
            JsonNode jsonNode = mapper.readTree(response);
            JsonNode usage = jsonNode.get("usage");
            if (usage == null) return UsageInfo.ZERO;

            int promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : 0;
            int completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : 0;
            int totalTokens = usage.has("total_tokens") ? usage.get("total_tokens").asInt() : 0;

            int cachedTokens = 0;
            JsonNode promptDetails = usage.path("prompt_tokens_details");
            if (!promptDetails.isMissingNode()) {
                cachedTokens = promptDetails.has("cached_tokens") ? promptDetails.get("cached_tokens").asInt() : 0;
            }

            int cacheCreationTokens = usage.has("cache_creation_input_tokens")
                    ? usage.get("cache_creation_input_tokens").asInt() : 0;
            int cacheReadTokens = usage.has("cache_read_input_tokens")
                    ? usage.get("cache_read_input_tokens").asInt() : 0;
            if (cachedTokens == 0 && cacheReadTokens > 0) {
                cachedTokens = cacheReadTokens;
            }

            return new UsageInfo(promptTokens, completionTokens, totalTokens,
                    cachedTokens, cacheCreationTokens, cacheReadTokens);
        } catch (Exception e) {
            log.warn("Failed to parse usage from response");
            return UsageInfo.ZERO;
        }
    }

    /**
     * 解析 Claude 格式的非流式响应 usage
     */
    public static UsageInfo parseClaudeUsage(ObjectMapper mapper, String response) {
        try {
            JsonNode jsonNode = mapper.readTree(response);
            JsonNode usage = jsonNode.get("usage");
            if (usage == null) return UsageInfo.ZERO;

            int inputTokens = usage.has("input_tokens") ? usage.get("input_tokens").asInt() : 0;
            int completionTokens = usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0;
            int cacheCreationTokens = usage.has("cache_creation_input_tokens")
                    ? usage.get("cache_creation_input_tokens").asInt() : 0;
            int cacheReadTokens = usage.has("cache_read_input_tokens")
                    ? usage.get("cache_read_input_tokens").asInt() : 0;
            int promptTokens = inputTokens + cacheReadTokens;

            return new UsageInfo(promptTokens, completionTokens, promptTokens + completionTokens,
                    cacheReadTokens, cacheCreationTokens, cacheReadTokens);
        } catch (Exception e) {
            log.warn("解析 Claude 响应 usage 失败: {}", e.getMessage());
            return UsageInfo.ZERO;
        }
    }

    /**
     * 解析 Claude 流式响应中的 usage（包含 message.usage 和顶层 usage 两个来源）
     */
    public static UsageInfo parseClaudeStreamUsage(ObjectMapper mapper, String lastUsageData) {
        if (lastUsageData == null) return UsageInfo.ZERO;
        try {
            JsonNode lastJson = mapper.readTree(lastUsageData);
            int promptTokens = 0, completionTokens = 0;
            int cacheCreationTokens = 0, cacheReadTokens = 0;

            JsonNode usageNode = lastJson.get("usage");
            if (usageNode != null) {
                completionTokens = usageNode.has("output_tokens") ? usageNode.get("output_tokens").asInt() : 0;
                cacheCreationTokens = usageNode.has("cache_creation_input_tokens")
                        ? usageNode.get("cache_creation_input_tokens").asInt() : 0;
                cacheReadTokens = usageNode.has("cache_read_input_tokens")
                        ? usageNode.get("cache_read_input_tokens").asInt() : 0;
            }

            if (lastJson.has("message") && lastJson.get("message").has("usage")) {
                JsonNode msgUsage = lastJson.get("message").get("usage");
                int msgInputTokens = msgUsage.has("input_tokens") ? msgUsage.get("input_tokens").asInt() : 0;
                promptTokens = msgInputTokens;
                if (cacheReadTokens == 0) {
                    cacheReadTokens = msgUsage.has("cache_read_input_tokens")
                            ? msgUsage.get("cache_read_input_tokens").asInt() : 0;
                }
                if (cacheCreationTokens == 0) {
                    cacheCreationTokens = msgUsage.has("cache_creation_input_tokens")
                            ? msgUsage.get("cache_creation_input_tokens").asInt() : 0;
                }
            }
            promptTokens += cacheReadTokens;

            return new UsageInfo(promptTokens, completionTokens, promptTokens + completionTokens,
                    cacheReadTokens, cacheCreationTokens, cacheReadTokens);
        } catch (Exception e) {
            log.warn("解析 Claude 流式 usage 失败: {}", e.getMessage());
            return UsageInfo.ZERO;
        }
    }

    /**
     * 解析 Gemini 格式的非流式响应 usage（usageMetadata）
     */
    public static UsageInfo parseGeminiUsage(ObjectMapper mapper, String response) {
        try {
            JsonNode jsonNode = mapper.readTree(response);
            JsonNode usage = jsonNode.get("usageMetadata");
            if (usage == null) return UsageInfo.ZERO;

            int promptTokens = usage.has("promptTokenCount") ? usage.get("promptTokenCount").asInt() : 0;
            int completionTokens = usage.has("candidatesTokenCount") ? usage.get("candidatesTokenCount").asInt() : 0;

            return new UsageInfo(promptTokens, completionTokens, promptTokens + completionTokens,
                    0, 0, 0);
        } catch (Exception e) {
            log.warn("解析 Gemini 响应 usage 失败: {}", e.getMessage());
            return UsageInfo.ZERO;
        }
    }

    /**
     * 从 OpenAI 格式的流式 usage 数据行中解析 token 计数
     */
    public static UsageInfo parseOpenAiStreamUsage(ObjectMapper mapper, String lastUsageData) {
        if (lastUsageData == null) return UsageInfo.ZERO;
        try {
            JsonNode usageNode = mapper.readTree(lastUsageData).get("usage");
            if (usageNode == null) return UsageInfo.ZERO;

            int promptTokens = usageNode.has("prompt_tokens") ? usageNode.get("prompt_tokens").asInt() : 0;
            int completionTokens = usageNode.has("completion_tokens") ? usageNode.get("completion_tokens").asInt() : 0;
            int cachedTokens = 0;
            JsonNode promptDetails = usageNode.path("prompt_tokens_details");
            if (!promptDetails.isMissingNode()) {
                cachedTokens = promptDetails.has("cached_tokens") ? promptDetails.get("cached_tokens").asInt() : 0;
            }

            return new UsageInfo(promptTokens, completionTokens, promptTokens + completionTokens,
                    cachedTokens, 0, cachedTokens);
        } catch (Exception e) {
            log.warn("解析流式响应 usage 数据失败: {}", e.getMessage());
            return UsageInfo.ZERO;
        }
    }

    /**
     * 从 Gemini 流式数据行中解析 usageMetadata
     */
    public static UsageInfo parseGeminiStreamUsage(ObjectMapper mapper, String lastUsageData) {
        if (lastUsageData == null) return UsageInfo.ZERO;
        try {
            JsonNode lastJson = mapper.readTree(lastUsageData);
            JsonNode usageNode = lastJson.has("usageMetadata") ? lastJson.get("usageMetadata") : null;
            if (usageNode == null) return UsageInfo.ZERO;

            int promptTokens = usageNode.has("promptTokenCount") ? usageNode.get("promptTokenCount").asInt() : 0;
            int completionTokens = usageNode.has("candidatesTokenCount")
                    ? usageNode.get("candidatesTokenCount").asInt() : 0;

            return new UsageInfo(promptTokens, completionTokens, promptTokens + completionTokens,
                    0, 0, 0);
        } catch (Exception e) {
            log.warn("解析 Gemini 流式 usage 失败: {}", e.getMessage());
            return UsageInfo.ZERO;
        }
    }

    // ==================== UsageLog 构建 ====================

    /**
     * 构建 UsageLog 实体（非流式，从 UsageInfo 取值）
     */
    public static UsageLog buildUsageLog(Token token, Channel channel, String model,
                                         UsageInfo usage, BigDecimal creditCost,
                                         long duration, String clientIp, String path) {
        return UsageLog.builder()
                .tokenId(token.getId())
                .channelId(channel.getId())
                .model(model)
                .promptTokens(usage.promptTokens())
                .completionTokens(usage.completionTokens())
                .totalTokens(usage.totalTokens())
                .promptTokensCacheHit(usage.cachedTokens())
                .cachedTokensCacheCreation(usage.cacheCreationTokens())
                .cachedTokensCacheRead(usage.cacheReadTokens())
                .creditCost(creditCost)
                .ip(clientIp)
                .duration(duration)
                .requestPath(path)
                .build();
    }

    /**
     * 构建 UsageLog 实体（流式，从显式 token 计数取值）
     */
    public static UsageLog buildStreamUsageLog(Token token, Channel channel, String model,
                                               int promptTokens, int completionTokens,
                                               int cachedTokens, int cacheCreationTokens, int cacheReadTokens,
                                               BigDecimal creditCost, long duration,
                                               String clientIp, String path) {
        int totalTokens = promptTokens + completionTokens;
        return UsageLog.builder()
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
                .ip(clientIp)
                .duration(duration)
                .requestPath(path)
                .build();
    }

    // ==================== SSE 流式读取 ====================

    /**
     * 流式读取上游 SSE 响应并透传给客户端，返回最后包含 usage 的 data 行内容
     *
     * @param conn         上游 HTTP 连接
     * @param httpResponse 下游 Servlet 响应
     * @param usageFilter  判断某行 data 是否为 usage 行的谓词；为 null 时使用默认规则（包含 "usage"）
     */
    public static String streamSseResponse(HttpURLConnection conn, HttpServletResponse httpResponse,
                                           Predicate<String> usageFilter) throws IOException {
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

    // ==================== 请求体处理 ====================

    /**
     * 为 chat/completions 请求注入 stream_options.include_usage = true
     */
    public static String injectStreamOptions(ObjectMapper mapper, String requestBody, String path) {
        if (!path.contains("/chat/completions")) return requestBody;
        try {
            JsonNode jsonBody = mapper.readTree(requestBody);
            if (jsonBody.isObject()) {
                ObjectNode streamOptions = mapper.createObjectNode();
                streamOptions.put("include_usage", true);
                ((ObjectNode) jsonBody).set("stream_options", streamOptions);
                return mapper.writeValueAsString(jsonBody);
            }
        } catch (Exception e) {
            log.warn("注入 stream_options 失败，使用原始请求体");
        }
        return requestBody;
    }

    // ==================== 错误响应写入 ====================

    /**
     * 写入 OpenAI 格式的错误响应
     */
    public static void writeOpenAiError(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.getWriter().write("{\"error\":{\"message\":\"" + message + "\"}}");
    }

    /**
     * 写入 Claude 格式的错误响应
     */
    public static void writeClaudeError(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.getWriter().write(
                "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"" + message + "\"}}");
    }

    /**
     * 写入 Gemini 格式的错误响应
     */
    public static void writeGeminiError(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.getWriter().write("{\"error\":{\"message\":\"" + message + "\"}}");
    }

    // ==================== Gemini 流式 chunk 转换 ====================

    /**
     * 将 Gemini 流式 JSON chunk 转换为 OpenAI SSE 格式的字符串
     * 返回 null 表示转换失败
     */
    public static String convertGeminiStreamChunkToOpenAiSse(ObjectMapper mapper, JsonNode json) {
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

            return mapper.writeValueAsString(chunk);
        } catch (Exception e) {
            log.warn("转换 Gemini stream chunk 为 OpenAI SSE 失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 渠道限流检查 ====================

    /**
     * 检查渠道是否被限流。需要外部传入 RateLimitService 的可选引用和检查逻辑。
     *
     * @param channel         渠道
     * @param rateLimitChecker 执行限流检查的回调，如果渠道被限流应抛出 BusinessException
     * @return true 表示被限流
     */
    public static boolean isChannelRateLimited(Channel channel, Runnable rateLimitChecker) {
        if (rateLimitChecker != null) {
            try {
                rateLimitChecker.run();
            } catch (com.aiconnecting.common.BusinessException e) {
                return true;
            }
        }
        return false;
    }
}
