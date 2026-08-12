package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.ProtocolConverter;
import com.aiconnecting.entity.Channel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

/** Boundary conversion between public protocols and a selected channel's native protocol. */
@Component
@RequiredArgsConstructor
public class RelayProtocolAdapter {

    private final ObjectMapper objectMapper;

    public UnifiedRelayRequest adaptRequest(RelayProtocol protocol, String path, String body, String pathModel) {
        try {
            JsonNode json = objectMapper.readTree(body);
            if (!(json instanceof ObjectNode object)) {
                throw new BusinessException(400, "请求体必须是 JSON 对象", "Request body must be a JSON object");
            }
            String model = pathModel != null && !pathModel.isBlank()
                    ? pathModel : object.path("model").asText("");
            boolean stream = protocol == RelayProtocol.GEMINI
                    ? path != null && path.contains(":streamGenerateContent")
                    : object.path("stream").asBoolean(false);
            Integer maxTokens = null;
            if (object.has("max_tokens")) maxTokens = object.get("max_tokens").asInt();
            else if (object.has("maxOutputTokens")) maxTokens = object.get("maxOutputTokens").asInt();
            else if (object.path("generationConfig").has("maxOutputTokens")) {
                maxTokens = object.path("generationConfig").get("maxOutputTokens").asInt();
            }
            Double temperature = null;
            if (object.has("temperature")) temperature = object.get("temperature").asDouble();
            else if (object.path("generationConfig").has("temperature")) {
                temperature = object.path("generationConfig").get("temperature").asDouble();
            }
            JsonNode content = protocol == RelayProtocol.GEMINI ? object.get("contents") : object.get("messages");
            return new UnifiedRelayRequest(protocol, path, model, stream, maxTokens, temperature,
                    content, body);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "请求 JSON 格式无效", "Invalid request JSON", e);
        }
    }

    public RelayProtocol channelProtocol(Channel channel) {
        if (RelayServiceUtils.isClaudeTypeChannel(channel)) return RelayProtocol.CLAUDE;
        if (RelayServiceUtils.isGeminiTypeChannel(channel)) return RelayProtocol.GEMINI;
        return RelayProtocol.OPENAI;
    }

    public String upstreamPath(RelayProtocol protocol, String memberModel, boolean stream) {
        return switch (protocol) {
            case OPENAI -> "/v1/chat/completions";
            case CLAUDE -> "/v1/messages";
            case GEMINI -> "/v1/models/" + memberModel
                    + (stream ? ":streamGenerateContent?alt=sse" : ":generateContent");
        };
    }

    public String toUpstreamBody(UnifiedRelayRequest request, String memberModel,
                                 RelayProtocol upstreamProtocol) {
        String rewritten = rewriteModel(request.rawBody(), memberModel);
        if (request.protocol() == upstreamProtocol) return rewritten;
        return switch (request.protocol()) {
            case OPENAI -> switch (upstreamProtocol) {
                case OPENAI -> rewritten;
                case GEMINI -> ProtocolConverter.convertOpenAiToGeminiRequest(rewritten);
                case CLAUDE -> ProtocolConverter.convertGeminiToClaudeBody(
                        ProtocolConverter.convertOpenAiToGeminiRequest(rewritten));
            };
            case CLAUDE -> switch (upstreamProtocol) {
                case OPENAI -> ProtocolConverter.convertClaudeToOpenAiBody(rewritten);
                case CLAUDE -> rewritten;
                case GEMINI -> ProtocolConverter.convertClaudeToGeminiRequest(rewritten);
            };
            case GEMINI -> switch (upstreamProtocol) {
                case OPENAI -> ProtocolConverter.convertGeminiToOpenAiBody(rewritten);
                case CLAUDE -> ProtocolConverter.convertGeminiToClaudeBody(rewritten);
                case GEMINI -> rewritten;
            };
        };
    }

    public String fromUpstreamResponse(String response, RelayProtocol upstreamProtocol,
                                       RelayProtocol clientProtocol) {
        if (upstreamProtocol == clientProtocol) return response;
        return switch (clientProtocol) {
            case OPENAI -> switch (upstreamProtocol) {
                case OPENAI -> response;
                case GEMINI -> ProtocolConverter.convertGeminiToOpenAiResponse(response);
                case CLAUDE -> ProtocolConverter.convertGeminiToOpenAiResponse(
                        ProtocolConverter.convertClaudeToGeminiResponse(response));
            };
            case CLAUDE -> switch (upstreamProtocol) {
                case OPENAI -> ProtocolConverter.convertOpenAiToClaudeResponse(response);
                case CLAUDE -> response;
                case GEMINI -> ProtocolConverter.convertGeminiToClaudeResponse(response);
            };
            case GEMINI -> switch (upstreamProtocol) {
                case OPENAI -> ProtocolConverter.convertOpenAiToGeminiResponse(response);
                case CLAUDE -> ProtocolConverter.convertClaudeToGeminiResponse(response);
                case GEMINI -> response;
            };
        };
    }

    public RelayServiceUtils.UsageInfo parseUsage(RelayProtocol protocol, String response) {
        return switch (protocol) {
            case OPENAI -> parseOpenAiUsage(response);
            case CLAUDE -> RelayServiceUtils.parseClaudeUsage(objectMapper, response);
            case GEMINI -> RelayServiceUtils.parseGeminiUsage(objectMapper, response);
        };
    }

    private RelayServiceUtils.UsageInfo parseOpenAiUsage(String response) {
        try {
            JsonNode usage = objectMapper.readTree(response).path("usage");
            int prompt = usage.path("prompt_tokens").asInt(0);
            int completion = usage.path("completion_tokens").asInt(0);
            int total = usage.path("total_tokens").asInt(prompt + completion);
            int cached = usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);
            int creation = usage.path("cache_creation_input_tokens").asInt(0);
            int read = usage.path("cache_read_input_tokens").asInt(cached);
            return new RelayServiceUtils.UsageInfo(prompt, completion, total, cached, creation, read);
        } catch (Exception e) {
            return RelayServiceUtils.UsageInfo.ZERO;
        }
    }

    public void writeError(RelayProtocol protocol, HttpServletResponse response, int status,
                           String message, boolean upstream) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), errorEnvelope(protocol, status, message, upstream));
    }

    /** Builds the canonical public error envelope used by both MVC and direct relay responses. */
    public ObjectNode errorEnvelope(RelayProtocol protocol, int status, String message, boolean upstream) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode error = root.putObject("error");
        switch (protocol) {
            case OPENAI -> {
                error.put("message", message);
                error.put("type", status >= 500 ? "api_error" : "invalid_request_error");
                error.put("code", status);
            }
            case CLAUDE -> {
                root.put("type", "error");
                error.put("type", "api_error");
                error.put("message", message);
            }
            case GEMINI -> {
                error.put("code", status);
                error.put("message", message);
                error.put("status", geminiStatus(status));
            }
        }
        if (upstream) error.put("traceId", com.aiconnecting.common.SseUtils.currentTraceId());
        return root;
    }

    static String geminiStatus(int status) {
        return switch (status) {
            case 400 -> "INVALID_ARGUMENT";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "PERMISSION_DENIED";
            case 404 -> "NOT_FOUND";
            case 409 -> "ABORTED";
            case 413, 429 -> "RESOURCE_EXHAUSTED";
            case 501 -> "UNIMPLEMENTED";
            case 502, 503 -> "UNAVAILABLE";
            case 504 -> "DEADLINE_EXCEEDED";
            default -> status >= 500 ? "INTERNAL" : "UNKNOWN";
        };
    }

    public StreamState newStreamState(String model, RelayProtocol upstream, RelayProtocol client) {
        return new StreamState(model, upstream, client);
    }

    public void writeSseError(RelayProtocol protocol, HttpServletResponse response, int status,
                              String zhMessage, String enMessage, boolean upstream) throws IOException {
        String message = com.aiconnecting.common.SseUtils.clientErrorMessage(
                zhMessage, enMessage, upstream);
        writeSseError(protocol, response, status, message, upstream);
    }

    public void writeSseError(RelayProtocol protocol, HttpServletResponse response, int status,
                              String message, boolean upstream) throws IOException {
        response.setCharacterEncoding("UTF-8");
        var writer = response.getWriter();
        if (protocol != RelayProtocol.GEMINI) writer.write("event: error\n");
        writer.write("data: ");
        writer.write(objectMapper.writeValueAsString(errorEnvelope(protocol, status, message, upstream)));
        writer.write("\n\n");
        writer.flush();
    }

    /** Synthetic lifecycle prefix is needed only when a non-Claude upstream is exposed as Claude. */
    public List<String> streamPrefix(StreamState state) {
        if (state.client == RelayProtocol.CLAUDE && state.upstream != RelayProtocol.CLAUDE) {
            String id = "msg_" + System.currentTimeMillis();
            return List.of(
                    "{\"type\":\"message_start\",\"message\":{\"id\":\"" + id
                            + "\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":"
                            + jsonString(state.model) + ",\"stop_reason\":null,\"stop_sequence\":null}}",
                    "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"
            );
        }
        return List.of();
    }

    public List<String> convertStreamData(String data, StreamState state) {
        if (data == null || data.isBlank() || "[DONE]".equals(data)) return List.of();
        state.observe(data, objectMapper);
        if (state.upstream == state.client) return List.of(data);
        if (state.client == RelayProtocol.CLAUDE && state.upstream == RelayProtocol.GEMINI) {
            return geminiToClaudeEvents(data, state);
        }
        String converted = null;
        if (state.client == RelayProtocol.GEMINI) {
            converted = state.upstream == RelayProtocol.OPENAI
                    ? ProtocolConverter.convertOpenAiStreamChunkToGemini(data)
                    : ProtocolConverter.convertClaudeStreamEventToGemini(data);
        } else if (state.client == RelayProtocol.OPENAI) {
            if (state.upstream == RelayProtocol.GEMINI) {
                converted = geminiToOpenAiChunk(data);
            } else {
                converted = claudeToOpenAiEvent(data, state);
            }
        } else {
            converted = openAiToClaudeEvent(data);
        }
        return converted == null ? List.of() : List.of(converted);
    }

    public List<String> streamSuffix(StreamState state) {
        if (state.upstream == state.client) return List.of();
        if (state.client == RelayProtocol.OPENAI) return List.of("[DONE]");
        if (state.client == RelayProtocol.CLAUDE && state.upstream != RelayProtocol.CLAUDE) {
            String reason = state.stopReason != null ? state.stopReason : "end_turn";
            List<String> suffix = new ArrayList<>();
            suffix.add("{\"type\":\"content_block_stop\",\"index\":0}");
            int blockIndex = 1;
            for (ToolCall tool : state.toolCalls) {
                suffix.add("{\"type\":\"content_block_start\",\"index\":" + blockIndex
                        + ",\"content_block\":{\"type\":\"tool_use\",\"id\":" + jsonString(tool.id)
                        + ",\"name\":" + jsonString(tool.name) + ",\"input\":{}}}");
                suffix.add("{\"type\":\"content_block_delta\",\"index\":" + blockIndex
                        + ",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":"
                        + jsonString(tool.arguments.toString()) + "}}");
                suffix.add("{\"type\":\"content_block_stop\",\"index\":" + blockIndex + "}");
                blockIndex++;
            }
            if (!state.toolCalls.isEmpty() || state.geminiToolSeen) reason = "tool_use";
            suffix.add("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":" + jsonString(reason)
                    + ",\"stop_sequence\":null},\"usage\":{\"output_tokens\":" + state.completionTokens + "}}");
            suffix.add("{\"type\":\"message_stop\"}");
            suffix.add("[DONE]");
            return suffix;
        }
        return List.of();
    }

    public RelayServiceUtils.UsageInfo streamUsage(StreamState state) {
        int promptTokens = state.upstream == RelayProtocol.CLAUDE
                ? state.promptTokens + state.cacheReadTokens : state.promptTokens;
        return new RelayServiceUtils.UsageInfo(promptTokens, state.completionTokens,
                promptTokens + state.completionTokens, state.cachedTokens,
                state.cacheCreationTokens, state.cacheReadTokens);
    }

    private String geminiToOpenAiChunk(String data) {
        try {
            return RelayServiceUtils.convertGeminiStreamChunkToOpenAiSse(objectMapper, objectMapper.readTree(data));
        } catch (Exception e) {
            return null;
        }
    }

    private String claudeToOpenAiEvent(String data, StreamState state) {
        try {
            JsonNode json = objectMapper.readTree(data);
            String type = json.path("type").asText("");
            ObjectNode chunk = objectMapper.createObjectNode();
            chunk.put("id", "chatcmpl-" + System.currentTimeMillis());
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", state.model);
            var choices = chunk.putArray("choices");
            ObjectNode choice = choices.addObject();
            choice.put("index", 0);
            ObjectNode delta = choice.putObject("delta");
            choice.putNull("finish_reason");
            if ("content_block_delta".equals(type)) {
                JsonNode source = json.path("delta");
                if ("text_delta".equals(source.path("type").asText())) {
                    delta.put("content", source.path("text").asText(""));
                } else if ("input_json_delta".equals(source.path("type").asText())) {
                    int index = Math.max(0, json.path("index").asInt(1) - 1);
                    var toolCalls = delta.putArray("tool_calls");
                    ObjectNode tool = toolCalls.addObject();
                    tool.put("index", index);
                    tool.putObject("function").put("arguments", source.path("partial_json").asText(""));
                } else return null;
            } else if ("content_block_start".equals(type)
                    && "tool_use".equals(json.path("content_block").path("type").asText())) {
                JsonNode block = json.path("content_block");
                int index = Math.max(0, json.path("index").asInt(1) - 1);
                var toolCalls = delta.putArray("tool_calls");
                ObjectNode tool = toolCalls.addObject();
                tool.put("index", index);
                tool.put("id", block.path("id").asText(""));
                tool.put("type", "function");
                ObjectNode function = tool.putObject("function");
                function.put("name", block.path("name").asText(""));
                function.put("arguments", "");
            } else if ("message_delta".equals(type)) {
                String stop = json.path("delta").path("stop_reason").asText("end_turn");
                choice.put("finish_reason", "tool_use".equals(stop) ? "tool_calls"
                        : "max_tokens".equals(stop) ? "length" : "stop");
            } else return null;
            return objectMapper.writeValueAsString(chunk);
        } catch (Exception e) {
            return null;
        }
    }

    private String openAiToClaudeEvent(String data) {
        try {
            JsonNode json = objectMapper.readTree(data);
            JsonNode delta = json.path("choices").path(0).path("delta");
            String text = delta.path("content").asText("");
            if (text.isEmpty()) text = delta.path("reasoning_content").asText("");
            if (!text.isEmpty()) {
                return "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":"
                        + jsonString(text) + "}}";
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> geminiToClaudeEvents(String data, StreamState state) {
        try {
            JsonNode json = objectMapper.readTree(data);
            JsonNode candidate = json.path("candidates").path(0);
            List<String> events = new ArrayList<>();
            for (JsonNode part : candidate.path("content").path("parts")) {
                if (part.has("text") && !part.get("text").asText().isEmpty()) {
                    events.add("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":"
                            + jsonString(part.get("text").asText()) + "}}");
                }
                if (part.has("functionCall")) {
                    JsonNode function = part.get("functionCall");
                    int index = ++state.geminiToolBlockIndex;
                    String id = "call_" + index;
                    String args = objectMapper.writeValueAsString(function.path("args"));
                    events.add("{\"type\":\"content_block_start\",\"index\":" + index
                            + ",\"content_block\":{\"type\":\"tool_use\",\"id\":" + jsonString(id)
                            + ",\"name\":" + jsonString(function.path("name").asText("unknown")) + ",\"input\":{}}}");
                    events.add("{\"type\":\"content_block_delta\",\"index\":" + index
                            + ",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":"
                            + jsonString(args) + "}}");
                    events.add("{\"type\":\"content_block_stop\",\"index\":" + index + "}");
                    state.geminiToolSeen = true;
                }
            }
            return events;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String jsonString(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    public static final class StreamState {
        private final String model;
        private final RelayProtocol upstream;
        private final RelayProtocol client;
        private int promptTokens;
        private int completionTokens;
        private int cachedTokens;
        private int cacheCreationTokens;
        private int cacheReadTokens;
        private String stopReason;
        private final List<ToolCall> toolCalls = new ArrayList<>();
        private int geminiToolBlockIndex;
        private boolean geminiToolSeen;

        private StreamState(String model, RelayProtocol upstream, RelayProtocol client) {
            this.model = model;
            this.upstream = upstream;
            this.client = client;
        }

        private void observe(String data, ObjectMapper mapper) {
            try {
                JsonNode json = mapper.readTree(data);
                if (upstream == RelayProtocol.OPENAI) {
                    JsonNode usage = json.path("usage");
                    promptTokens = usage.path("prompt_tokens").asInt(promptTokens);
                    completionTokens = usage.path("completion_tokens").asInt(completionTokens);
                    cachedTokens = usage.path("prompt_tokens_details").path("cached_tokens").asInt(cachedTokens);
                    cacheReadTokens = cachedTokens;
                    String finish = json.path("choices").path(0).path("finish_reason").asText("");
                    if (!finish.isEmpty()) stopReason = "length".equals(finish) ? "max_tokens" : "end_turn";
                    JsonNode calls = json.path("choices").path(0).path("delta").path("tool_calls");
                    for (JsonNode call : calls) {
                        int index = call.path("index").asInt(0);
                        while (toolCalls.size() <= index) toolCalls.add(new ToolCall());
                        ToolCall target = toolCalls.get(index);
                        if (call.has("id")) target.id = call.get("id").asText("");
                        JsonNode function = call.path("function");
                        if (function.has("name")) target.name = function.get("name").asText("");
                        if (function.has("arguments")) target.arguments.append(function.get("arguments").asText(""));
                    }
                } else if (upstream == RelayProtocol.CLAUDE) {
                    JsonNode usage = json.has("message") ? json.path("message").path("usage") : json.path("usage");
                    promptTokens = usage.path("input_tokens").asInt(promptTokens);
                    completionTokens = usage.path("output_tokens").asInt(completionTokens);
                    cacheCreationTokens = usage.path("cache_creation_input_tokens").asInt(cacheCreationTokens);
                    cacheReadTokens = usage.path("cache_read_input_tokens").asInt(cacheReadTokens);
                    cachedTokens = cacheReadTokens;
                    String stop = json.path("delta").path("stop_reason").asText("");
                    if (!stop.isEmpty()) stopReason = stop;
                } else {
                    JsonNode usage = json.path("usageMetadata");
                    promptTokens = usage.path("promptTokenCount").asInt(promptTokens);
                    completionTokens = usage.path("candidatesTokenCount").asInt(completionTokens);
                    String finish = json.path("candidates").path(0).path("finishReason").asText("");
                    if (!finish.isEmpty()) stopReason = "MAX_TOKENS".equals(finish) ? "max_tokens" : "end_turn";
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static final class ToolCall {
        private String id = "";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();
    }

    public String rewriteModel(String body, String model) {
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node instanceof ObjectNode object) {
                object.put("model", model);
                return objectMapper.writeValueAsString(object);
            }
        } catch (Exception ignored) {
        }
        return body;
    }
}
