package com.aiconnecting.service;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/** 修复 agnes-2.5-flash 历史工具调用中不兼容的参数格式。 */
@Component
public class AgnesV25FlashRequestBodyTransformer implements RequestBodyTransformer {

    private static final String MODEL = "agnes-2.5-flash";

    private final ObjectMapper objectMapper;
    private final ObjectMapper lenientObjectMapper;

    public AgnesV25FlashRequestBodyTransformer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.lenientObjectMapper = JsonMapper.builder()
                .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
                .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
                .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
                .build();
    }

    @Override
    public boolean supports(String model) {
        return MODEL.equals(model);
    }

    @Override
    public String transform(String model, String requestBodyJson) {
        try {
            JsonNode body = objectMapper.readTree(requestBodyJson);
            if (!(body instanceof ObjectNode objectBody)
                    || !(objectBody.get("messages") instanceof ArrayNode messages)) {
                return requestBodyJson;
            }

            boolean modified = false;
            for (JsonNode message : messages) {
                if (!(message instanceof ObjectNode objectMessage)
                        || !"assistant".equals(objectMessage.path("role").asText())
                        || !(objectMessage.get("tool_calls") instanceof ArrayNode toolCalls)) {
                    continue;
                }

                for (JsonNode toolCall : toolCalls) {
                    if (!(toolCall instanceof ObjectNode objectToolCall)) {
                        continue;
                    }

                    if (!objectToolCall.hasNonNull("id")) {
                        objectToolCall.put("id", "call_" + UUID.randomUUID().toString().substring(0, 8));
                        modified = true;
                    }

                    JsonNode function = objectToolCall.get("function");
                    if (!(function instanceof ObjectNode objectFunction)) {
                        continue;
                    }

                    JsonNode arguments = objectFunction.get("arguments");
                    if (arguments != null && (arguments.isObject() || arguments.isArray())) {
                        objectFunction.put("arguments", objectMapper.writeValueAsString(arguments));
                        modified = true;
                    } else if (arguments != null && arguments.isTextual()
                            && !isValidJson(arguments.textValue(), objectMapper)) {
                        objectFunction.put("arguments", repairArguments(arguments.textValue()));
                        modified = true;
                    }
                }
            }

            return modified ? objectMapper.writeValueAsString(objectBody) : requestBodyJson;
        } catch (IOException | RuntimeException e) {
            return requestBodyJson;
        }
    }

    private String repairArguments(String arguments) throws IOException {
        try {
            JsonNode repaired = readJson(arguments, lenientObjectMapper);
            return objectMapper.writeValueAsString(repaired);
        } catch (IOException e) {
            return "{}";
        }
    }

    private boolean isValidJson(String value, ObjectMapper mapper) {
        try {
            readJson(value, mapper);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private JsonNode readJson(String value, ObjectMapper mapper) throws IOException {
        JsonNode parsed = mapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(value);
        if (parsed == null || parsed.isMissingNode()) {
            throw new IOException("Empty JSON content");
        }
        return parsed;
    }
}
