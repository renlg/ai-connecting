package com.aiconnecting.service;

import org.springframework.stereotype.Component;

import java.util.List;

/** 按平台模型名查找响应改写策略；匹配发生在渠道 modelMapping 之前，没有策略时原样透传。 */
@Component
public class ResponseTransformerRegistry {

    private final List<ResponseTransformer> transformers;

    public ResponseTransformerRegistry(List<ResponseTransformer> transformers) {
        this.transformers = transformers;
    }

    public String transform(String model, String responseBodyJson) {
        return transformers.stream()
                .filter(transformer -> transformer.supports(model))
                .findFirst()
                .map(transformer -> transformer.transform(model, responseBodyJson))
                .orElse(responseBodyJson);
    }
}
