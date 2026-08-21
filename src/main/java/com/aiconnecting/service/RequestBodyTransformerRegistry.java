package com.aiconnecting.service;

import org.springframework.stereotype.Component;

import java.util.List;

/** 按模型查找请求体转换策略；没有匹配策略时保持原请求体不变。 */
@Component
public class RequestBodyTransformerRegistry {

    private final List<RequestBodyTransformer> transformers;

    public RequestBodyTransformerRegistry(List<RequestBodyTransformer> transformers) {
        this.transformers = transformers;
    }

    public String transform(String model, String requestBodyJson) {
        return transformers.stream()
                .filter(transformer -> transformer.supports(model))
                .findFirst()
                .map(transformer -> transformer.transform(model, requestBodyJson))
                .orElse(requestBodyJson);
    }
}
