package com.aiconnecting.service;

import com.aiconnecting.common.SseUtils;
import com.aiconnecting.entity.Channel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中转服务公共工具类，提取所有 RelayService / RelaySupport / 各协议 RelayService 中可复用的静态方法。
 * <p>
 * 涵盖：渠道类型判断、各协议 usage 解析、错误响应写入、Gemini 流式 chunk 转换等。
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

    // ==================== Usage 解析（各协议） ====================

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

    /**
     * 统一解析流式 usage 数据行：兼容 OpenAI(usage.prompt_tokens)、Claude(usage.input_tokens)
     * 与 Gemini(usageMetadata) 三种格式，供流中断时从最后一条含 usage 的数据行恢复已解析的 partial usage
     */
    public static UsageInfo parseStreamUsageAny(ObjectMapper mapper, String lastUsageData) {
        if (lastUsageData == null) return UsageInfo.ZERO;
        try {
            JsonNode root = mapper.readTree(lastUsageData);
            JsonNode usage = root.get("usage");
            if (usage != null && usage.isObject()) {
                int promptTokens = intOf(usage, "prompt_tokens", "input_tokens");
                int completionTokens = intOf(usage, "completion_tokens", "output_tokens");
                int cacheCreationTokens = usage.path("cache_creation_input_tokens").asInt(0);
                int cacheReadTokens = usage.path("cache_read_input_tokens").asInt(0);
                int cachedTokens = usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);
                if (cachedTokens == 0 && cacheReadTokens > 0) cachedTokens = cacheReadTokens;
                return new UsageInfo(promptTokens, completionTokens, promptTokens + completionTokens,
                        cachedTokens, cacheCreationTokens, cacheReadTokens);
            }
            JsonNode meta = root.get("usageMetadata");
            if (meta != null && meta.isObject()) {
                int promptTokens = meta.path("promptTokenCount").asInt(0);
                int completionTokens = meta.path("candidatesTokenCount").asInt(0);
                return new UsageInfo(promptTokens, completionTokens, promptTokens + completionTokens,
                        0, 0, 0);
            }
            return UsageInfo.ZERO;
        } catch (Exception e) {
            return UsageInfo.ZERO;
        }
    }

    private static int intOf(JsonNode node, String first, String second) {
        return node.has(first) ? node.path(first).asInt() : node.path(second).asInt(0);
    }

    /**
     * 上游未返回 usage 时的粗略估算兜底：约 4 字符折 1 token，宁可轻微高估也不允许零计费
     */
    public static int estimateTokensFromChars(long chars) {
        if (chars <= 0) return 0;
        return (int) Math.min(chars / 4, Integer.MAX_VALUE);
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

}
