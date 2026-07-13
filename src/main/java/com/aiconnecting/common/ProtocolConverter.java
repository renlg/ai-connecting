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
 * 支持：多模态（图片）、Function Calling（tools）、stop_sequences 等
 */
@Slf4j
public final class ProtocolConverter {

    private ProtocolConverter() {}

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // ==================== Claude → OpenAI 请求转换 ====================

    /**
     * 将 Claude 格式的请求体转换为 OpenAI 格式
     * 支持：system、messages（含多模态/工具调用）、tools、tool_choice、stop_sequences 等
     */
    public static String convertClaudeToOpenAiBody(String claudeBody) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(claudeBody);
            Map<String, Object> openAiMap = new LinkedHashMap<>();
            if (node.has("model")) {
                openAiMap.put("model", node.get("model").asText());
            }

            // 构建 messages 数组（支持 tool_result 和 tool_use 转换）
            List<Map<String, Object>> messages = new ArrayList<>();
            if (node.has("system")) {
                messages.add(buildSystemMessage(node.get("system")));
            }
            if (node.has("messages")) {
                for (JsonNode msg : node.get("messages")) {
                    convertMessage(msg, messages);
                }
            }
            openAiMap.put("messages", messages);

            // 基础参数
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

            // stop_sequences → stop
            if (node.has("stop_sequences")) {
                List<String> stop = new ArrayList<>();
                for (JsonNode s : node.get("stop_sequences")) {
                    stop.add(s.asText());
                }
                openAiMap.put("stop", stop);
            }

            // tools → tools（Claude 格式转 OpenAI 格式）
            if (node.has("tools")) {
                openAiMap.put("tools", convertTools(node.get("tools")));
            }

            // tool_choice 转换
            if (node.has("tool_choice")) {
                openAiMap.put("tool_choice", convertToolChoice(node.get("tool_choice")));
            }

            return OBJECT_MAPPER.writeValueAsString(openAiMap);
        } catch (Exception e) {
            log.warn("转换 Claude 请求体失败，使用原始 body: {}", e.getMessage());
            return claudeBody;
        }
    }

    /** 构建 system 消息（支持字符串和数组格式） */
    private static Map<String, Object> buildSystemMessage(JsonNode systemNode) {
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
        return Map.of("role", "system", "content", systemText);
    }

    /**
     * 将单条 Claude 消息转换为 OpenAI 格式消息
     * 处理 tool_result → role:tool 拆分，tool_use → tool_calls 转换
     */
    private static void convertMessage(JsonNode msg, List<Map<String, Object>> messages) {
        String role = msg.path("role").asText("");
        JsonNode contentNode = msg.get("content");

        // 检查是否包含工具相关块
        boolean hasToolResult = false;
        boolean hasToolUse = false;
        if (contentNode != null && contentNode.isArray()) {
            for (JsonNode block : contentNode) {
                String type = block.path("type").asText("");
                if ("tool_result".equals(type)) hasToolResult = true;
                if ("tool_use".equals(type)) hasToolUse = true;
            }
        }

        // tool_result 块 → 拆分为独立的 role:tool 消息
        if (hasToolResult) {
            for (JsonNode block : contentNode) {
                if ("tool_result".equals(block.path("type").asText())) {
                    Map<String, Object> toolMsg = new LinkedHashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", block.path("tool_use_id").asText(""));
                    JsonNode resultContent = block.get("content");
                    if (resultContent != null && resultContent.isTextual()) {
                        toolMsg.put("content", resultContent.asText());
                    } else if (resultContent != null && resultContent.isArray()) {
                        StringBuilder sb = new StringBuilder();
                        for (JsonNode b : resultContent) {
                            if ("text".equals(b.path("type").asText())) {
                                if (sb.length() > 0) sb.append("\n");
                                sb.append(b.get("text").asText());
                            }
                        }
                        toolMsg.put("content", sb.toString());
                    } else {
                        toolMsg.put("content", "");
                    }
                    messages.add(toolMsg);
                }
            }
            return;
        }

        // 普通消息（可能包含 tool_use）
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        if (msg.has("name")) {
            message.put("name", msg.get("name").asText());
        }

        // assistant 消息中的 tool_use → tool_calls
        if (hasToolUse && "assistant".equals(role)) {
            List<Map<String, Object>> toolCalls = new ArrayList<>();
            StringBuilder textParts = new StringBuilder();
            for (JsonNode block : contentNode) {
                String type = block.path("type").asText("");
                if ("tool_use".equals(type)) {
                    Map<String, Object> toolCall = new LinkedHashMap<>();
                    toolCall.put("id", block.path("id").asText(""));
                    toolCall.put("type", "function");
                    Map<String, Object> function = new LinkedHashMap<>();
                    function.put("name", block.path("name").asText(""));
                    JsonNode input = block.get("input");
                    try {
                        function.put("arguments", input != null ? OBJECT_MAPPER.writeValueAsString(input) : "{}");
                    } catch (Exception e) {
                        function.put("arguments", "{}");
                    }
                    toolCall.put("function", function);
                    toolCalls.add(toolCall);
                } else if ("text".equals(type) && block.has("text")) {
                    if (textParts.length() > 0) textParts.append("\n");
                    textParts.append(block.get("text").asText());
                }
            }
            message.put("content", textParts.length() > 0 ? textParts.toString() : null);
            message.put("tool_calls", toolCalls);
        } else {
            message.put("content", convertClaudeContent(contentNode));
        }

        messages.add(message);
    }

    /**
     * 将 Claude 的 content 节点转换为 OpenAI 格式
     * 支持纯文本、多模态（文本+图片）等格式
     */
    private static Object convertClaudeContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isNull()) {
            return "";
        }
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (contentNode.isArray()) {
            boolean hasImage = false;
            for (JsonNode block : contentNode) {
                if ("image".equals(block.path("type").asText())) {
                    hasImage = true;
                    break;
                }
            }
            if (!hasImage) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : contentNode) {
                    if ("text".equals(block.path("type").asText()) && block.has("text")) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(block.get("text").asText());
                    }
                }
                return sb.toString();
            }
            List<Map<String, Object>> contentArray = new ArrayList<>();
            for (JsonNode block : contentNode) {
                String type = block.path("type").asText("");
                if ("text".equals(type) && block.has("text")) {
                    contentArray.add(Map.of("type", "text", "text", block.get("text").asText()));
                } else if ("image".equals(type) && block.has("source")) {
                    JsonNode source = block.get("source");
                    String sourceType = source.path("type").asText("");
                    String imageUrl;
                    if ("base64".equals(sourceType)) {
                        String mediaType = source.path("media_type").asText("image/png");
                        String data = source.path("data").asText("");
                        imageUrl = "data:" + mediaType + ";base64," + data;
                    } else if ("url".equals(sourceType)) {
                        imageUrl = source.path("url").asText("");
                    } else {
                        continue;
                    }
                    Map<String, Object> imageBlock = new LinkedHashMap<>();
                    imageBlock.put("type", "image_url");
                    imageBlock.put("image_url", Map.of("url", imageUrl));
                    contentArray.add(imageBlock);
                }
            }
            return contentArray;
        }
        return contentNode.toString();
    }

    /**
     * Claude tools 格式转 OpenAI tools 格式
     * Claude: {name, description, input_schema} → OpenAI: {type:"function", function:{name, description, parameters}}
     */
    private static List<Map<String, Object>> convertTools(JsonNode claudeTools) {
        List<Map<String, Object>> openAiTools = new ArrayList<>();
        for (JsonNode tool : claudeTools) {
            Map<String, Object> openAiTool = new LinkedHashMap<>();
            openAiTool.put("type", "function");
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.path("name").asText(""));
            if (tool.has("description")) {
                function.put("description", tool.get("description").asText());
            }
            JsonNode schema = tool.get("input_schema");
            if (schema != null && schema.isObject()) {
                function.put("parameters", OBJECT_MAPPER.convertValue(schema, Map.class));
            } else {
                Map<String, Object> defaultSchema = new LinkedHashMap<>();
                defaultSchema.put("type", "object");
                defaultSchema.put("properties", Map.of());
                function.put("parameters", defaultSchema);
            }
            openAiTool.put("function", function);
            openAiTools.add(openAiTool);
        }
        return openAiTools;
    }

    /**
     * Claude tool_choice 转 OpenAI tool_choice
     * auto→auto, any→required, none→none, {type:"tool",name:"x"}→{type:"function",function:{name:"x"}}
     */
    private static Object convertToolChoice(JsonNode toolChoice) {
        if (toolChoice.isTextual()) {
            String value = toolChoice.asText();
            if ("auto".equals(value)) return "auto";
            if ("any".equals(value)) return "required";
            if ("none".equals(value)) return "none";
            return "auto";
        }
        if (toolChoice.isObject()) {
            String type = toolChoice.path("type").asText("");
            if ("tool".equals(type)) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("type", "function");
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", toolChoice.path("name").asText(""));
                result.put("function", function);
                return result;
            }
        }
        return "auto";
    }

    // ==================== OpenAI → Claude 响应转换 ====================

    /**
     * 将 OpenAI 格式的响应转换为 Claude Messages API 格式
     * 支持 tool_calls 响应转换和 finish_reason 映射
     */
    public static String convertOpenAiToClaudeResponse(String openAiResponse) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(openAiResponse);
            Map<String, Object> claudeMap = new LinkedHashMap<>();
            claudeMap.put("id", "msg_" + System.currentTimeMillis());
            claudeMap.put("type", "message");
            claudeMap.put("role", "assistant");

            JsonNode message = node.path("choices").path(0).path("message");
            String content = message.path("content").asText("");
            String finishReason = node.path("choices").path(0).path("finish_reason").asText("stop");

            // 构建 content 数组（文本 + tool_use）
            List<Map<String, Object>> claudeContent = new ArrayList<>();
            if (content != null && !content.isEmpty()) {
                claudeContent.add(Map.of("type", "text", "text", content));
            }

            // tool_calls → tool_use blocks
            JsonNode toolCalls = message.get("tool_calls");
            if (toolCalls != null && toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    Map<String, Object> toolUse = new LinkedHashMap<>();
                    toolUse.put("type", "tool_use");
                    toolUse.put("id", tc.path("id").asText(""));
                    toolUse.put("name", tc.path("function").path("name").asText(""));
                    String args = tc.path("function").path("arguments").asText("{}");
                    try {
                        toolUse.put("input", OBJECT_MAPPER.readValue(args, Map.class));
                    } catch (Exception e) {
                        toolUse.put("input", Map.of());
                    }
                    claudeContent.add(toolUse);
                }
            }

            if (claudeContent.isEmpty()) {
                claudeContent.add(Map.of("type", "text", "text", ""));
            }
            claudeMap.put("content", claudeContent);

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

            // finish_reason 映射: stop→end_turn, tool_calls→tool_use, length→max_tokens
            claudeMap.put("stop_reason", mapFinishReason(finishReason));
            claudeMap.put("stop_sequence", null);

            return OBJECT_MAPPER.writeValueAsString(claudeMap);
        } catch (Exception e) {
            log.warn("转换 OpenAI 响应为 Claude 格式失败，返回原始响应: {}", e.getMessage());
            return openAiResponse;
        }
    }

    /** OpenAI finish_reason → Claude stop_reason */
    private static String mapFinishReason(String finishReason) {
        if (finishReason == null) return "end_turn";
        return switch (finishReason) {
            case "stop" -> "end_turn";
            case "tool_calls" -> "tool_use";
            case "length" -> "max_tokens";
            case "content_filter" -> "end_turn";
            default -> "end_turn";
        };
    }
}
