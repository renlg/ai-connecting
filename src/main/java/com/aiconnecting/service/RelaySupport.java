package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.entity.Token;
import com.aiconnecting.entity.User;
import com.aiconnecting.entity.UsageLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 中转服务公共支撑类，提供 Token 校验、模型权限、渠道选择、使用记录、模型名称解析等公共能力
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RelaySupport {

    private final ChannelService channelService;
    final ChannelRouter channelRouter;
    final ChannelHealthTracker channelHealthTracker;
    private final TokenService tokenService;
    private final UsageLogService usageLogService;
    private final ModelConfigService modelConfigService;
    private final UserService userService;

    @Autowired(required = false)
    RateLimitService rateLimitService;

    @Autowired(required = false)
    private okhttp3.Interceptor tracingInterceptor;

    private OkHttpClient httpClient;
    final ObjectMapper objectMapper = new ObjectMapper();

    private final ConcurrentHashMap<String, CachedValue> modelNameCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedAllowedModels> allowedModelsCache = new ConcurrentHashMap<>();

    static final int MAX_RETRIES = 3;
    private static final long MODEL_CACHE_TTL_MS = 2 * 60 * 1000L;
    private static final long ALLOWED_MODELS_CACHE_TTL_MS = 2 * 60 * 1000L;

    record RelayContext(Token token, String channelModelId) {}

    private record CachedValue(String value, long cachedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > MODEL_CACHE_TTL_MS;
        }
    }

    private record CachedAllowedModels(Set<String> models, long cachedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > ALLOWED_MODELS_CACHE_TTL_MS;
        }
    }

    @jakarta.annotation.PostConstruct
    void initHttpClient() {
        System.setProperty("http.keepAlive", "false");

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS);
        if (tracingInterceptor != null) {
            builder.addInterceptor(tracingInterceptor);
        }
        httpClient = builder.build();
    }

    // ==================== 预检与校验 ====================

    RelayContext validateAndPrepare(String tokenKey, String model) {
        Token token = tokenService.validateTokenKey(tokenKey);
        if (token.getQuota() != -1 && token.getUsedQuota() >= token.getQuota()) {
            throw new BusinessException(429, "Token 额度已用完");
        }
        User tokenUser = userService.getByIdCached(token.getUserId());
        if (!"admin".equals(tokenUser.getRole()) && tokenUser.getCredits() != null && tokenUser.getCredits().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(402, "用户积分不足，请先充值");
        }
        checkModelPermission(token, model);
        String channelModelId = resolveToChannelModelId(model);
        if (rateLimitService != null) {
            rateLimitService.checkTokenRateLimit(token.getId(), token.getRateLimit());
        }
        return new RelayContext(token, channelModelId);
    }

    private void checkModelPermission(Token token, String model) {
        if (token.getAllowedModels() != null && !token.getAllowedModels().isEmpty()) {
            CachedAllowedModels cached = allowedModelsCache.get(token.getAllowedModels());
            Set<String> allowed;
            if (cached != null && !cached.isExpired()) {
                allowed = cached.models();
            } else {
                allowed = Arrays.stream(token.getAllowedModels().split(","))
                        .map(String::trim).collect(Collectors.toSet());
                allowedModelsCache.put(token.getAllowedModels(),
                        new CachedAllowedModels(allowed, System.currentTimeMillis()));
            }
            if (!allowed.contains(model)) {
                throw new BusinessException(403, "该 Token 无权使用模型: " + model);
            }
        }
    }

    // ==================== 模型名称解析 ====================

    public String resolveModelName(String model) {
        if (model == null || model.isEmpty()) {
            return model;
        }
        String cacheKey = "resolve:" + model;
        CachedValue cached = modelNameCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.value();
        }
        if (!modelConfigService.findByName(model).isEmpty()) {
            modelNameCache.put(cacheKey, new CachedValue(model, System.currentTimeMillis()));
            return model;
        }
        List<ModelConfig> byDisplayName = modelConfigService.findByDisplayName(model);
        if (!byDisplayName.isEmpty()) {
            String resolved = byDisplayName.get(0).getName();
            modelNameCache.put(cacheKey, new CachedValue(resolved, System.currentTimeMillis()));
            return resolved;
        }
        return model;
    }

    String resolveToChannelModelId(String modelName) {
        if (modelName == null || modelName.isEmpty()) return modelName;
        String cacheKey = "channelId:" + modelName;
        CachedValue cached = modelNameCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.value();
        }
        List<ModelConfig> models = modelConfigService.findByName(modelName);
        if (!models.isEmpty()) {
            String id = String.valueOf(models.get(0).getId());
            modelNameCache.put(cacheKey, new CachedValue(id, System.currentTimeMillis()));
            return id;
        }
        return modelName;
    }

    public void clearModelNameCache() {
        modelNameCache.clear();
    }

    // ==================== 渠道判断 ====================

    boolean isChannelRateLimited(Channel channel) {
        if (rateLimitService != null) {
            try {
                rateLimitService.checkChannelRateLimit(channel.getId(), channel.getRateLimit());
            } catch (BusinessException e) {
                return true;
            }
        }
        return false;
    }

    boolean isClaudeTypeChannel(Channel channel) {
        return "claude".equalsIgnoreCase(channel.getType()) || "anthropic".equalsIgnoreCase(channel.getType());
    }

    boolean isGeminiTypeChannel(Channel channel) {
        return "gemini".equalsIgnoreCase(channel.getType());
    }

    // ==================== 连接与认证 ====================

    String maskApiKey(String url) {
        return url.replaceAll("([?&]key=)[^&]*", "$1***");
    }

    HttpURLConnection createSseConnection(Channel channel, String path, String requestBody) throws IOException {
        String url = channel.getBaseUrl().replaceAll("/+$", "") + path;
        log.info("流式请求: url={}, channel={}", maskApiKey(url), channel.getId());
        java.net.URL urlObj = new java.net.URL(url);
        HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Connection", "close");
            applyChannelAuthToConnection(conn, channel);
            conn.getOutputStream().write(requestBody.getBytes(StandardCharsets.UTF_8));
            conn.getOutputStream().flush();
        } catch (IOException e) {
            conn.disconnect();
            throw e;
        }
        return conn;
    }

    private void applyChannelAuth(Request.Builder builder, Channel channel) {
        if ("claude".equalsIgnoreCase(channel.getType()) || "anthropic".equalsIgnoreCase(channel.getType())) {
            builder.addHeader("x-api-key", channel.getApiKey());
            builder.addHeader("anthropic-version", "2023-06-01");
        } else {
            builder.addHeader("Authorization", "Bearer " + channel.getApiKey());
        }
    }

    private void applyChannelAuthToConnection(HttpURLConnection conn, Channel channel) {
        if ("claude".equalsIgnoreCase(channel.getType()) || "anthropic".equalsIgnoreCase(channel.getType())) {
            conn.setRequestProperty("x-api-key", channel.getApiKey());
            conn.setRequestProperty("anthropic-version", "2023-06-01");
        } else {
            conn.setRequestProperty("Authorization", "Bearer " + channel.getApiKey());
        }
    }

    // ==================== 上游请求转发 ====================

    String forwardRequest(Channel channel, String path, String requestBody) {
        String url = channel.getBaseUrl().replaceAll("/+$", "") + path;

        RequestBody body = RequestBody.create(requestBody, MediaType.parse("application/json"));
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(body);
        applyChannelAuth(requestBuilder, channel);
        Request request = requestBuilder.build();

        try (okhttp3.Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.error("Upstream API error: {} - {}", response.code(), responseBody);
                throw new BusinessException(response.code(), "上游 API 错误: " + responseBody);
            }

            return responseBody;
        } catch (IOException e) {
            log.error("Failed to forward request to channel {}: {}", channel.getId(), e.getMessage());
            throw new BusinessException(502, "渠道请求失败: " + e.getMessage());
        }
    }

    String forwardClaudeRequest(Channel channel, String requestBody) {
        if (isChannelRateLimited(channel)) {
            throw new BusinessException(429, "渠道请求频率超限，请稍后重试");
        }

        String url = channel.getBaseUrl().replaceAll("/+$", "") + "/v1/messages";
        RequestBody body = RequestBody.create(requestBody, MediaType.parse("application/json"));
        Request.Builder reqBuilder = new Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(body);
        applyChannelAuth(reqBuilder, channel);

        try (okhttp3.Response response = httpClient.newCall(reqBuilder.build()).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("Claude upstream API error: {} - {}", response.code(), responseBody);
                throw new BusinessException(response.code(), "上游 API 错误: " + responseBody);
            }
            return responseBody;
        } catch (IOException e) {
            throw new BusinessException(502, "渠道请求失败: " + e.getMessage());
        }
    }

    String forwardGeminiRequest(Channel channel, String requestBody) {
        if (isChannelRateLimited(channel)) {
            throw new BusinessException(429, "渠道请求频率超限，请稍后重试");
        }

        String model = "default";
        try {
            JsonNode node = objectMapper.readTree(requestBody);
            if (node.has("model")) model = node.get("model").asText();
        } catch (Exception e) {
            log.warn("解析 Gemini 请求 model 失败，使用默认值");
        }

        String url = channel.getBaseUrl().replaceAll("/+$", "")
                + "/v1/models/" + model + ":generateContent?key=" + channel.getApiKey();
        RequestBody body = RequestBody.create(requestBody, MediaType.parse("application/json"));
        Request.Builder reqBuilder = new Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(body);

        try (okhttp3.Response response = httpClient.newCall(reqBuilder.build()).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("Gemini upstream API error: {} - {}", response.code(), responseBody);
                throw new BusinessException(response.code(), "上游 API 错误: " + responseBody);
            }
            return responseBody;
        } catch (IOException e) {
            throw new BusinessException(502, "渠道请求失败: " + e.getMessage());
        }
    }

    // ==================== 流式读取与请求体处理 ====================

    String streamSseResponse(HttpURLConnection conn, HttpServletResponse httpResponse,
                              Predicate<String> usageFilter) throws IOException {
        String lastUsageData = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            var writer = httpResponse.getWriter();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    writer.write("\n");
                } else {
                    writer.write(line);
                    writer.write("\n");
                }
                writer.flush();
                if (line.startsWith("data: ") && !line.equals("data: [DONE]")) {
                    String data = line.substring(6);
                    if (usageFilter != null ? usageFilter.test(data) : data.contains("\"usage\"")) {
                        lastUsageData = data;
                    }
                }
            }
        }
        return lastUsageData;
    }

    String injectStreamOptions(String requestBody, String path) {
        if (!path.contains("/chat/completions")) return requestBody;
        try {
            JsonNode jsonBody = objectMapper.readTree(requestBody);
            if (jsonBody.isObject()) {
                ObjectNode streamOptions = objectMapper.createObjectNode();
                streamOptions.put("include_usage", true);
                ((ObjectNode) jsonBody).set("stream_options", streamOptions);
                return objectMapper.writeValueAsString(jsonBody);
            }
        } catch (Exception e) {
            log.warn("注入 stream_options 失败，使用原始请求体");
        }
        return requestBody;
    }

    // ==================== 使用记录 ====================

    void recordUsage(Token token, Channel channel, String model,
                     String response, long duration, HttpServletRequest httpRequest, String path) {
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;
        int cachedTokens = 0;
        int cacheCreationTokens = 0;
        int cacheReadTokens = 0;

        try {
            JsonNode jsonNode = objectMapper.readTree(response);
            JsonNode usage = jsonNode.get("usage");
            if (usage != null) {
                promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : 0;
                completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : 0;
                totalTokens = usage.has("total_tokens") ? usage.get("total_tokens").asInt() : 0;
                JsonNode promptDetails = usage.path("prompt_tokens_details");
                if (!promptDetails.isMissingNode()) {
                    cachedTokens = promptDetails.has("cached_tokens") ? promptDetails.get("cached_tokens").asInt() : 0;
                }
                cacheCreationTokens = usage.has("cache_creation_input_tokens") ? usage.get("cache_creation_input_tokens").asInt() : 0;
                cacheReadTokens = usage.has("cache_read_input_tokens") ? usage.get("cache_read_input_tokens").asInt() : 0;
                if (cachedTokens == 0 && cacheReadTokens > 0) {
                    cachedTokens = cacheReadTokens;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse usage from response");
        }

        BigDecimal creditCost = usageLogService.calculateCreditCost(model, promptTokens, completionTokens, cachedTokens);

        UsageLog usageLog = UsageLog.builder()
                .tokenId(token.getId())
                .channelId(channel.getId())
                .model(model)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .promptTokensCacheHit(cachedTokens)
                .cachedTokensCacheCreation(cacheCreationTokens)
                .cachedTokensCacheRead(cacheReadTokens)
                .creditCost(creditCost)
                .ip(getClientIp(httpRequest))
                .duration(duration)
                .requestPath(path)
                .build();

        usageLogService.recordUsageAndQuotas(usageLog, token.getId(), channel.getId(), totalTokens, token.getUserId());
    }

    void recordStreamUsage(Token token, Channel channel, String model,
                            int promptTokens, int completionTokens,
                            int cachedTokens, int cacheCreationTokens, int cacheReadTokens,
                            long duration, HttpServletRequest httpRequest, String path) {
        int totalTokens = promptTokens + completionTokens;
        BigDecimal creditCost = usageLogService.calculateCreditCost(model, promptTokens, completionTokens, cachedTokens);
        UsageLog usageLog = UsageLog.builder()
                .tokenId(token.getId()).channelId(channel.getId()).model(model)
                .promptTokens(promptTokens).completionTokens(completionTokens).totalTokens(totalTokens)
                .promptTokensCacheHit(cachedTokens)
                .cachedTokensCacheCreation(cacheCreationTokens)
                .cachedTokensCacheRead(cacheReadTokens)
                .creditCost(creditCost).ip(getClientIp(httpRequest)).duration(duration)
                .requestPath(path).build();
        usageLogService.recordUsageAndQuotas(usageLog, token.getId(), channel.getId(), totalTokens, token.getUserId());
    }

    String getClientIp(HttpServletRequest request) {
        if (request == null) return "";
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
