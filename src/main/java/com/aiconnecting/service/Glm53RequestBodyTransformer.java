package com.aiconnecting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

/** 剥离 glm-5.3 不支持的思考开关参数，由上游使用始终思考的默认行为。 */
@Component
public class Glm53RequestBodyTransformer implements RequestBodyTransformer {

    private static final String MODEL = "glm-5.3";
    private static final Set<String> THINKING_TOGGLE_FIELDS = Set.of(
            "thinking",
            "enable_thinking",
            "thinking_enabled",
            "disable_thinking",
            "thinking_disabled"
    );

    private final ObjectMapper objectMapper;

    public Glm53RequestBodyTransformer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String model) {
        return MODEL.equals(model);
    }

    @Override
    public String transform(String model, String requestBodyJson) {
        try {
            JsonNode body = objectMapper.readTree(requestBodyJson);
            if (!(body instanceof ObjectNode objectBody)) {
                return requestBodyJson;
            }

            boolean modified = false;
            for (String field : THINKING_TOGGLE_FIELDS) {
                modified |= objectBody.remove(field) != null;
            }
            return modified ? objectMapper.writeValueAsString(objectBody) : requestBodyJson;
        } catch (IOException | RuntimeException e) {
            return requestBodyJson;
        }
    }
}
