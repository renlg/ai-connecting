package com.aiconnecting.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;

/** Single protocol-independent entry point for all public text relay protocols. */
@Service
@RequiredArgsConstructor
public class RelayOrchestrator {

    private final RelaySupport support;
    private final RelayProtocolAdapter protocolAdapter;
    private final OpenAiRelayService openAiRelayService;
    private final ClaudeRelayService claudeRelayService;
    private final GeminiRelayService geminiRelayService;
    private final ModelGroupFailoverExecutor groupExecutor;

    public String relay(String tokenKey, RelayProtocol protocol, String path, String body,
                        String pathModel, HttpServletRequest request, HttpServletResponse response) {
        UnifiedRelayRequest unified = protocolAdapter.adaptRequest(protocol, path, body, pathModel);
        if (isGroupModel(unified.model())) {
            return groupExecutor.relayRequest(tokenKey, unified, request, response);
        }
        return switch (protocol) {
            case OPENAI -> openAiRelayService.relayRequest(tokenKey, path, body, unified.model(), request, response);
            case CLAUDE -> claudeRelayService.claudeRelayRequest(tokenKey, body, unified.model(), request);
            case GEMINI -> geminiRelayService.geminiRelayRequest(tokenKey, body, unified.model(), request);
        };
    }

    public void relayStream(String tokenKey, RelayProtocol protocol, String path, String body,
                            String pathModel, HttpServletRequest request,
                            HttpServletResponse response) throws IOException {
        UnifiedRelayRequest unified = protocolAdapter.adaptRequest(protocol, path, body, pathModel);
        if (isGroupModel(unified.model())) {
            groupExecutor.relayStreamRequest(tokenKey, unified, request, response);
            return;
        }
        switch (protocol) {
            case OPENAI -> openAiRelayService.relayStreamRequest(
                    tokenKey, path, body, unified.model(), request, response);
            case CLAUDE -> claudeRelayService.claudeRelayStreamRequest(
                    tokenKey, body, unified.model(), request, response);
            case GEMINI -> geminiRelayService.geminiRelayStreamRequest(
                    tokenKey, body, unified.model(), request, response);
        }
    }

    /** A concrete model wins over an accidentally colliding group name. */
    boolean isGroupModel(String model) {
        if (support.findModelConfigCached(model) != null) return false;
        return groupExecutor.findEnabledGroup(model).isPresent();
    }
}
