package com.aiconnecting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 剥离 glm-5.3 不支持的思考开关参数，由上游使用始终思考的默认行为。 */
@Slf4j
@Component
public class Glm53RequestBodyTransformer implements RequestBodyTransformer {

    private static final Set<String> MODELS = Set.of("glm-5.3", "glm-5.3-flash");
    private static final Set<String> THINKING_TOGGLE_FIELDS = Set.of(
            "thinking",
            "enable_thinking",
            "thinking_enabled",
            "disable_thinking",
            "thinking_disabled",
            "reasoning_effort"
    );

    private final ObjectMapper objectMapper;

    public Glm53RequestBodyTransformer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String model) {
        return MODELS.contains(model);
    }

    @Override
    public String transform(String model, String requestBodyJson) {
        try {
            JsonNode body = objectMapper.readTree(requestBodyJson);
            Object topLevelFields = "non-object";
            if (body instanceof ObjectNode objectBody) {
                List<String> fieldNames = new ArrayList<>();
                objectBody.fieldNames().forEachRemaining(fieldNames::add);
                topLevelFields = fieldNames;
            }
            log.warn("GLM53_TRANSFORM received model={}, topLevelFields={}, requestBodyJson={}",
                    model, topLevelFields, requestBodyJson);
            if (!(body instanceof ObjectNode objectBody)) {
                log.warn("GLM53_TRANSFORM stripped model={}, body={}", model, requestBodyJson);
                return requestBodyJson;
            }

            boolean modified = false;
            for (String field : THINKING_TOGGLE_FIELDS) {
                modified |= objectBody.remove(field) != null;
            }
            String transformedBody = modified ? objectMapper.writeValueAsString(objectBody) : requestBodyJson;
            log.warn("GLM53_TRANSFORM stripped model={}, body={}", model, transformedBody);
            return transformedBody;
        } catch (IOException | RuntimeException e) {
            return requestBodyJson;
        }
    }
}
