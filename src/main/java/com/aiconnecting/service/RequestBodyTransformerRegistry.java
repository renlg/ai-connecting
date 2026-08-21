package com.aiconnecting.service;

import org.springframework.stereotype.Component;

import java.util.List;

/** 按平台模型名查找请求体转换策略；匹配发生在渠道 modelMapping 之前，没有策略时原样透传。 */
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
