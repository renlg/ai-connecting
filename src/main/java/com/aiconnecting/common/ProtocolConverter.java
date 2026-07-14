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

    // ==================== OpenAI → Gemini 请求转换 ====================

    /**
     * 将 OpenAI 格式的请求体转换为 Gemini 格式
     * 用于 OpenAI 端点收到请求后，转发给 Gemini 渠道
     */
    public static String convertOpenAiToGeminiRequest(String openAiBody) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(openAiBody);
            Map<String, Object> geminiMap = new LinkedHashMap<>();

            if (node.has("model")) {
                geminiMap.put("model", node.get("model").asText());
            }

            List<Map<String, Object>> contents = new ArrayList<>();
            Map<String, Object> systemInstruction = null;

            if (node.has("messages")) {
                for (JsonNode msg : node.get("messages")) {
                    String role = msg.path("role").asText("");
                    JsonNode contentNode = msg.get("content");

                    if ("system".equals(role)) {
                        String sysText = contentNode != null ?
                                (contentNode.isTextual() ? contentNode.asText() : contentNode.toString()) : "";
                        systemInstruction = new LinkedHashMap<>();
                        List<Map<String, Object>> sysParts = new ArrayList<>();
                        sysParts.add(Map.of("text", sysText));
                        systemInstruction.put("parts", sysParts);
                        continue;
                    }

                    String geminiRole = "assistant".equals(role) ? "model" : "user";
                    Map<String, Object> content = new LinkedHashMap<>();
                    content.put("role", geminiRole);

                    List<Map<String, Object>> parts = new ArrayList<>();

                    if (contentNode != null) {
                        if (contentNode.isTextual()) {
                            parts.add(Map.of("text", contentNode.asText()));
                        } else if (contentNode.isArray()) {
                            StringBuilder textBuf = new StringBuilder();
                            for (JsonNode block : contentNode) {
                                String type = block.path("type").asText("");
                                if ("text".equals(type) && block.has("text")) {
                                    textBuf.append(block.get("text").asText());
                                } else if ("image_url".equals(type) && block.has("image_url")) {
                                    String url = block.get("image_url").path("url").asText("");
                                    if (url.startsWith("data:")) {
                                        int semiIdx = url.indexOf(';');
                                        int commaIdx = url.indexOf(',');
                                        if (semiIdx > 5 && commaIdx > semiIdx) {
                                            String mimeType = url.substring(5, semiIdx);
                                            String data = url.substring(commaIdx + 1);
                                            Map<String, Object> inlineData = new LinkedHashMap<>();
                                            inlineData.put("mimeType", mimeType);
                                            inlineData.put("data", data);
                                            parts.add(Map.of("inlineData", inlineData));
                                        }
                                    } else {
                                        Map<String, Object> fileData = new LinkedHashMap<>();
                                        fileData.put("mimeType", "image/png");
                                        fileData.put("fileUri", url);
                                        parts.add(Map.of("fileData", fileData));
                                    }
                                }
                            }
                            if (textBuf.length() > 0) {
                                parts.add(Map.of("text", textBuf.toString()));
                            }
                        }
                    }

                    // tool_calls → functionCall parts
                    JsonNode toolCalls = msg.get("tool_calls");
                    if (toolCalls != null && toolCalls.isArray()) {
                        for (JsonNode tc : toolCalls) {
                            Map<String, Object> functionCall = new LinkedHashMap<>();
                            functionCall.put("name", tc.path("function").path("name").asText(""));
                            String args = tc.path("function").path("arguments").asText("{}");
                            try {
                                functionCall.put("args", OBJECT_MAPPER.readValue(args, Map.class));
                            } catch (Exception e) {
                                functionCall.put("args", Map.of());
                            }
                            parts.add(Map.of("functionCall", functionCall));
                        }
                    }

                    // role:tool → functionResponse
                    if ("tool".equals(role)) {
                        Map<String, Object> functionResponse = new LinkedHashMap<>();
                        functionResponse.put("name", msg.path("name").asText(""));
                        String toolContent = contentNode != null ?
                                (contentNode.isTextual() ? contentNode.asText() : contentNode.toString()) : "{}";
                        try {
                            functionResponse.put("response", OBJECT_MAPPER.readValue(toolContent, Map.class));
                        } catch (Exception e) {
                            functionResponse.put("response", Map.of("result", toolContent));
                        }
                        parts.clear();
                        parts.add(Map.of("functionResponse", functionResponse));
                    }

                    if (parts.isEmpty()) {
                        parts.add(Map.of("text", ""));
                    }
                    content.put("parts", parts);
                    contents.add(content);
                }
            }

            geminiMap.put("contents", contents);
            if (systemInstruction != null) {
                geminiMap.put("systemInstruction", systemInstruction);
            }

            Map<String, Object> generationConfig = new LinkedHashMap<>();
            if (node.has("max_tokens")) generationConfig.put("maxOutputTokens", node.get("max_tokens").asInt());
            if (node.has("temperature")) generationConfig.put("temperature", node.get("temperature").asDouble());
            if (node.has("top_p")) generationConfig.put("topP", node.get("top_p").asDouble());
            if (node.has("stop")) {
                JsonNode stop = node.get("stop");
                if (stop.isArray()) {
                    List<String> stopSequences = new ArrayList<>();
                    for (JsonNode s : stop) stopSequences.add(s.asText());
                    generationConfig.put("stopSequences", stopSequences);
                }
            }
            if (!generationConfig.isEmpty()) {
                geminiMap.put("generationConfig", generationConfig);
            }

            if (node.has("tools")) {
                geminiMap.put("tools", convertOpenAiToolsToGemini(node.get("tools")));
            }

            return OBJECT_MAPPER.writeValueAsString(geminiMap);
        } catch (Exception e) {
            log.warn("转换 OpenAI 请求体为 Gemini 格式失败，使用原始 body: {}", e.getMessage());
            return openAiBody;
        }
    }

    private static List<Map<String, Object>> convertOpenAiToolsToGemini(JsonNode openAiTools) {
        List<Map<String, Object>> functionDeclarations = new ArrayList<>();
        for (JsonNode tool : openAiTools) {
            JsonNode function = tool.has("function") ? tool.get("function") : tool;
            Map<String, Object> declaration = new LinkedHashMap<>();
            declaration.put("name", function.path("name").asText(""));
            if (function.has("description")) declaration.put("description", function.get("description").asText());
            JsonNode params = function.get("parameters");
            if (params != null && params.isObject()) {
                declaration.put("parameters", OBJECT_MAPPER.convertValue(params, Map.class));
            } else {
                Map<String, Object> defaultSchema = new LinkedHashMap<>();
                defaultSchema.put("type", "object");
                defaultSchema.put("properties", Map.of());
                declaration.put("parameters", defaultSchema);
            }
            functionDeclarations.add(declaration);
        }
        Map<String, Object> toolGroup = new LinkedHashMap<>();
        toolGroup.put("functionDeclarations", functionDeclarations);
        return List.of(toolGroup);
    }

    // ==================== Gemini → OpenAI 响应转换 ====================

    /**
     * 将 Gemini 格式的响应转换为 OpenAI 格式
     * 用于 Gemini 渠道返回后，转换为 OpenAI 格式响应给客户端
     */
    public static String convertGeminiToOpenAiResponse(String geminiResponse) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(geminiResponse);
            Map<String, Object> openAiMap = new LinkedHashMap<>();

            openAiMap.put("id", "chatcmpl-" + System.currentTimeMillis());
            openAiMap.put("object", "chat.completion");

            JsonNode candidates = node.get("candidates");
            JsonNode candidate = candidates != null && candidates.isArray() && candidates.size() > 0
                    ? candidates.get(0) : null;

            String content = "";
            List<Map<String, Object>> toolCalls = new ArrayList<>();
            String finishReason = "stop";

            if (candidate != null) {
                JsonNode contentNode = candidate.get("content");
                if (contentNode != null && contentNode.has("parts")) {
                    StringBuilder textBuf = new StringBuilder();
                    int tcIdx = 0;
                    for (JsonNode part : contentNode.get("parts")) {
                        if (part.has("text")) {
                            textBuf.append(part.get("text").asText());
                        } else if (part.has("functionCall")) {
                            JsonNode fc = part.get("functionCall");
                            Map<String, Object> tc = new LinkedHashMap<>();
                            tc.put("id", "call_" + fc.path("name").asText("unknown") + "_" + tcIdx);
                            tc.put("type", "function");
                            Map<String, Object> function = new LinkedHashMap<>();
                            function.put("name", fc.path("name").asText(""));
                            JsonNode args = fc.get("args");
                            try {
                                function.put("arguments", args != null ? OBJECT_MAPPER.writeValueAsString(args) : "{}");
                            } catch (Exception e) {
                                function.put("arguments", "{}");
                            }
                            tc.put("function", function);
                            toolCalls.add(tc);
                            tcIdx++;
                        }
                    }
                    content = textBuf.toString();
                }
                String geminiFinish = candidate.path("finishReason").asText("STOP");
                finishReason = mapGeminiFinishReasonToOpenAi(geminiFinish);
            }

            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "assistant");
            message.put("content", content);
            if (!toolCalls.isEmpty()) {
                message.put("tool_calls", toolCalls);
                finishReason = "tool_calls";
            }

            List<Map<String, Object>> choices = new ArrayList<>();
            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("message", message);
            choice.put("finish_reason", finishReason);
            choices.add(choice);
            openAiMap.put("choices", choices);

            if (node.has("usageMetadata")) {
                JsonNode usageMeta = node.get("usageMetadata");
                Map<String, Object> usage = new LinkedHashMap<>();
                usage.put("prompt_tokens", usageMeta.path("promptTokenCount").asInt(0));
                usage.put("completion_tokens", usageMeta.path("candidatesTokenCount").asInt(0));
                usage.put("total_tokens", usageMeta.path("totalTokenCount").asInt(0));
                openAiMap.put("usage", usage);
            }

            if (node.has("modelVersion")) {
                openAiMap.put("model", node.get("modelVersion").asText());
            }

            return OBJECT_MAPPER.writeValueAsString(openAiMap);
        } catch (Exception e) {
            log.warn("转换 Gemini 响应为 OpenAI 格式失败，返回原始响应: {}", e.getMessage());
            return geminiResponse;
        }
    }

    private static String mapGeminiFinishReasonToOpenAi(String finishReason) {
        if (finishReason == null) return "stop";
        return switch (finishReason) {
            case "STOP" -> "stop";
            case "MAX_TOKENS" -> "length";
            case "SAFETY", "RECITATION" -> "content_filter";
            default -> "stop";
        };
    }

    // ==================== Gemini → OpenAI 请求转换 ====================

    /**
     * 将 Gemini 格式的请求体转换为 OpenAI 格式
     * Gemini: {model, contents[{role, parts[{text}]}], systemInstruction, generationConfig}
     * OpenAI: {model, messages[{role, content}], ...}
     */
    public static String convertGeminiToOpenAiBody(String geminiBody) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(geminiBody);
            Map<String, Object> openAiMap = new LinkedHashMap<>();

            if (node.has("model")) {
                openAiMap.put("model", node.get("model").asText());
            }

            List<Map<String, Object>> messages = new ArrayList<>();

            if (node.has("systemInstruction")) {
                messages.add(buildSystemMessage(node.get("systemInstruction")));
            }

            if (node.has("contents")) {
                for (JsonNode content : node.get("contents")) {
                    String role = content.path("role").asText("user");
                    if ("model".equals(role)) role = "assistant";
                    JsonNode parts = content.get("parts");
                    if (parts == null) continue;

                    StringBuilder textParts = new StringBuilder();
                    List<Map<String, Object>> imageParts = new ArrayList<>();
                    List<Map<String, Object>> toolCalls = new ArrayList<>();

                    for (JsonNode part : parts) {
                        if (part.has("text")) {
                            textParts.append(part.get("text").asText());
                        } else if (part.has("inlineData") || part.has("inline_data")) {
                            JsonNode inline = part.has("inlineData") ? part.get("inlineData") : part.get("inline_data");
                            String mimeType = inline.path("mimeType").asText(
                                    inline.path("mime_type").asText("image/png"));
                            String data = inline.path("data").asText("");
                            imageParts.add(Map.of("type", "image_url",
                                    "image_url", Map.of("url", "data:" + mimeType + ";base64," + data)));
                        } else if (part.has("functionResponse") || part.has("function_response")) {
                            JsonNode fnResp = part.has("functionResponse") ? part.get("functionResponse") : part.get("function_response");
                            Map<String, Object> toolMsg = new LinkedHashMap<>();
                            toolMsg.put("role", "tool");
                            toolMsg.put("tool_call_id", fnResp.path("id").asText(fnResp.path("name").asText("")));
                            JsonNode respContent = fnResp.get("response");
                            toolMsg.put("content", respContent != null ? respContent.toString() : "{}");
                            messages.add(toolMsg);
                        } else if (part.has("functionCall") || part.has("function_call")) {
                            JsonNode fnCall = part.has("functionCall") ? part.get("functionCall") : part.get("function_call");
                            Map<String, Object> toolCall = new LinkedHashMap<>();
                            toolCall.put("id", "call_" + fnCall.path("name").asText("unknown"));
                            toolCall.put("type", "function");
                            Map<String, Object> function = new LinkedHashMap<>();
                            function.put("name", fnCall.path("name").asText(""));
                            JsonNode args = fnCall.get("args");
                            try {
                                function.put("arguments", args != null ? OBJECT_MAPPER.writeValueAsString(args) : "{}");
                            } catch (Exception e) {
                                function.put("arguments", "{}");
                            }
                            toolCall.put("function", function);
                            toolCalls.add(toolCall);
                        }
                    }

                    if (!toolCalls.isEmpty()) {
                        Map<String, Object> msg = new LinkedHashMap<>();
                        msg.put("role", "assistant");
                        msg.put("content", textParts.length() > 0 ? textParts.toString() : null);
                        msg.put("tool_calls", toolCalls);
                        messages.add(msg);
                    } else if (!imageParts.isEmpty()) {
                        List<Map<String, Object>> contentArray = new ArrayList<>();
                        if (textParts.length() > 0) {
                            contentArray.add(Map.of("type", "text", "text", textParts.toString()));
                        }
                        contentArray.addAll(imageParts);
                        messages.add(Map.of("role", role, "content", contentArray));
                    } else {
                        messages.add(Map.of("role", role, "content", textParts.toString()));
                    }
                }
            }
            openAiMap.put("messages", messages);

            if (node.has("generationConfig")) {
                JsonNode gc = node.get("generationConfig");
                if (gc.has("maxOutputTokens")) openAiMap.put("max_tokens", gc.get("maxOutputTokens").asInt());
                if (gc.has("temperature")) openAiMap.put("temperature", gc.get("temperature").asDouble());
                if (gc.has("topP")) openAiMap.put("top_p", gc.get("topP").asDouble());
                if (gc.has("stopSequences")) {
                    List<String> stop = new ArrayList<>();
                    for (JsonNode s : gc.get("stopSequences")) stop.add(s.asText());
                    openAiMap.put("stop", stop);
                }
            }

            if (node.has("tools")) {
                openAiMap.put("tools", convertGeminiToolsToOpenAi(node.get("tools")));
            }
            if (node.has("toolConfig")) {
                openAiMap.put("tool_choice", convertGeminiToolChoiceToOpenAi(node.get("toolConfig")));
            }

            return OBJECT_MAPPER.writeValueAsString(openAiMap);
        } catch (Exception e) {
            log.warn("转换 Gemini 请求体为 OpenAI 格式失败，使用原始 body: {}", e.getMessage());
            return geminiBody;
        }
    }

    private static List<Map<String, Object>> convertGeminiToolsToOpenAi(JsonNode geminiTools) {
        List<Map<String, Object>> openAiTools = new ArrayList<>();
        for (JsonNode toolGroup : geminiTools) {
            JsonNode functionDeclarations = toolGroup.has("functionDeclarations")
                    ? toolGroup.get("functionDeclarations")
                    : toolGroup.has("function_declarations") ? toolGroup.get("function_declarations") : null;
            if (functionDeclarations == null || !functionDeclarations.isArray()) continue;
            for (JsonNode fn : functionDeclarations) {
                Map<String, Object> openAiTool = new LinkedHashMap<>();
                openAiTool.put("type", "function");
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", fn.path("name").asText(""));
                if (fn.has("description")) function.put("description", fn.get("description").asText());
                JsonNode params = fn.get("parameters");
                if (params != null && params.isObject()) {
                    function.put("parameters", OBJECT_MAPPER.convertValue(params, Map.class));
                } else {
                    Map<String, Object> defaultSchema = new LinkedHashMap<>();
                    defaultSchema.put("type", "object");
                    defaultSchema.put("properties", Map.of());
                    function.put("parameters", defaultSchema);
                }
                openAiTool.put("function", function);
                openAiTools.add(openAiTool);
            }
        }
        return openAiTools;
    }

    private static Object convertGeminiToolChoiceToOpenAi(JsonNode toolConfig) {
        JsonNode fcConfig = toolConfig.has("functionCallingConfig")
                ? toolConfig.get("functionCallingConfig")
                : toolConfig.has("function_calling_config") ? toolConfig.get("function_calling_config") : null;
        if (fcConfig == null) return "auto";
        String mode = fcConfig.path("mode").asText("AUTO");
        return switch (mode.toUpperCase()) {
            case "ANY", "REQUIRED" -> "required";
            case "NONE" -> "none";
            default -> "auto";
        };
    }

    // ==================== OpenAI → Gemini 响应转换 ====================

    /**
     * 将 OpenAI 格式的响应转换为 Gemini 格式
     */
    public static String convertOpenAiToGeminiResponse(String openAiResponse) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(openAiResponse);
            Map<String, Object> geminiMap = new LinkedHashMap<>();

            List<Map<String, Object>> candidates = new ArrayList<>();
            Map<String, Object> candidate = new LinkedHashMap<>();

            JsonNode message = node.path("choices").path(0).path("message");
            String content = message.path("content").asText("");
            String finishReason = node.path("choices").path(0).path("finish_reason").asText("stop");

            List<Map<String, Object>> parts = new ArrayList<>();

            if (content != null && !content.isEmpty()) {
                parts.add(Map.of("text", content));
            }

            JsonNode toolCalls = message.get("tool_calls");
            if (toolCalls != null && toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    Map<String, Object> functionCall = new LinkedHashMap<>();
                    functionCall.put("name", tc.path("function").path("name").asText(""));
                    String args = tc.path("function").path("arguments").asText("{}");
                    try {
                        functionCall.put("args", OBJECT_MAPPER.readValue(args, Map.class));
                    } catch (Exception e) {
                        functionCall.put("args", Map.of());
                    }
                    parts.add(Map.of("functionCall", functionCall));
                }
            }

            if (parts.isEmpty()) {
                parts.add(Map.of("text", ""));
            }

            Map<String, Object> contentObj = new LinkedHashMap<>();
            contentObj.put("role", "model");
            contentObj.put("parts", parts);
            candidate.put("content", contentObj);
            candidate.put("finishReason", mapOpenAiFinishReasonToGemini(finishReason));
            candidate.put("index", 0);
            candidates.add(candidate);

            geminiMap.put("candidates", candidates);

            if (node.has("usage")) {
                JsonNode usage = node.get("usage");
                Map<String, Object> usageMetadata = new LinkedHashMap<>();
                usageMetadata.put("promptTokenCount", usage.path("prompt_tokens").asInt(0));
                usageMetadata.put("candidatesTokenCount", usage.path("completion_tokens").asInt(0));
                usageMetadata.put("totalTokenCount", usage.path("total_tokens").asInt(0));
                geminiMap.put("usageMetadata", usageMetadata);
            }

            if (node.has("model")) {
                geminiMap.put("modelVersion", node.get("model").asText());
            }

            return OBJECT_MAPPER.writeValueAsString(geminiMap);
        } catch (Exception e) {
            log.warn("转换 OpenAI 响应为 Gemini 格式失败，返回原始响应: {}", e.getMessage());
            return openAiResponse;
        }
    }

    private static String mapOpenAiFinishReasonToGemini(String finishReason) {
        if (finishReason == null) return "STOP";
        return switch (finishReason) {
            case "stop" -> "STOP";
            case "tool_calls" -> "STOP";
            case "length" -> "MAX_TOKENS";
            case "content_filter" -> "SAFETY";
            default -> "FINISH_REASON_UNSPECIFIED";
        };
    }

    // ==================== Claude → Gemini 请求转换 ====================

    /**
     * 将 Claude 格式的请求体转换为 Gemini 格式
     * 用于 Claude 端点收到请求后，转发给 Gemini 渠道
     */
    public static String convertClaudeToGeminiRequest(String claudeBody) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(claudeBody);
            Map<String, Object> geminiMap = new LinkedHashMap<>();

            if (node.has("model")) {
                geminiMap.put("model", node.get("model").asText());
            }

            if (node.has("system")) {
                JsonNode sysNode = node.get("system");
                Map<String, Object> systemInstruction = new LinkedHashMap<>();
                List<Map<String, Object>> sysParts = new ArrayList<>();
                if (sysNode.isTextual()) {
                    sysParts.add(Map.of("text", sysNode.asText()));
                } else if (sysNode.isArray()) {
                    for (JsonNode block : sysNode) {
                        if (block.has("text")) sysParts.add(Map.of("text", block.get("text").asText()));
                    }
                }
                systemInstruction.put("parts", sysParts);
                geminiMap.put("systemInstruction", systemInstruction);
            }

            List<Map<String, Object>> contents = new ArrayList<>();
            if (node.has("messages")) {
                for (JsonNode msg : node.get("messages")) {
                    String role = msg.path("role").asText("");
                    if ("system".equals(role)) continue;
                    String geminiRole = "assistant".equals(role) ? "model" : "user";
                    JsonNode contentNode = msg.get("content");

                    Map<String, Object> content = new LinkedHashMap<>();
                    content.put("role", geminiRole);
                    List<Map<String, Object>> parts = new ArrayList<>();

                    if ("tool".equals(role)) {
                        Map<String, Object> functionResponse = new LinkedHashMap<>();
                        functionResponse.put("name", msg.path("name").asText(""));
                        String toolContent = contentNode != null ?
                                (contentNode.isTextual() ? contentNode.asText() : contentNode.toString()) : "{}";
                        try {
                            functionResponse.put("response", OBJECT_MAPPER.readValue(toolContent, Map.class));
                        } catch (Exception e) {
                            functionResponse.put("response", Map.of("result", toolContent));
                        }
                        parts.add(Map.of("functionResponse", functionResponse));
                    } else if (contentNode != null) {
                        if (contentNode.isTextual()) {
                            parts.add(Map.of("text", contentNode.asText()));
                        } else if (contentNode.isArray()) {
                            StringBuilder textBuf = new StringBuilder();
                            for (JsonNode block : contentNode) {
                                String type = block.path("type").asText("");
                                if ("text".equals(type) && block.has("text")) {
                                    textBuf.append(block.get("text").asText());
                                } else if ("image".equals(type) && block.has("source")) {
                                    JsonNode source = block.get("source");
                                    String sourceType = source.path("type").asText("");
                                    if ("base64".equals(sourceType)) {
                                        String mimeType = source.path("media_type").asText("image/png");
                                        String data = source.path("data").asText("");
                                        Map<String, Object> inlineData = new LinkedHashMap<>();
                                        inlineData.put("mimeType", mimeType);
                                        inlineData.put("data", data);
                                        parts.add(Map.of("inlineData", inlineData));
                                    } else if ("url".equals(sourceType)) {
                                        Map<String, Object> fileData = new LinkedHashMap<>();
                                        fileData.put("mimeType", "image/png");
                                        fileData.put("fileUri", source.path("url").asText(""));
                                        parts.add(Map.of("fileData", fileData));
                                    }
                                } else if ("tool_use".equals(type)) {
                                    Map<String, Object> functionCall = new LinkedHashMap<>();
                                    functionCall.put("name", block.path("name").asText(""));
                                    JsonNode input = block.get("input");
                                    if (input != null && input.isObject()) {
                                        functionCall.put("args", OBJECT_MAPPER.convertValue(input, Map.class));
                                    } else {
                                        functionCall.put("args", Map.of());
                                    }
                                    parts.add(Map.of("functionCall", functionCall));
                                } else if ("tool_result".equals(type)) {
                                    Map<String, Object> functionResponse = new LinkedHashMap<>();
                                    functionResponse.put("name", block.path("name").asText(""));
                                    JsonNode resultContent = block.get("content");
                                    String respText = resultContent != null ?
                                            (resultContent.isTextual() ? resultContent.asText() : resultContent.toString()) : "{}";
                                    try {
                                        functionResponse.put("response", OBJECT_MAPPER.readValue(respText, Map.class));
                                    } catch (Exception e) {
                                        functionResponse.put("response", Map.of("result", respText));
                                    }
                                    parts.add(Map.of("functionResponse", functionResponse));
                                }
                            }
                            if (textBuf.length() > 0) {
                                parts.add(Map.of("text", textBuf.toString()));
                            }
                        }
                    }

                    if (parts.isEmpty()) {
                        parts.add(Map.of("text", ""));
                    }
                    content.put("parts", parts);
                    contents.add(content);
                }
            }
            geminiMap.put("contents", contents);

            Map<String, Object> generationConfig = new LinkedHashMap<>();
            if (node.has("max_tokens")) generationConfig.put("maxOutputTokens", node.get("max_tokens").asInt());
            if (node.has("temperature")) generationConfig.put("temperature", node.get("temperature").asDouble());
            if (node.has("top_p")) generationConfig.put("topP", node.get("top_p").asDouble());
            if (node.has("stop_sequences")) {
                List<String> stopSequences = new ArrayList<>();
                for (JsonNode s : node.get("stop_sequences")) stopSequences.add(s.asText());
                generationConfig.put("stopSequences", stopSequences);
            }
            if (!generationConfig.isEmpty()) {
                geminiMap.put("generationConfig", generationConfig);
            }

            if (node.has("tools")) {
                geminiMap.put("tools", convertClaudeToolsToGemini(node.get("tools")));
            }

            return OBJECT_MAPPER.writeValueAsString(geminiMap);
        } catch (Exception e) {
            log.warn("转换 Claude 请求体为 Gemini 格式失败，使用原始 body: {}", e.getMessage());
            return claudeBody;
        }
    }

    private static List<Map<String, Object>> convertClaudeToolsToGemini(JsonNode claudeTools) {
        List<Map<String, Object>> functionDeclarations = new ArrayList<>();
        for (JsonNode tool : claudeTools) {
            Map<String, Object> declaration = new LinkedHashMap<>();
            declaration.put("name", tool.path("name").asText(""));
            if (tool.has("description")) declaration.put("description", tool.get("description").asText());
            JsonNode schema = tool.get("input_schema");
            if (schema != null && schema.isObject()) {
                declaration.put("parameters", OBJECT_MAPPER.convertValue(schema, Map.class));
            } else {
                Map<String, Object> defaultSchema = new LinkedHashMap<>();
                defaultSchema.put("type", "object");
                defaultSchema.put("properties", Map.of());
                declaration.put("parameters", defaultSchema);
            }
            functionDeclarations.add(declaration);
        }
        Map<String, Object> toolGroup = new LinkedHashMap<>();
        toolGroup.put("functionDeclarations", functionDeclarations);
        return List.of(toolGroup);
    }

    // ==================== Gemini → Claude 响应转换 ====================

    /**
     * 将 Gemini 格式的响应转换为 Claude 格式
     * 用于 Gemini 渠道返回后，转换为 Claude 格式响应给客户端
     */
    public static String convertGeminiToClaudeResponse(String geminiResponse) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(geminiResponse);
            Map<String, Object> claudeMap = new LinkedHashMap<>();

            claudeMap.put("id", "msg_" + System.currentTimeMillis());
            claudeMap.put("type", "message");
            claudeMap.put("role", "assistant");

            List<Map<String, Object>> claudeContent = new ArrayList<>();
            JsonNode candidates = node.get("candidates");
            JsonNode candidate = candidates != null && candidates.isArray() && candidates.size() > 0
                    ? candidates.get(0) : null;
            String stopReason = "end_turn";

            if (candidate != null) {
                JsonNode contentNode = candidate.get("content");
                if (contentNode != null && contentNode.has("parts")) {
                    for (JsonNode part : contentNode.get("parts")) {
                        if (part.has("text")) {
                            claudeContent.add(Map.of("type", "text", "text", part.get("text").asText("")));
                        } else if (part.has("functionCall")) {
                            JsonNode fc = part.get("functionCall");
                            Map<String, Object> toolUse = new LinkedHashMap<>();
                            toolUse.put("type", "tool_use");
                            toolUse.put("id", "toolu_" + fc.path("name").asText("unknown"));
                            toolUse.put("name", fc.path("name").asText(""));
                            JsonNode args = fc.get("args");
                            if (args != null && args.isObject()) {
                                toolUse.put("input", OBJECT_MAPPER.convertValue(args, Map.class));
                            } else {
                                toolUse.put("input", Map.of());
                            }
                            claudeContent.add(toolUse);
                        }
                    }
                }
                String geminiFinish = candidate.path("finishReason").asText("STOP");
                stopReason = mapGeminiFinishReasonToClaude(geminiFinish);
            }

            if (claudeContent.isEmpty()) {
                claudeContent.add(Map.of("type", "text", "text", ""));
            }
            claudeMap.put("content", claudeContent);
            claudeMap.put("stop_reason", stopReason);
            claudeMap.put("stop_sequence", null);

            if (node.has("usageMetadata")) {
                JsonNode usageMeta = node.get("usageMetadata");
                Map<String, Object> claudeUsage = new LinkedHashMap<>();
                claudeUsage.put("input_tokens", usageMeta.path("promptTokenCount").asInt(0));
                claudeUsage.put("output_tokens", usageMeta.path("candidatesTokenCount").asInt(0));
                claudeMap.put("usage", claudeUsage);
            }

            if (node.has("modelVersion")) {
                claudeMap.put("model", node.get("modelVersion").asText());
            }

            return OBJECT_MAPPER.writeValueAsString(claudeMap);
        } catch (Exception e) {
            log.warn("转换 Gemini 响应为 Claude 格式失败，返回原始响应: {}", e.getMessage());
            return geminiResponse;
        }
    }

    private static String mapGeminiFinishReasonToClaude(String finishReason) {
        if (finishReason == null) return "end_turn";
        return switch (finishReason) {
            case "STOP" -> "end_turn";
            case "MAX_TOKENS" -> "max_tokens";
            case "SAFETY", "RECITATION" -> "end_turn";
            default -> "end_turn";
        };
    }

    // ==================== Gemini → Claude 请求转换 ====================

    /**
     * 将 Gemini 格式的请求体转换为 Claude 格式
     */
    public static String convertGeminiToClaudeBody(String geminiBody) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(geminiBody);
            Map<String, Object> claudeMap = new LinkedHashMap<>();

            if (node.has("model")) {
                claudeMap.put("model", node.get("model").asText());
            }

            if (node.has("systemInstruction")) {
                JsonNode sysNode = node.get("systemInstruction");
                claudeMap.put("system", extractTextFromGeminiParts(sysNode));
            }

            List<Map<String, Object>> messages = new ArrayList<>();
            if (node.has("contents")) {
                for (JsonNode content : node.get("contents")) {
                    String role = content.path("role").asText("user");
                    if ("model".equals(role)) role = "assistant";
                    JsonNode parts = content.get("parts");
                    if (parts == null) continue;

                    StringBuilder textParts = new StringBuilder();
                    List<Map<String, Object>> contentBlocks = new ArrayList<>();
                    List<Map<String, Object>> toolUseBlocks = new ArrayList<>();
                    List<Map<String, Object>> toolResultBlocks = new ArrayList<>();

                    for (JsonNode part : parts) {
                        if (part.has("text")) {
                            textParts.append(part.get("text").asText());
                        } else if (part.has("inlineData") || part.has("inline_data")) {
                            JsonNode inline = part.has("inlineData") ? part.get("inlineData") : part.get("inline_data");
                            String mimeType = inline.path("mimeType").asText(
                                    inline.path("mime_type").asText("image/png"));
                            String data = inline.path("data").asText("");
                            Map<String, Object> imageBlock = new LinkedHashMap<>();
                            imageBlock.put("type", "image");
                            imageBlock.put("source", Map.of(
                                    "type", "base64",
                                    "media_type", mimeType,
                                    "data", data));
                            contentBlocks.add(imageBlock);
                        } else if (part.has("functionCall") || part.has("function_call")) {
                            JsonNode fnCall = part.has("functionCall") ? part.get("functionCall") : part.get("function_call");
                            Map<String, Object> toolUse = new LinkedHashMap<>();
                            toolUse.put("type", "tool_use");
                            toolUse.put("id", "toolu_" + fnCall.path("name").asText("unknown"));
                            toolUse.put("name", fnCall.path("name").asText(""));
                            JsonNode args = fnCall.get("args");
                            if (args != null && args.isObject()) {
                                toolUse.put("input", OBJECT_MAPPER.convertValue(args, Map.class));
                            } else {
                                toolUse.put("input", Map.of());
                            }
                            toolUseBlocks.add(toolUse);
                        } else if (part.has("functionResponse") || part.has("function_response")) {
                            JsonNode fnResp = part.has("functionResponse") ? part.get("functionResponse") : part.get("function_response");
                            Map<String, Object> toolResult = new LinkedHashMap<>();
                            toolResult.put("type", "tool_result");
                            toolResult.put("tool_use_id", fnResp.path("id").asText(fnResp.path("name").asText("")));
                            JsonNode respContent = fnResp.get("response");
                            toolResult.put("content", respContent != null ? respContent.toString() : "{}");
                            toolResultBlocks.add(toolResult);
                        }
                    }

                    if (!toolResultBlocks.isEmpty()) {
                        Map<String, Object> msg = new LinkedHashMap<>();
                        msg.put("role", "user");
                        msg.put("content", toolResultBlocks);
                        messages.add(msg);
                    } else if (!toolUseBlocks.isEmpty()) {
                        List<Map<String, Object>> assistantContent = new ArrayList<>();
                        if (textParts.length() > 0) {
                            assistantContent.add(Map.of("type", "text", "text", textParts.toString()));
                        }
                        assistantContent.addAll(toolUseBlocks);
                        Map<String, Object> msg = new LinkedHashMap<>();
                        msg.put("role", "assistant");
                        msg.put("content", assistantContent);
                        messages.add(msg);
                    } else {
                        if (!contentBlocks.isEmpty()) {
                            if (textParts.length() > 0) {
                                contentBlocks.add(0, Map.of("type", "text", "text", textParts.toString()));
                            }
                            Map<String, Object> msg = new LinkedHashMap<>();
                            msg.put("role", role);
                            msg.put("content", contentBlocks);
                            messages.add(msg);
                        } else {
                            Map<String, Object> msg = new LinkedHashMap<>();
                            msg.put("role", role);
                            msg.put("content", textParts.toString());
                            messages.add(msg);
                        }
                    }
                }
            }
            claudeMap.put("messages", messages);

            if (node.has("generationConfig")) {
                JsonNode gc = node.get("generationConfig");
                if (gc.has("maxOutputTokens")) claudeMap.put("max_tokens", gc.get("maxOutputTokens").asInt());
                else claudeMap.put("max_tokens", 4096);
                if (gc.has("temperature")) claudeMap.put("temperature", gc.get("temperature").asDouble());
                if (gc.has("topP")) claudeMap.put("top_p", gc.get("topP").asDouble());
                if (gc.has("stopSequences")) {
                    List<String> stop = new ArrayList<>();
                    for (JsonNode s : gc.get("stopSequences")) stop.add(s.asText());
                    claudeMap.put("stop_sequences", stop);
                }
            } else {
                claudeMap.put("max_tokens", 4096);
            }

            if (node.has("tools")) {
                claudeMap.put("tools", convertGeminiToolsToClaude(node.get("tools")));
            }

            return OBJECT_MAPPER.writeValueAsString(claudeMap);
        } catch (Exception e) {
            log.warn("转换 Gemini 请求体为 Claude 格式失败，使用原始 body: {}", e.getMessage());
            return geminiBody;
        }
    }

    private static List<Map<String, Object>> convertGeminiToolsToClaude(JsonNode geminiTools) {
        List<Map<String, Object>> claudeTools = new ArrayList<>();
        for (JsonNode toolGroup : geminiTools) {
            JsonNode functionDeclarations = toolGroup.has("functionDeclarations")
                    ? toolGroup.get("functionDeclarations")
                    : toolGroup.has("function_declarations") ? toolGroup.get("function_declarations") : null;
            if (functionDeclarations == null || !functionDeclarations.isArray()) continue;
            for (JsonNode fn : functionDeclarations) {
                Map<String, Object> claudeTool = new LinkedHashMap<>();
                claudeTool.put("name", fn.path("name").asText(""));
                if (fn.has("description")) claudeTool.put("description", fn.get("description").asText());
                JsonNode params = fn.get("parameters");
                if (params != null && params.isObject()) {
                    claudeTool.put("input_schema", OBJECT_MAPPER.convertValue(params, Map.class));
                } else {
                    Map<String, Object> defaultSchema = new LinkedHashMap<>();
                    defaultSchema.put("type", "object");
                    defaultSchema.put("properties", Map.of());
                    claudeTool.put("input_schema", defaultSchema);
                }
                claudeTools.add(claudeTool);
            }
        }
        return claudeTools;
    }

    // ==================== Claude → Gemini 响应转换 ====================

    /**
     * 将 Claude 格式的响应转换为 Gemini 格式
     */
    public static String convertClaudeToGeminiResponse(String claudeResponse) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(claudeResponse);
            Map<String, Object> geminiMap = new LinkedHashMap<>();

            List<Map<String, Object>> candidates = new ArrayList<>();
            Map<String, Object> candidate = new LinkedHashMap<>();

            List<Map<String, Object>> parts = new ArrayList<>();
            JsonNode contentArray = node.get("content");
            if (contentArray != null && contentArray.isArray()) {
                for (JsonNode block : contentArray) {
                    String type = block.path("type").asText("");
                    if ("text".equals(type)) {
                        parts.add(Map.of("text", block.path("text").asText("")));
                    } else if ("tool_use".equals(type)) {
                        Map<String, Object> functionCall = new LinkedHashMap<>();
                        functionCall.put("name", block.path("name").asText(""));
                        JsonNode input = block.get("input");
                        if (input != null && input.isObject()) {
                            functionCall.put("args", OBJECT_MAPPER.convertValue(input, Map.class));
                        } else {
                            functionCall.put("args", Map.of());
                        }
                        parts.add(Map.of("functionCall", functionCall));
                    }
                }
            }

            if (parts.isEmpty()) {
                parts.add(Map.of("text", ""));
            }

            Map<String, Object> contentObj = new LinkedHashMap<>();
            contentObj.put("role", "model");
            contentObj.put("parts", parts);
            candidate.put("content", contentObj);
            candidate.put("finishReason", mapClaudeStopReasonToGemini(node.path("stop_reason").asText("end_turn")));
            candidate.put("index", 0);
            candidates.add(candidate);
            geminiMap.put("candidates", candidates);

            if (node.has("usage")) {
                JsonNode usage = node.get("usage");
                Map<String, Object> usageMetadata = new LinkedHashMap<>();
                usageMetadata.put("promptTokenCount", usage.path("input_tokens").asInt(0));
                usageMetadata.put("candidatesTokenCount", usage.path("output_tokens").asInt(0));
                usageMetadata.put("totalTokenCount",
                        usage.path("input_tokens").asInt(0) + usage.path("output_tokens").asInt(0));
                geminiMap.put("usageMetadata", usageMetadata);
            }

            if (node.has("model")) {
                geminiMap.put("modelVersion", node.get("model").asText());
            }

            return OBJECT_MAPPER.writeValueAsString(geminiMap);
        } catch (Exception e) {
            log.warn("转换 Claude 响应为 Gemini 格式失败，返回原始响应: {}", e.getMessage());
            return claudeResponse;
        }
    }

    private static String mapClaudeStopReasonToGemini(String stopReason) {
        if (stopReason == null) return "STOP";
        return switch (stopReason) {
            case "end_turn" -> "STOP";
            case "tool_use" -> "STOP";
            case "max_tokens" -> "MAX_TOKENS";
            default -> "FINISH_REASON_UNSPECIFIED";
        };
    }

    // ==================== Gemini 流式 chunk 转换 ====================

    /**
     * 将 OpenAI SSE chunk 转换为 Gemini 流式 chunk JSON 字符串
     */
    public static String convertOpenAiStreamChunkToGemini(String openAiChunk) {
        try {
            JsonNode json = OBJECT_MAPPER.readTree(openAiChunk);
            Map<String, Object> geminiChunk = new LinkedHashMap<>();

            List<Map<String, Object>> candidates = new ArrayList<>();
            Map<String, Object> candidate = new LinkedHashMap<>();

            JsonNode delta = json.path("choices").path(0).path("delta");
            String content = delta.path("content").asText("");
            String finishReason = json.path("choices").path(0).path("finish_reason").asText("");

            List<Map<String, Object>> parts = new ArrayList<>();
            if (!content.isEmpty()) {
                parts.add(Map.of("text", content));
            }

            JsonNode toolCalls = delta.get("tool_calls");
            if (toolCalls != null && toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    if (tc.has("function") && tc.get("function").has("name")) {
                        Map<String, Object> functionCall = new LinkedHashMap<>();
                        functionCall.put("name", tc.get("function").path("name").asText(""));
                        String args = tc.get("function").path("arguments").asText("{}");
                        try {
                            functionCall.put("args", OBJECT_MAPPER.readValue(args, Map.class));
                        } catch (Exception e) {
                            functionCall.put("args", Map.of());
                        }
                        parts.add(Map.of("functionCall", functionCall));
                    }
                }
            }

            if (!parts.isEmpty()) {
                Map<String, Object> contentObj = new LinkedHashMap<>();
                contentObj.put("role", "model");
                contentObj.put("parts", parts);
                candidate.put("content", contentObj);
            }
            if (!finishReason.isEmpty()) {
                candidate.put("finishReason", mapOpenAiFinishReasonToGemini(finishReason));
            }
            candidate.put("index", 0);
            candidates.add(candidate);
            geminiChunk.put("candidates", candidates);

            if (json.has("usage")) {
                JsonNode usage = json.get("usage");
                Map<String, Object> usageMetadata = new LinkedHashMap<>();
                usageMetadata.put("promptTokenCount", usage.path("prompt_tokens").asInt(0));
                usageMetadata.put("candidatesTokenCount", usage.path("completion_tokens").asInt(0));
                usageMetadata.put("totalTokenCount", usage.path("total_tokens").asInt(0));
                geminiChunk.put("usageMetadata", usageMetadata);
            }

            return OBJECT_MAPPER.writeValueAsString(geminiChunk);
        } catch (Exception e) {
            log.warn("转换 OpenAI SSE chunk 为 Gemini 格式失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将 Claude SSE event 转换为 Gemini 流式 chunk JSON 字符串
     * 返回 null 表示跳过该事件（如 message_start 等控制事件）
     */
    public static String convertClaudeStreamEventToGemini(String claudeEvent) {
        try {
            JsonNode json = OBJECT_MAPPER.readTree(claudeEvent);
            String type = json.path("type").asText("");

            if ("content_block_delta".equals(type)) {
                JsonNode delta = json.get("delta");
                String deltaType = delta.path("type").asText("");
                Map<String, Object> geminiChunk = new LinkedHashMap<>();
                List<Map<String, Object>> candidates = new ArrayList<>();
                Map<String, Object> candidate = new LinkedHashMap<>();

                if ("text_delta".equals(deltaType)) {
                    String text = delta.path("text").asText("");
                    if (!text.isEmpty()) {
                        Map<String, Object> contentObj = new LinkedHashMap<>();
                        contentObj.put("role", "model");
                        contentObj.put("parts", List.of(Map.of("text", text)));
                        candidate.put("content", contentObj);
                    }
                } else if ("input_json_delta".equals(deltaType)) {
                    String partialJson = delta.path("partial_json").asText("");
                    Map<String, Object> functionCall = new LinkedHashMap<>();
                    functionCall.put("args", Map.of("_partial", partialJson));
                    Map<String, Object> contentObj = new LinkedHashMap<>();
                    contentObj.put("role", "model");
                    contentObj.put("parts", List.of(Map.of("functionCall", functionCall)));
                    candidate.put("content", contentObj);
                }

                candidate.put("index", json.path("index").asInt(0));
                candidates.add(candidate);
                geminiChunk.put("candidates", candidates);
                return OBJECT_MAPPER.writeValueAsString(geminiChunk);
            }

            if ("message_delta".equals(type)) {
                JsonNode deltaObj = json.get("delta");
                String stopReason = deltaObj.path("stop_reason").asText("");
                Map<String, Object> geminiChunk = new LinkedHashMap<>();
                List<Map<String, Object>> candidates = new ArrayList<>();
                Map<String, Object> candidate = new LinkedHashMap<>();
                if (!stopReason.isEmpty()) {
                    candidate.put("finishReason", mapClaudeStopReasonToGemini(stopReason));
                }
                candidate.put("index", 0);
                candidates.add(candidate);
                geminiChunk.put("candidates", candidates);

                JsonNode usage = json.get("usage");
                if (usage != null) {
                    Map<String, Object> usageMetadata = new LinkedHashMap<>();
                    usageMetadata.put("candidatesTokenCount", usage.path("output_tokens").asInt(0));
                    geminiChunk.put("usageMetadata", usageMetadata);
                }
                return OBJECT_MAPPER.writeValueAsString(geminiChunk);
            }

            return null;
        } catch (Exception e) {
            log.warn("转换 Claude SSE event 为 Gemini 格式失败: {}", e.getMessage());
            return null;
        }
    }

    private static String extractTextFromGeminiParts(JsonNode instructionNode) {
        if (instructionNode == null || instructionNode.isNull()) return "";
        if (instructionNode.isTextual()) return instructionNode.asText();
        if (instructionNode.has("parts")) {
            JsonNode parts = instructionNode.get("parts");
            if (parts.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode part : parts) {
                    if (part.has("text")) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(part.get("text").asText());
                    }
                }
                return sb.toString();
            }
        }
        if (instructionNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : instructionNode) {
                if (part.has("text")) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(part.get("text").asText());
                }
            }
            return sb.toString();
        }
        return instructionNode.toString();
    }
}
