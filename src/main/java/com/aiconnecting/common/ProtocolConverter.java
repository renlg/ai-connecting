package com.aiconnecting.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI ↔ Claude 协议格式转换工具类
 * 负责请求体和响应体的双向格式转换
 */
@Slf4j
public final class ProtocolConverter {

    private ProtocolConverter() {}

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 将 Claude 格式的请求体转换为 OpenAI 格式
     * 处理 Claude 的 system 字段和 content 数组格式
     */
    public static String convertClaudeToOpenAiBody(String claudeBody) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(claudeBody);
            Map<String, Object> openAiMap = new LinkedHashMap<>();
            if (node.has("model")) {
                openAiMap.put("model", node.get("model").asText());
            }
            // 构建 messages 数组
            List<Map<String, Object>> messages = new ArrayList<>();
            // Claude 的 system 字段转为 system message
            if (node.has("system")) {
                JsonNode systemNode = node.get("system");
                String systemText;
                if (systemNode.isTextual()) {
                    systemText = systemNode.asText();
                } else if (systemNode.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode block : systemNode) {
                        if (block.has("text")) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append(block.get("text").asText());
                        }
                    }
                    systemText = sb.toString();
                } else {
                    systemText = systemNode.toString();
                }
                if (!systemText.isEmpty()) {
                    messages.add(Map.of("role", "system", "content", systemText));
                }
            }
            // Claude 的 messages 数组，处理 content 可以是字符串或数组
            if (node.has("messages")) {
                for (JsonNode msg : node.get("messages")) {
                    String role = msg.path("role").asText("");
                    JsonNode contentNode = msg.get("content");
                    String contentText;
                    if (contentNode == null || contentNode.isNull()) {
                        contentText = "";
                    } else if (contentNode.isTextual()) {
                        contentText = contentNode.asText();
                    } else if (contentNode.isArray()) {
                        StringBuilder sb = new StringBuilder();
                        for (JsonNode block : contentNode) {
                            if ("text".equals(block.path("type").asText()) && block.has("text")) {
                                if (sb.length() > 0) sb.append("\n");
                                sb.append(block.get("text").asText());
                            }
                        }
                        contentText = sb.toString();
                    } else {
                        contentText = contentNode.toString();
                    }
                    messages.add(Map.of("role", role, "content", contentText));
                }
            }
            openAiMap.put("messages", messages);
            if (node.has("max_tokens")) {
                openAiMap.put("max_tokens", node.get("max_tokens").asInt());
            }
            if (node.has("stream")) {
                openAiMap.put("stream", node.get("stream").asBoolean());
            }
            if (node.has("temperature")) {
                openAiMap.put("temperature", node.get("temperature").asDouble());
            }
            if (node.has("top_p")) {
                openAiMap.put("top_p", node.get("top_p").asDouble());
            }
            return OBJECT_MAPPER.writeValueAsString(openAiMap);
        } catch (Exception e) {
            log.warn("转换 Claude 请求体失败，使用原始 body: {}", e.getMessage());
            return claudeBody;
        }
    }

    /**
     * 将 OpenAI 格式的响应转换为 Claude Messages API 格式
     */
    public static String convertOpenAiToClaudeResponse(String openAiResponse) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(openAiResponse);
            Map<String, Object> claudeMap = new LinkedHashMap<>();
            claudeMap.put("id", "msg_" + System.currentTimeMillis());
            claudeMap.put("type", "message");
            claudeMap.put("role", "assistant");
            String content = node.path("choices").path(0).path("message").path("content").asText("");
            claudeMap.put("content", List.of(Map.of("type", "text", "text", content)));
            if (node.has("model")) {
                claudeMap.put("model", node.get("model").asText());
            }
            if (node.has("usage")) {
                JsonNode usage = node.get("usage");
                Map<String, Object> claudeUsage = new LinkedHashMap<>();
                claudeUsage.put("input_tokens", usage.path("prompt_tokens").asInt(0));
                claudeUsage.put("output_tokens", usage.path("completion_tokens").asInt(0));
                claudeMap.put("usage", claudeUsage);
            }
            claudeMap.put("stop_reason", "end_turn");
            claudeMap.put("stop_sequence", null);
            return OBJECT_MAPPER.writeValueAsString(claudeMap);
        } catch (Exception e) {
            log.warn("转换 OpenAI 响应为 Claude 格式失败，返回原始响应: {}", e.getMessage());
            return openAiResponse;
        }
    }
}
