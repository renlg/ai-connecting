package com.aiconnecting.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * API 中转 Facade - 委托给各协议子服务处理
 * OpenAI 协议 → OpenAiRelayService
 * Claude 协议 → ClaudeRelayService
 * Gemini 协议 → GeminiRelayService
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RelayService {

    private final RelaySupport relaySupport;
    private final OpenAiRelayService openAiRelayService;
    private final ClaudeRelayService claudeRelayService;
    private final GeminiRelayService geminiRelayService;

    // ==================== OpenAI 协议中转 ====================

    public String relayRequest(String tokenKey, String path, String requestBody,
                               String model, HttpServletRequest httpRequest) {
        return openAiRelayService.relayRequest(tokenKey, path, requestBody, model, httpRequest);
    }

    public void relayStreamRequest(String tokenKey, String path, String requestBody,
                                    String model, HttpServletRequest httpRequest,
                                    HttpServletResponse httpResponse) throws IOException {
        openAiRelayService.relayStreamRequest(tokenKey, path, requestBody, model, httpRequest, httpResponse);
    }

    // ==================== Claude 协议中转 ====================

    public String claudeRelayRequest(String tokenKey, String requestBody,
                                     String model, HttpServletRequest httpRequest) {
        return claudeRelayService.claudeRelayRequest(tokenKey, requestBody, model, httpRequest);
    }

    public void claudeRelayStreamRequest(String tokenKey, String requestBody,
                                          String model, HttpServletRequest httpRequest,
                                          HttpServletResponse httpResponse) throws IOException {
        claudeRelayService.claudeRelayStreamRequest(tokenKey, requestBody, model, httpRequest, httpResponse);
    }

    // ==================== Gemini 协议中转 ====================

    public String geminiRelayRequest(String tokenKey, String requestBody,
                                     String model, HttpServletRequest httpRequest) {
        return geminiRelayService.geminiRelayRequest(tokenKey, requestBody, model, httpRequest);
    }

    public void geminiRelayStreamRequest(String tokenKey, String requestBody,
                                          String model, HttpServletRequest httpRequest,
                                          HttpServletResponse httpResponse) throws IOException {
        geminiRelayService.geminiRelayStreamRequest(tokenKey, requestBody, model, httpRequest, httpResponse);
    }

    // ==================== 模型名称解析 ====================

    public String resolveModelName(String model) {
        return relaySupport.resolveModelName(model);
    }

    public void clearModelNameCache() {
        relaySupport.clearModelNameCache();
    }
}
