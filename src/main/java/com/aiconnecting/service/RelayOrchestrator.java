package com.aiconnecting.service;

import com.aiconnecting.entity.Token;
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

    /**
     * 供后台测试等已持有 Token 实体的内部调用方复用：Token 明文不再落库后无法凭 key 反查，
     * 直接按实体构建上下文，路由与校验逻辑与 {@link #relay} 完全一致
     */
    public String relayForToken(Token token, RelayProtocol protocol, String path, String body,
                                String pathModel, HttpServletRequest request, HttpServletResponse response) {
        UnifiedRelayRequest unified = protocolAdapter.adaptRequest(protocol, path, body, pathModel);
        if (isGroupModel(unified.model())) {
            return groupExecutor.relayRequestForToken(token, unified, request, response);
        }
        return switch (protocol) {
            case OPENAI -> openAiRelayService.relayRequestForToken(token, path, body, unified.model(), request, response);
            case CLAUDE -> claudeRelayService.claudeRelayRequestForToken(token, body, unified.model(), request);
            case GEMINI -> geminiRelayService.geminiRelayRequestForToken(token, body, unified.model(), request);
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

    /** 供后台测试等已持有 Token 实体的内部调用方复用，路由与校验逻辑与 {@link #relayStream} 完全一致 */
    public void relayStreamForToken(Token token, RelayProtocol protocol, String path, String body,
                                    String pathModel, HttpServletRequest request,
                                    HttpServletResponse response) throws IOException {
        UnifiedRelayRequest unified = protocolAdapter.adaptRequest(protocol, path, body, pathModel);
        if (isGroupModel(unified.model())) {
            groupExecutor.relayStreamRequestForToken(token, unified, request, response);
            return;
        }
        switch (protocol) {
            case OPENAI -> openAiRelayService.relayStreamRequestForToken(
                    token, path, body, unified.model(), request, response);
            case CLAUDE -> claudeRelayService.claudeRelayStreamRequestForToken(
                    token, body, unified.model(), request, response);
            case GEMINI -> geminiRelayService.geminiRelayStreamRequestForToken(
                    token, body, unified.model(), request, response);
        }
    }

    /** A concrete model wins over an accidentally colliding group name. */
    boolean isGroupModel(String model) {
        if (support.findModelConfigCached(model) != null) return false;
        return groupExecutor.findEnabledGroup(model).isPresent();
    }
}
