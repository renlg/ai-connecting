package com.aiconnecting.service;

/**
 * 按平台模型名改写响应体。{@link #supports(String)} 接收请求/模型配置中的平台模型名，
 * 与渠道 modelMapping 后的上游模型名解耦；实现类只处理自己支持的模型。
 */
public interface ResponseTransformer {

    /** @param model 平台模型名（不是渠道 modelMapping 后的上游模型名） */
    boolean supports(String model);

    String transform(String model, String responseBodyJson);
}
