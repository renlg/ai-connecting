package com.aiconnecting.controller;

import com.aiconnecting.common.ApiResponse;
import com.aiconnecting.common.BusinessException;
import com.aiconnecting.dto.TokenRequest;
import com.aiconnecting.entity.Token;
import com.aiconnecting.entity.User;
import com.aiconnecting.repository.UsageLogRepository;
import com.aiconnecting.repository.UserRepository;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.repository.ChannelRepository;
import com.aiconnecting.repository.ModelConfigRepository;
import com.aiconnecting.service.ChannelService;
import com.aiconnecting.service.RelayService;
import com.aiconnecting.service.TokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;

import jakarta.servlet.http.HttpServletResponse;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/tokens")
@RequiredArgsConstructor
public class TokenController {

    private final TokenService tokenService;
    private final UsageLogRepository usageLogRepository;
    private final UserRepository userRepository;
    private final RelayService relayService;
    private final ChannelService channelService;
    private final ChannelRepository channelRepository;
    private final ModelConfigRepository modelConfigRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 普通用户查看自己的 token */
    @GetMapping
    public ApiResponse<List<Token>> list(@AuthenticationPrincipal User user,
                                         @RequestParam(required = false) String search) {
        List<Token> tokens;
        if ("admin".equals(user.getRole())) {
            tokens = tokenService.listAll();
        } else {
            tokens = tokenService.listByUser(user.getId());
        }
        // 填充 ownerName
        fillOwnerName(tokens);
        // 按账号搜索
        if (search != null && !search.trim().isEmpty()) {
            String keyword = search.trim().toLowerCase();
            tokens = tokens.stream()
                    .filter(t -> t.getOwnerName() != null && t.getOwnerName().toLowerCase().contains(keyword))
                    .collect(Collectors.toList());
        }
        return ApiResponse.success(tokens);
    }

    @GetMapping("/{id}")
    public ApiResponse<Token> getById(@PathVariable Long id) {
        return ApiResponse.success(tokenService.getById(id));
    }

    @PostMapping
    public ApiResponse<Token> create(@AuthenticationPrincipal User user,
                                     @RequestBody TokenRequest request) {
        return ApiResponse.success(tokenService.create(user.getId(), request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Token> update(@AuthenticationPrincipal User user,
                                     @PathVariable Long id, @RequestBody TokenRequest request) {
        Token token = tokenService.getById(id);
        checkTokenOwner(user, token);
        return ApiResponse.success(tokenService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal User user, @PathVariable Long id) {
        Token token = tokenService.getById(id);
        checkTokenOwner(user, token);
        tokenService.delete(id);
        return ApiResponse.success();
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@AuthenticationPrincipal User user,
                                          @PathVariable Long id, @RequestBody Map<String, Integer> body) {
        Token token = tokenService.getById(id);
        checkTokenOwner(user, token);
        tokenService.updateStatus(id, body.get("status"));
        return ApiResponse.success();
    }

    @GetMapping("/{id}/credit-history")
    public ApiResponse<List<Map<String, Object>>> creditHistory(@AuthenticationPrincipal User user,
                                                                 @PathVariable Long id) {
        Token token = tokenService.getById(id);
        if (!"admin".equals(user.getRole()) && !token.getUserId().equals(user.getId())) {
            throw new BusinessException(403, "无权查看该 Token 的消耗记录");
        }
        LocalDateTime since = LocalDateTime.now().minusDays(90);
        List<Object[]> rows = usageLogRepository.findDailyCreditCostByTokenIdSince(id, since);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new HashMap<>();
            item.put("date", row[0]);
            item.put("credits", row[1]);
            result.add(item);
        }
        return ApiResponse.success(result);
    }

    /**
     * 测试 Token 聊天功能 - 支持 OpenAI 和 Claude 协议
     */
    @PostMapping("/test-chat")
    public ApiResponse<Map<String, Object>> testChat(@RequestBody Map<String, String> request) {
        String tokenKey = request.get("tokenKey");
        String protocol = request.get("protocol"); // "openai" or "claude"
        String model = request.get("model");
        String message = request.get("message");

        if (tokenKey == null || tokenKey.isBlank()) {
            throw new BusinessException("缺少 Token Key");
        }
        if (model == null || model.isBlank()) {
            throw new BusinessException("请选择模型");
        }

        // 解析 displayName 为实际模型名
        String resolvedModel = relayService.resolveModelName(model);

        long startTime = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            if ("claude".equalsIgnoreCase(protocol)) {
                // Claude 协议测试
                String requestBody = objectMapper.writeValueAsString(Map.of(
                        "model", resolvedModel,
                        "max_tokens", 100,
                        "messages", List.of(Map.of("role", "user", "content", message != null ? message : "hi"))
                ));
                String response = relayService.claudeRelayRequest(tokenKey, requestBody, resolvedModel, null);
                long duration = System.currentTimeMillis() - startTime;

                JsonNode root = objectMapper.readTree(response);
                JsonNode contentArr = root.get("content");
                StringBuilder sb = new StringBuilder();
                if (contentArr != null && contentArr.isArray()) {
                    for (JsonNode node : contentArr) {
                        if ("text".equals(node.path("type").asText())) {
                            sb.append(node.path("text").asText());
                        }
                    }
                }
                result.put("success", true);
                result.put("content", sb.toString());
                result.put("duration", duration);
                result.put("protocol", "claude");
                JsonNode usage = root.get("usage");
                if (usage != null) {
                    Map<String, Object> usageMap = new LinkedHashMap<>();
                    usageMap.put("input_tokens", usage.path("input_tokens").asInt(0));
                    usageMap.put("output_tokens", usage.path("output_tokens").asInt(0));
                    result.put("usage", usageMap);
                }
            } else {
                // OpenAI 协议测试
                String requestBody = objectMapper.writeValueAsString(Map.of(
                        "model", resolvedModel,
                        "messages", List.of(Map.of("role", "user", "content", message != null ? message : "hi")),
                        "max_tokens", 100
                ));
                String response = relayService.relayRequest(tokenKey, "/v1/chat/completions", requestBody, resolvedModel, null);
                long duration = System.currentTimeMillis() - startTime;

                JsonNode root = objectMapper.readTree(response);
                String content = root.path("choices").path(0).path("message").path("content").asText("");
                result.put("success", true);
                result.put("content", content);
                result.put("duration", duration);
                result.put("protocol", "openai");
                JsonNode usage = root.get("usage");
                if (usage != null) {
                    Map<String, Object> usageMap = new LinkedHashMap<>();
                    usageMap.put("prompt_tokens", usage.path("prompt_tokens").asInt(0));
                    usageMap.put("completion_tokens", usage.path("completion_tokens").asInt(0));
                    usageMap.put("total_tokens", usage.path("total_tokens").asInt(0));
                    result.put("usage", usageMap);
                }
            }
        } catch (BusinessException e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("duration", System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "请求失败: " + e.getMessage());
            result.put("duration", System.currentTimeMillis() - startTime);
        }

        return ApiResponse.success(result);
    }

    /**
     * 测试 Token 聊天功能（流式）- 支持 OpenAI 和 Claude 协议
     */
    @PostMapping(value = "/test-chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void testChatStream(@RequestBody Map<String, String> request, HttpServletResponse response) throws Exception {
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        String tokenKey = request.get("tokenKey");
        String protocol = request.get("protocol"); // "openai" or "claude"
        String model = request.get("model");
        String message = request.get("message");

        if (tokenKey == null || tokenKey.isBlank()) {
            throw new BusinessException("缺少 Token Key");
        }
        if (model == null || model.isBlank()) {
            throw new BusinessException("请选择模型");
        }

        // 解析 displayName 为实际模型名
        String resolvedModel = relayService.resolveModelName(model);

        try {
            if ("claude".equalsIgnoreCase(protocol)) {
                // Claude 协议流式测试
                String requestBody = objectMapper.writeValueAsString(Map.of(
                        "model", resolvedModel,
                        "max_tokens", 100,
                        "stream", true,
                        "messages", List.of(Map.of("role", "user", "content", message != null ? message : "hi"))
                ));
                
                // 调用上游流式接口并转发
                forwardClaudeStream(tokenKey, requestBody, resolvedModel, response);
            } else {
                // OpenAI 协议流式测试
                String requestBody = objectMapper.writeValueAsString(Map.of(
                        "model", resolvedModel,
                        "messages", List.of(Map.of("role", "user", "content", message != null ? message : "hi")),
                        "max_tokens", 100,
                        "stream", true
                ));
                
                // 调用上游流式接口并转发
                forwardOpenAIStream(tokenKey, requestBody, resolvedModel, response);
            }
        } catch (Exception e) {
            // 发送错误事件
            response.getWriter().write("data: {\"error\":\"" + escapeJson(e.getMessage()) + "\"}\n\n");
            // 发送 [DONE] 标记以结束 SSE 流
            response.getWriter().write("data: [DONE]\n\n");
            response.getWriter().flush();
        }
    }

    private void forwardOpenAIStream(String tokenKey, String requestBody, String model, HttpServletResponse response) throws Exception {
        // 走真实的中转接口，包含积分扣减
        relayService.relayStreamRequest(tokenKey, "/v1/chat/completions", requestBody, model, null, response);
    }

    private void forwardClaudeStream(String tokenKey, String requestBody, String model, HttpServletResponse response) throws Exception {
        // 走真实的 Claude 中转接口，包含积分扣减
        relayService.claudeRelayStreamRequest(tokenKey, requestBody, model, null, response);
    }

    private String extractMessageFromRequestBody(String requestBody) {
        try {
            JsonNode node = objectMapper.readTree(requestBody);
            JsonNode messages = node.get("messages");
            if (messages != null && messages.isArray() && messages.size() > 0) {
                return messages.get(messages.size() - 1).path("content").asText("hi");
            }
        } catch (Exception e) {
            // ignore
        }
        return "hi";
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * 填充 Token 的 ownerName
     */
    private void fillOwnerName(List<Token> tokens) {
        if (tokens == null || tokens.isEmpty()) return;
        // 批量查询用户
        var userIds = tokens.stream().map(Token::getUserId).distinct().collect(Collectors.toList());
        var userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername));
        tokens.forEach(t -> t.setOwnerName(userMap.getOrDefault(t.getUserId(), "unknown")));
    }

    /**
     * 获取当前 Token 可用的模型列表（仅返回 id 和 displayName）
     */
    @GetMapping("/models")
    public ApiResponse<List<Map<String, Object>>> getAvailableModels(@AuthenticationPrincipal User user) {
        boolean isAdmin = "admin".equals(user.getRole());

        // 获取启用的模型
        List<ModelConfig> models = isAdmin
                ? modelConfigRepository.findByStatusOrderByStatusDescNameAsc(1)
                : modelConfigRepository.findByStatusAndAdminOnlyFalseOrderByStatusDescNameAsc(1);

        // 收集所有启用渠道配置的模型ID
        Set<String> channelModelIds = new LinkedHashSet<>();
        for (Channel channel : channelRepository.findByStatusOrderByPriorityDesc(1)) {
            if (channel.getModelIds() != null && !channel.getModelIds().isEmpty()) {
                for (String modelId : channel.getModelIds().split(",")) {
                    channelModelIds.add(modelId.trim());
                }
            }
        }

        // 只返回 id 和 displayName
        List<Map<String, Object>> result = new ArrayList<>();
        for (ModelConfig model : models) {
            if (!channelModelIds.contains(String.valueOf(model.getId()))) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", model.getId());
            item.put("displayName", model.getDisplayName() != null ? model.getDisplayName() : model.getName());
            result.add(item);
        }
        return ApiResponse.success(result);
    }

    /**
     * 检查当前用户是否为 Token 所有者或管理员
     */
    private void checkTokenOwner(User user, Token token) {
        if (!"admin".equals(user.getRole()) && !token.getUserId().equals(user.getId())) {
            throw new BusinessException(403, "无权操作该 Token");
        }
    }
}
