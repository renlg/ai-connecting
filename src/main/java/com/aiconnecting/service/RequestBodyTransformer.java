package com.aiconnecting.service;

/**
 * 按上游模型转换请求体参数。实现类只处理自己支持的模型，未匹配的请求由注册表原样透传。
 */
public interface RequestBodyTransformer {

    boolean supports(String model);

    String transform(String model, String requestBodyJson);
}
