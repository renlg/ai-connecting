package com.aiconnecting.service;

import com.aiconnecting.entity.Token;
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
    private final RelayOrchestrator relayOrchestrator;
    private final OpenAiRelayService openAiRelayService;
    private final ClaudeRelayService claudeRelayService;
    private final GeminiRelayService geminiRelayService;
    private final ModelGroupFailoverExecutor modelGroupFailoverExecutor;

    // ==================== OpenAI 协议中转 ====================
    // 模型组请求（model 命中一个已启用的模型组名）在此处分流到独立的 ModelGroupFailoverExecutor，
    // 不进入既有的单模型转发路径；单模型请求（model 未命中任何模型组）行为与改造前完全一致。

    public String relayRequest(String tokenKey, String path, String requestBody,
                               String model, HttpServletRequest httpRequest) {
        return relayRequest(tokenKey, path, requestBody, model, httpRequest, null);
    }

    public String relayRequest(String tokenKey, String path, String requestBody,
                               String model, HttpServletRequest httpRequest,
                               HttpServletResponse httpResponse) {
        return relayOrchestrator.relay(tokenKey, RelayProtocol.OPENAI, path, requestBody,
                model, httpRequest, httpResponse);
    }

    public void relayStreamRequest(String tokenKey, String path, String requestBody,
                                    String model, HttpServletRequest httpRequest,
                                    HttpServletResponse httpResponse) throws IOException {
        relayOrchestrator.relayStream(tokenKey, RelayProtocol.OPENAI, path, requestBody,
                model, httpRequest, httpResponse);
    }

    // ==================== 内部测试链路（按 Token 实体） ====================
    // Token 明文不再落库（仅存哈希）后，后台测试等内部调用方无法再凭 key 走公开链路，
    // 提供按已加载实体的等价入口：跳过 key 反查，校验/路由/计费逻辑完全一致

    public String relayRequestForToken(Token token, String path, String requestBody,
                                       String model, HttpServletRequest httpRequest,
                                       HttpServletResponse httpResponse) {
        return relayOrchestrator.relayForToken(token, RelayProtocol.OPENAI, path, requestBody,
                model, httpRequest, httpResponse);
    }

    public void relayStreamRequestForToken(Token token, String path, String requestBody,
                                           String model, HttpServletRequest httpRequest,
                                           HttpServletResponse httpResponse) throws IOException {
        relayOrchestrator.relayStreamForToken(token, RelayProtocol.OPENAI, path, requestBody,
                model, httpRequest, httpResponse);
    }

    public String claudeRelayRequestForToken(Token token, String requestBody,
                                             String model, HttpServletRequest httpRequest) {
        return relayOrchestrator.relayForToken(token, RelayProtocol.CLAUDE, "/v1/messages",
                requestBody, model, httpRequest, null);
    }

    public void claudeRelayStreamRequestForToken(Token token, String requestBody,
                                                 String model, HttpServletRequest httpRequest,
                                                 HttpServletResponse httpResponse) throws IOException {
        relayOrchestrator.relayStreamForToken(token, RelayProtocol.CLAUDE, "/v1/messages",
                requestBody, model, httpRequest, httpResponse);
    }

    public String relayMediaRequest(String tokenKey, String path, String requestBody,
                                    String model, HttpServletRequest httpRequest, String mediaType) {
        if (isGroupModel(model)) {
            return "video".equals(mediaType)
                    ? modelGroupFailoverExecutor.relayVideoRequest(tokenKey, path, requestBody, model, httpRequest)
                    : modelGroupFailoverExecutor.relayImageRequest(tokenKey, path, requestBody, model, httpRequest);
        }
        return openAiRelayService.relayMediaRequest(tokenKey, path, requestBody, model, httpRequest, mediaType);
    }

    public String relayVideoStatusRequest(String tokenKey, String videoId) {
        return openAiRelayService.relayVideoStatusRequest(tokenKey, videoId);
    }

    public void relayAudioSpeech(String tokenKey, String requestBody, String model,
                                 HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException {
        if (isGroupModel(model)) {
            modelGroupFailoverExecutor.relayAudioSpeech(tokenKey, requestBody, model, httpRequest, httpResponse);
            return;
        }
        openAiRelayService.relayAudioSpeech(tokenKey, requestBody, model, httpRequest, httpResponse);
    }

    /**
     * 单模型名称优先于同名模型组：若存在同名的 model_config，即便同名的组也已启用，
     * 请求仍按单模型路径处理（正常情况下二者不应同名，见 ModelGroupService#guardDuplicateName
     * 与 ModelConfigController 的创建/更新校验；此处仅作为路由层的兜底防御）
     */
    private boolean isGroupModel(String model) {
        if (relaySupport.findModelConfigCached(model) != null) {
            return false;
        }
        return modelGroupFailoverExecutor.findEnabledGroup(model).isPresent();
    }

    public org.springframework.http.ResponseEntity<byte[]> relayAudioTranscription(
            String tokenKey, String path, org.springframework.web.multipart.MultipartFile file,
            org.springframework.util.MultiValueMap<String, String> formFields, HttpServletRequest httpRequest) throws IOException {
        return openAiRelayService.relayAudioTranscription(tokenKey, path, file, formFields, httpRequest);
    }

    // ==================== Claude 协议中转 ====================

    public String claudeRelayRequest(String tokenKey, String requestBody,
                                     String model, HttpServletRequest httpRequest) {
        return relayOrchestrator.relay(tokenKey, RelayProtocol.CLAUDE, "/v1/messages",
                requestBody, model, httpRequest, null);
    }

    public String claudeRelayRequest(String tokenKey, String requestBody,
                                     String model, HttpServletRequest httpRequest,
                                     HttpServletResponse httpResponse) {
        return relayOrchestrator.relay(tokenKey, RelayProtocol.CLAUDE, "/v1/messages",
                requestBody, model, httpRequest, httpResponse);
    }

    public void claudeRelayStreamRequest(String tokenKey, String requestBody,
                                          String model, HttpServletRequest httpRequest,
                                          HttpServletResponse httpResponse) throws IOException {
        relayOrchestrator.relayStream(tokenKey, RelayProtocol.CLAUDE, "/v1/messages",
                requestBody, model, httpRequest, httpResponse);
    }

    // ==================== Gemini 协议中转 ====================

    public String geminiRelayRequest(String tokenKey, String requestBody,
                                     String model, HttpServletRequest httpRequest) {
        return relayOrchestrator.relay(tokenKey, RelayProtocol.GEMINI,
                "/v1/models/" + model + ":generateContent", requestBody, model, httpRequest, null);
    }

    public String geminiRelayRequest(String tokenKey, String requestBody,
                                     String model, HttpServletRequest httpRequest,
                                     HttpServletResponse httpResponse) {
        return relayOrchestrator.relay(tokenKey, RelayProtocol.GEMINI,
                "/v1/models/" + model + ":generateContent", requestBody, model, httpRequest, httpResponse);
    }

    public void geminiRelayStreamRequest(String tokenKey, String requestBody,
                                          String model, HttpServletRequest httpRequest,
                                          HttpServletResponse httpResponse) throws IOException {
        relayOrchestrator.relayStream(tokenKey, RelayProtocol.GEMINI,
                "/v1/models/" + model + ":streamGenerateContent", requestBody, model, httpRequest, httpResponse);
    }

    // ==================== 模型名称解析 ====================

    public String resolveModelName(String model) {
        return relaySupport.resolveModelName(model);
    }

    public void clearModelNameCache() {
        relaySupport.clearModelNameCache();
    }
}
