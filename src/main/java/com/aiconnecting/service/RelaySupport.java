package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.common.MediaDurationLimits;
import com.aiconnecting.common.OpenAiUrlUtils;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.entity.ModelGroup;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private final ModelGroupService modelGroupService;
    private final UserService userService;
    private final VideoTaskUsageLogService videoTaskUsageLogService;

    @Autowired
    private CacheInvalidationService cacheInvalidationService;

    @Autowired(required = false)
    RateLimitService rateLimitService;

    @Autowired(required = false)
    private okhttp3.Interceptor tracingInterceptor;

    @Autowired
    private ObjectProvider<ModelHealthTracker> modelHealthTrackerProvider;

    private OkHttpClient httpClient;
    final ObjectMapper objectMapper = new ObjectMapper();

    private final ConcurrentHashMap<String, CachedValue> modelNameCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedAllowedModels> allowedModelsCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedModelConfig> modelConfigCache = new ConcurrentHashMap<>();

    static final int MAX_RETRIES = 3;
    private static final Set<String> AGNES_HOST_SUFFIXES = Set.of("agnes-ai.cn", "agnes-ai.com");
    private static final long MODEL_CACHE_TTL_MS = 2 * 60 * 1000L;
    private static final long ALLOWED_MODELS_CACHE_TTL_MS = 2 * 60 * 1000L;
    private static final ExecutorService SSE_WRITE_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "sse-request-writer");
        thread.setDaemon(true);
        return thread;
    });

    record RelayContext(Token token, String channelModelId, Integer userLevel, User user, ModelConfig modelConfig) {}

    /** 媒体请求预扣结果：cost=计算出的积分消耗，deducted=是否实际扣减（admin 余额不足时放行但不扣减） */
    record MediaCharge(BigDecimal cost, boolean deducted) {}

    /**
     * 视频预扣结果附带计费所用的分辨率档位、声明时长与档位单价，供任务终态结算时
     * 按提交时的单价（而非结算时可能已变化的当前价格）重新计算实际费用
     */
    record VideoChargeInfo(MediaCharge charge, String size, int durationSeconds, BigDecimal unitPrice) {}

    record MediaParams(String size, int n, int durationSeconds) {}

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

    private record CachedModelConfig(ModelConfig config, long cachedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > MODEL_CACHE_TTL_MS;
        }
    }

    @jakarta.annotation.PostConstruct
    void initHttpClient() {
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
        return validateAndPrepare(tokenKey, model, "text");
    }

    /**
     * 转发前统一预检：Token 有效性、账号状态、余额、模型权限/状态/类型与端点匹配、限流
     *
     * @param endpointType 端点类别: text=文本类端点, image=图片端点, video=视频端点, audio=音频端点
     */
    RelayContext validateAndPrepare(String tokenKey, String model, String endpointType) {
        return validateAndPrepare(tokenKey, model, model, endpointType);
    }

    /**
     * @param permissionModel 用于 Token allowed_models 权限校验的模型名（模型组请求传组名）
     * @param routingModel    用于模型配置查找/状态校验/渠道路由解析的模型名（模型组请求传实际成员模型名）
     *                        单模型请求两者相同，与原逻辑完全一致；仅供 {@link ModelGroupFailoverExecutor} 复用。
     */
    RelayContext validateAndPrepare(String tokenKey, String permissionModel, String routingModel, String endpointType) {
        Token token = tokenService.validateTokenKey(tokenKey);
        if (token.getQuota() != -1 && token.getUsedQuota() >= token.getQuota()) {
            throw new BusinessException(429, "Token 额度已用完", "Token quota exhausted");
        }
        User tokenUser = userService.getByIdCached(token.getUserId());
        if (tokenUser.getStatus() == null || tokenUser.getStatus() != 1) {
            throw new BusinessException(403, "账号已被禁用", "Account disabled");
        }
        boolean isAdmin = "admin".equals(tokenUser.getRole());
        if (!isAdmin && tokenUser.getCredits() != null && tokenUser.getCredits().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(402, "用户积分不足，请先充值", "Insufficient credits, please recharge");
        }
        checkModelPermission(token, permissionModel);

        ModelConfig config = findModelConfigCached(routingModel);
        if (config != null) {
            if (config.getStatus() == null || config.getStatus() != 1) {
                throw new BusinessException(403, "模型已禁用: " + routingModel, "Model disabled: " + routingModel);
            }
            if (Boolean.TRUE.equals(config.getAdminOnly()) && !isAdmin) {
                throw new BusinessException(403, "该模型仅限管理员使用: " + routingModel,
                        "This model is admin-only: " + routingModel);
            }
            if (!isAdmin && !com.aiconnecting.common.LevelUtils.isAllowed(config.getSupportedLevels(), tokenUser.getLevel())) {
                throw new BusinessException(404, "模型不存在: " + routingModel, "Model not found: " + routingModel);
            }
        } else {
            ModelGroup group = modelGroupService.findByName(routingModel).filter(g -> Boolean.TRUE.equals(g.getEnabled())).orElse(null);
            if (group == null || (!isAdmin && !com.aiconnecting.common.LevelUtils.isAllowed(group.getSupportedLevels(), tokenUser.getLevel()))) {
                throw new BusinessException(404, "模型不存在: " + routingModel, "Model not found: " + routingModel);
            }
        }
        checkEndpointTypeMatch(routingModel, config, endpointType);

        String channelModelId = resolveToChannelModelId(routingModel);
        if (rateLimitService != null) {
            rateLimitService.checkTokenRateLimit(token.getId(), token.getRateLimit());
        }
        return new RelayContext(token, channelModelId, tokenUser.getLevel(), tokenUser, config);
    }

    /**
     * 模型组请求专用预检：Token 有效性、账号状态、余额、组权限、限流；不涉及具体成员模型的
     * 状态/类型校验与渠道路由解析（由 {@link ModelGroupFailoverExecutor} 按选中的成员模型分别处理），
     * 返回的 RelayContext 中 channelModelId/modelConfig 均为 null，调用方不得依赖这两个字段
     */
    RelayContext prepareGroupContext(String tokenKey, String groupName) {
        Token token = tokenService.validateTokenKey(tokenKey);
        if (token.getQuota() != -1 && token.getUsedQuota() >= token.getQuota()) {
            throw new BusinessException(429, "Token 额度已用完", "Token quota exhausted");
        }
        User tokenUser = userService.getByIdCached(token.getUserId());
        if (tokenUser.getStatus() == null || tokenUser.getStatus() != 1) {
            throw new BusinessException(403, "账号已被禁用", "Account disabled");
        }
        boolean isAdmin = "admin".equals(tokenUser.getRole());
        if (!isAdmin && tokenUser.getCredits() != null && tokenUser.getCredits().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(402, "用户积分不足，请先充值", "Insufficient credits, please recharge");
        }
        checkModelPermission(token, groupName);
        if (rateLimitService != null) {
            rateLimitService.checkTokenRateLimit(token.getId(), token.getRateLimit());
        }
        return new RelayContext(token, null, tokenUser.getLevel(), tokenUser, null);
    }

    private static final Set<String> MEDIA_ENDPOINT_TYPES = Set.of("image", "video", "audio");

    /**
     * 端点类别与模型类型必须匹配：图片端点只接受 image 模型、视频端点只接受 video 模型、
     * 音频端点只接受 audio 模型，文本类端点拒绝媒体类模型，避免走错计费逻辑导致零计费或错计费
     */
    private void checkEndpointTypeMatch(String model, ModelConfig config, String endpointType) {
        String modelType = config != null ? config.getType() : null;
        if (MEDIA_ENDPOINT_TYPES.contains(endpointType)) {
            if (config == null) {
                throw new BusinessException(400, "模型未配置，无法用于媒体请求: " + model,
                        "Model not configured for media requests: " + model);
            }
            if (!endpointType.equals(modelType)) {
                throw new BusinessException(400, "模型 " + model + " 类型为 " + (modelType != null ? modelType : "text")
                        + "，不能用于 " + endpointType + " 端点",
                        "Model " + model + " of type " + (modelType != null ? modelType : "text")
                                + " cannot be used on the " + endpointType + " endpoint");
            }
        } else if (modelType != null && MEDIA_ENDPOINT_TYPES.contains(modelType)) {
            throw new BusinessException(400, "模型 " + model + " 类型为 " + modelType + "，不能用于文本类端点",
                    "Model " + model + " of type " + modelType + " cannot be used on a text endpoint");
        }
    }

    /**
     * 校验 Token 及所属账号（不涉及模型），供视频状态查询等只读接口使用；
     * 与转发端点一样执行 Token 限流，避免轮询接口绕过限流
     */
    Token validateToken(String tokenKey) {
        Token token = tokenService.validateTokenKey(tokenKey);
        User tokenUser = userService.getByIdCached(token.getUserId());
        if (tokenUser.getStatus() == null || tokenUser.getStatus() != 1) {
            throw new BusinessException(403, "账号已被禁用", "Account disabled");
        }
        if (rateLimitService != null) {
            rateLimitService.checkTokenRateLimit(token.getId(), token.getRateLimit());
        }
        return token;
    }

    void checkModelPermission(Token token, String model) {
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
                throw new BusinessException(403, "该 Token 无权使用模型: " + model,
                        "This token is not allowed to use this model");
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
        long generation = cacheInvalidationService.generation(CacheInvalidationService.MODEL_CONFIG);
        if (!modelConfigService.findByName(model).isEmpty()) {
            CachedValue fresh = new CachedValue(model, System.currentTimeMillis());
            if (cacheInvalidationService.isCurrentGeneration(CacheInvalidationService.MODEL_CONFIG, generation)) {
                modelNameCache.put(cacheKey, fresh);
                removeIfModelGenerationChanged(modelNameCache, cacheKey, fresh, generation);
            }
            return model;
        }
        List<ModelConfig> byDisplayName = modelConfigService.findByDisplayName(model);
        if (!byDisplayName.isEmpty()) {
            String resolved = byDisplayName.get(0).getName();
            CachedValue fresh = new CachedValue(resolved, System.currentTimeMillis());
            if (cacheInvalidationService.isCurrentGeneration(CacheInvalidationService.MODEL_CONFIG, generation)) {
                modelNameCache.put(cacheKey, fresh);
                removeIfModelGenerationChanged(modelNameCache, cacheKey, fresh, generation);
            }
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
        long generation = cacheInvalidationService.generation(CacheInvalidationService.MODEL_CONFIG);
        List<ModelConfig> models = modelConfigService.findByName(modelName);
        if (!models.isEmpty()) {
            String id = String.valueOf(models.get(0).getId());
            CachedValue fresh = new CachedValue(id, System.currentTimeMillis());
            if (cacheInvalidationService.isCurrentGeneration(CacheInvalidationService.MODEL_CONFIG, generation)) {
                modelNameCache.put(cacheKey, fresh);
                removeIfModelGenerationChanged(modelNameCache, cacheKey, fresh, generation);
            }
            return id;
        }
        return modelName;
    }

    public void clearModelNameCache() {
        modelNameCache.clear();
        modelConfigCache.clear();
    }

    @EventListener
    public void onCacheInvalidation(CacheInvalidationService.CacheInvalidationEvent event) {
        if (CacheInvalidationService.MODEL_CONFIG.equals(event.route())) {
            clearModelNameCache();
        }
    }

    /**
     * 按模型名查找模型配置（带 2 分钟缓存，未配置时缓存 null）
     */
    ModelConfig findModelConfigCached(String model) {
        if (model == null || model.isEmpty()) return null;
        CachedModelConfig cached = modelConfigCache.get(model);
        if (cached != null && !cached.isExpired()) {
            return cached.config();
        }
        long generation = cacheInvalidationService.generation(CacheInvalidationService.MODEL_CONFIG);
        List<ModelConfig> models = modelConfigService.findByName(model);
        ModelConfig config = models.isEmpty() ? null : models.get(0);
        CachedModelConfig fresh = new CachedModelConfig(config, System.currentTimeMillis());
        if (cacheInvalidationService.isCurrentGeneration(CacheInvalidationService.MODEL_CONFIG, generation)) {
            modelConfigCache.put(model, fresh);
            if (!cacheInvalidationService.isCurrentGeneration(CacheInvalidationService.MODEL_CONFIG, generation)) {
                modelConfigCache.remove(model, fresh);
            }
        }
        return config;
    }

    private void removeIfModelGenerationChanged(ConcurrentHashMap<String, CachedValue> cache,
                                                String key, CachedValue value, long generation) {
        if (!cacheInvalidationService.isCurrentGeneration(CacheInvalidationService.MODEL_CONFIG, generation)) {
            cache.remove(key, value);
        }
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

    /**
     * 单模型直连路径的失败记录：按 {@link FailureClassifier} 分类互斥地写入——RATE_LIMIT/QUOTA/
     * MODEL_NOT_FOUND 只写模型级冷却（{@link ModelHealthTracker}，仅限该 渠道+模型 组合），不再
     * 触发渠道级熔断（避免同渠道下的其它模型被一起限流）；CHANNEL 类失败仍由调用方按原有方式写入
     * 渠道级熔断（{@code channelFailureRecorder}，保留调用方原有的 ErrorCategory 判定逻辑不变）；
     * FAST_FAIL 两者都不写。modelConfigId 缺失（如模型未配置）时，429/配额/模型不存在类失败
     * 退化为调用方原有的渠道级记录方式，避免静默丢失该次失败信号。
     */
    void dispatchRelayFailure(Long channelId, Long modelConfigId, BusinessException error,
                               Runnable channelFailureRecorder) {
        FailureClassifier.Classification classification = FailureClassifier.classify(error);
        switch (classification.kind()) {
            case RATE_LIMIT, QUOTA, MODEL_NOT_FOUND -> {
                ModelHealthTracker tracker = modelHealthTrackerProvider.getIfAvailable();
                if (tracker != null && modelConfigId != null) {
                    ModelHealthTracker.FailureType type = switch (classification.kind()) {
                        case RATE_LIMIT -> ModelHealthTracker.FailureType.RATE_LIMIT;
                        case QUOTA -> ModelHealthTracker.FailureType.QUOTA;
                        default -> ModelHealthTracker.FailureType.MODEL_NOT_FOUND;
                    };
                    tracker.recordFailure(channelId, modelConfigId, type, classification.retryAfterSeconds());
                } else {
                    channelFailureRecorder.run();
                }
            }
            case CHANNEL -> channelFailureRecorder.run();
            case FAST_FAIL -> { }
        }
    }

    boolean isClaudeTypeChannel(Channel channel) {
        return "claude".equalsIgnoreCase(channel.getType()) || "anthropic".equalsIgnoreCase(channel.getType());
    }

    boolean isGeminiTypeChannel(Channel channel) {
        return "gemini".equalsIgnoreCase(channel.getType());
    }

    /** Agnes 图片模型不支持 OpenAI 的 response_format 参数。 */
    boolean isAgnesTypeChannel(Channel channel) {
        if ("agnes".equalsIgnoreCase(channel.getType())) {
            return true;
        }
        try {
            String host = URI.create(channel.getBaseUrl()).getHost();
            return isAgnesHost(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static boolean isAgnesHost(String host) {
        if (host == null) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return AGNES_HOST_SUFFIXES.stream()
                .anyMatch(suffix -> normalizedHost.equals(suffix)
                        || normalizedHost.endsWith("." + suffix));
    }

    /** Agnes 视频任务只能使用创建响应中的 video_id 通过 /agnesapi 查询。 */
    String videoStatusPath(Channel channel, String videoId, String model) {
        if (!isAgnesTypeChannel(channel)) {
            return "/v1/videos/" + videoId;
        }
        if (model == null || model.isBlank()) {
            throw new BusinessException(500, "Agnes 视频任务缺少模型名称", "Agnes video task is missing the model name");
        }
        return "/agnesapi?video_id=" + URLEncoder.encode(videoId, StandardCharsets.UTF_8)
                + "&model_name=" + URLEncoder.encode(model, StandardCharsets.UTF_8);
    }

    String prepareImageRequestBody(Channel channel, String requestBody) {
        if (!isAgnesTypeChannel(channel)) {
            return requestBody;
        }
        try {
            JsonNode body = objectMapper.readTree(requestBody);
            if (!(body instanceof ObjectNode objectBody) || !objectBody.has("response_format")) {
                return requestBody;
            }
            objectBody.remove("response_format");
            return objectMapper.writeValueAsString(objectBody);
        } catch (IOException e) {
            throw new BusinessException(400, "图片请求 JSON 格式无效", "Invalid image request JSON", e);
        }
    }

    // ==================== 连接与认证 ====================

    String maskApiKey(String url) {
        return url.replaceAll("([?&]key=)[^&]*", "$1***");
    }

    /** 将连接、写入、读取及整个 call 都收窄到本次尝试的剩余总预算。 */
    private OkHttpClient boundedReadTimeoutClient(Long readTimeoutMs) {
        if (readTimeoutMs == null || readTimeoutMs <= 0) {
            return httpClient;
        }
        long budget = Math.max(1L, readTimeoutMs);
        return httpClient.newBuilder()
                .connectTimeout(boundedTimeout(budget, httpClient.connectTimeoutMillis()), TimeUnit.MILLISECONDS)
                .writeTimeout(boundedTimeout(budget, httpClient.writeTimeoutMillis()), TimeUnit.MILLISECONDS)
                .readTimeout(boundedTimeout(budget, httpClient.readTimeoutMillis()), TimeUnit.MILLISECONDS)
                .callTimeout(budget, TimeUnit.MILLISECONDS)
                .build();
    }

    private long boundedTimeout(long budget, long configuredMs) {
        return configuredMs > 0 ? Math.min(budget, configuredMs) : budget;
    }

    HttpURLConnection createSseConnection(Channel channel, String path, String requestBody) throws IOException {
        return createSseConnection(channel, path, requestBody, null);
    }

    /** @param readTimeoutMs 覆盖默认 120s 读超时（毫秒），语义同 {@link #forwardRequest(Channel, String, String, Long)} */
    HttpURLConnection createSseConnection(Channel channel, String path, String requestBody, Long readTimeoutMs) throws IOException {
        captureChannelModel(requestBody);
        String url = upstreamUrl(channel.getBaseUrl(), path);
        log.info("流式请求: url={}, channel={}", maskApiKey(url), channel.getId());
        java.net.URL urlObj = new java.net.URL(url);
        HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
        try {
            conn.setRequestMethod("POST");
            long budget = readTimeoutMs != null && readTimeoutMs > 0 ? readTimeoutMs : 120_000L;
            conn.setConnectTimeout((int) Math.max(1L, Math.min(budget, 15_000L)));
            conn.setReadTimeout(readTimeoutMs != null && readTimeoutMs > 0
                    ? (int) Math.min(readTimeoutMs, 120_000L) : 120000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Connection", "close");
            applyChannelAuthToConnection(conn, channel);
            byte[] requestBytes = requestBody.getBytes(StandardCharsets.UTF_8);
            // Disable HttpURLConnection's request buffering so the bounded phase below covers the
            // actual socket write, rather than only a copy into its internal buffer.
            conn.setFixedLengthStreamingMode(requestBytes.length);
            long configuredWriteTimeout = httpClient != null ? httpClient.writeTimeoutMillis() : 30_000L;
            long writeTimeoutMs = boundedTimeout(budget, configuredWriteTimeout);
            writeSseRequestBody(conn, requestBytes, writeTimeoutMs);
        } catch (IOException e) {
            conn.disconnect();
            throw e;
        }
        return conn;
    }

    /**
     * HttpURLConnection has no public write-timeout API. Run only the finite request-body write on a
     * deadline-bound worker; on timeout disconnecting the connection closes the socket while leaving
     * the successful response streaming/read path on the caller thread.
     */
    private void writeSseRequestBody(HttpURLConnection conn, byte[] body, long timeoutMs) throws IOException {
        Future<?> write = SSE_WRITE_EXECUTOR.submit(() -> {
            try {
                var output = conn.getOutputStream();
                output.write(body);
                output.flush();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        try {
            write.get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            conn.disconnect();
            write.cancel(true);
            SocketTimeoutException timeout = new SocketTimeoutException(
                    "SSE request body write timed out after " + timeoutMs + " ms");
            timeout.initCause(e);
            throw timeout;
        } catch (InterruptedException e) {
            conn.disconnect();
            write.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("SSE request body write interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof java.io.UncheckedIOException io) throw io.getCause();
            throw new IOException("SSE request body write failed", cause);
        }
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
        return forwardRequest(channel, path, requestBody, null);
    }

    /**
     * @param readTimeoutMs 覆盖默认 120s 读超时（毫秒），供模型组故障转移按剩余总耗时预算收窄单次尝试的
     *                       超时使用，避免固定 120s 超时叠加多次尝试导致总耗时远超预算；为 null 时使用默认超时
     */
    String forwardRequest(Channel channel, String path, String requestBody, Long readTimeoutMs) {
        captureChannelModel(requestBody);
        String url = upstreamUrl(channel.getBaseUrl(), path);

        RequestBody body = RequestBody.create(requestBody, MediaType.parse("application/json"));
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(body);
        applyChannelAuth(requestBuilder, channel);
        Request request = requestBuilder.build();

        OkHttpClient client = boundedReadTimeoutClient(readTimeoutMs);
        try (okhttp3.Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.error("Upstream API error: {} - {}", response.code(), responseBody);
                FailureLogContext.setChannelError(response.code(), responseBody);
                throw BusinessException.upstream(response.code(), "上游 API 错误: " + responseBody,
                        "Upstream API error: " + responseBody, responseBody,
                        parseRetryAfterSeconds(response, responseBody));
            }

            return responseBody;
        } catch (SocketTimeoutException e) {
            log.error("Timed out forwarding request to channel {}: {}", channel.getId(), e.getMessage());
            throw new BusinessException(504, "渠道请求超时: " + e.getMessage(),
                    "Channel request timed out: " + e.getMessage(), e, null, null, true);
        } catch (IOException e) {
            log.error("Failed to forward request to channel {}: {}", channel.getId(), e.getMessage());
            throw new BusinessException(502, "渠道请求失败: " + e.getMessage(),
                    "Channel request failed: " + e.getMessage(), e, null, null, true);
        }
    }

    private String upstreamUrl(String baseUrl, String path) {
        return OpenAiUrlUtils.endpointUrl(baseUrl, path);
    }

    /**
     * 以渠道自身凭据向上游转发 GET 请求（供视频任务状态轮询使用），不向客户端暴露渠道信息
     */
    String forwardGetRequest(Channel channel, String path) {
        String url = upstreamUrl(channel.getBaseUrl(), path);

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .get();
        applyChannelAuth(requestBuilder, channel);

        try (okhttp3.Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("Upstream API error: {} - {}", response.code(), responseBody);
                FailureLogContext.setChannelError(response.code(), responseBody);
                throw BusinessException.upstream(response.code(), "上游 API 错误: " + responseBody,
                        "Upstream API error: " + responseBody, responseBody,
                        parseRetryAfterSeconds(response, responseBody));
            }
            return responseBody;
        } catch (SocketTimeoutException e) {
            log.error("Timed out forwarding GET request to channel {}: {}", channel.getId(), e.getMessage());
            throw new BusinessException(504, "渠道请求超时: " + e.getMessage(),
                    "Channel request timed out: " + e.getMessage(), e, null, null, true);
        } catch (IOException e) {
            log.error("Failed to forward GET request to channel {}: {}", channel.getId(), e.getMessage());
            throw new BusinessException(502, "渠道请求失败: " + e.getMessage(),
                    "Channel request failed: " + e.getMessage(), e, null, null, true);
        }
    }

    /** 二进制上游响应：音频等非 JSON 响应按原始字节透传 */
    record BinaryResponse(byte[] body, String contentType) {}

    /**
     * 转发 JSON 请求并以原始字节返回上游响应（供 /v1/audio/speech 等二进制响应端点使用）
     */
    BinaryResponse forwardBinaryRequest(Channel channel, String path, String requestBody) {
        return forwardBinaryRequest(channel, path, requestBody, null);
    }

    /** @param readTimeoutMs 覆盖默认读超时（毫秒），语义同 {@link #forwardRequest(Channel, String, String, Long)} */
    BinaryResponse forwardBinaryRequest(Channel channel, String path, String requestBody, Long readTimeoutMs) {
        captureChannelModel(requestBody);
        String url = upstreamUrl(channel.getBaseUrl(), path);
        RequestBody body = RequestBody.create(requestBody, MediaType.parse("application/json"));
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(body);
        applyChannelAuth(requestBuilder, channel);
        return executeBinary(channel, requestBuilder.build(), MAX_AUDIO_RESPONSE_BYTES, readTimeoutMs);
    }

    /**
     * 转发 multipart/form-data 请求并以原始字节返回上游响应
     * （供 /v1/audio/transcriptions、/v1/audio/translations 文件上传端点使用）
     */
    BinaryResponse forwardMultipartRequest(Channel channel, String path, MultipartBody multipartBody) {
        return forwardMultipartRequest(channel, path, multipartBody, null);
    }

    BinaryResponse forwardMultipartRequest(Channel channel, String path, MultipartBody multipartBody, Long timeoutMs) {
        String url = upstreamUrl(channel.getBaseUrl(), path);
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(multipartBody);
        applyChannelAuth(requestBuilder, channel);
        return executeBinary(channel, requestBuilder.build(), MAX_TRANSCRIPTION_RESPONSE_BYTES, timeoutMs);
    }

    /** TTS 二进制音频响应上限，避免与请求体等副本叠加造成单请求内存峰值过高 */
    static final long MAX_AUDIO_RESPONSE_BYTES = 32L * 1024 * 1024;

    /** 转写/翻译响应仅为 JSON 或纯文本，使用更小的独立上限 */
    static final long MAX_TRANSCRIPTION_RESPONSE_BYTES = 4L * 1024 * 1024;

    private BinaryResponse executeBinary(Channel channel, Request request, long maxResponseBytes, Long readTimeoutMs) {
        OkHttpClient client = boundedReadTimeoutClient(readTimeoutMs);
        try (okhttp3.Response response = client.newCall(request).execute()) {
            byte[] bytes = response.body() != null
                    ? readCapped(response.body().byteStream(), maxResponseBytes)
                    : new byte[0];
            if (!response.isSuccessful()) {
                String error = new String(bytes, StandardCharsets.UTF_8);
                log.error("Upstream API error: {} - {}", response.code(), error);
                FailureLogContext.setChannelError(response.code(), error);
                throw BusinessException.upstream(response.code(), "上游 API 错误: " + error,
                        "Upstream API error: " + error, error, parseRetryAfterSeconds(response, error));
            }
            return new BinaryResponse(bytes, response.header("Content-Type"));
        } catch (SocketTimeoutException e) {
            log.error("Timed out forwarding request to channel {}: {}", channel.getId(), e.getMessage());
            throw new BusinessException(504, "渠道请求超时: " + e.getMessage(),
                    "Channel request timed out: " + e.getMessage(), e, null, null, true);
        } catch (IOException e) {
            log.error("Failed to forward request to channel {}: {}", channel.getId(), e.getMessage());
            throw new BusinessException(502, "渠道请求失败: " + e.getMessage(),
                    "Channel request failed: " + e.getMessage(), e, null, null, true);
        }
    }

    /** 限制大小地读取输入流，超限抛错而不是无限占用内存 */
    private static byte[] readCapped(java.io.InputStream in, long maxBytes) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buf)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new BusinessException(502, "上游响应超出大小限制 (" + maxBytes / (1024 * 1024) + "MB)",
                        "Upstream response exceeds the size limit (" + maxBytes / (1024 * 1024) + "MB)");
            }
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    String forwardClaudeRequest(Channel channel, String requestBody) {
        return forwardClaudeRequest(channel, requestBody, null);
    }

    /** @param readTimeoutMs remaining model-group wall-clock budget, or null for the normal timeout */
    String forwardClaudeRequest(Channel channel, String requestBody, Long readTimeoutMs) {
        captureChannelModel(requestBody);
        if (isChannelRateLimited(channel)) {
            throw new BusinessException(429, "请求过于频繁，请稍后重试", "Too many requests, please try again later");
        }

        String url = channel.getBaseUrl().replaceAll("/+$", "") + "/v1/messages";
        RequestBody body = RequestBody.create(requestBody, MediaType.parse("application/json"));
        Request.Builder reqBuilder = new Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(body);
        applyChannelAuth(reqBuilder, channel);

        OkHttpClient client = boundedReadTimeoutClient(readTimeoutMs);
        try (okhttp3.Response response = client.newCall(reqBuilder.build()).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("Claude upstream API error: {} - {}", response.code(), responseBody);
                FailureLogContext.setChannelError(response.code(), responseBody);
                throw BusinessException.upstream(response.code(), "上游 API 错误: " + responseBody,
                        "Upstream API error: " + responseBody, responseBody,
                        parseRetryAfterSeconds(response, responseBody));
            }
            return responseBody;
        } catch (SocketTimeoutException e) {
            throw new BusinessException(504, "渠道请求超时: " + e.getMessage(),
                    "Channel request timed out: " + e.getMessage(), e, null, null, true);
        } catch (IOException e) {
            throw new BusinessException(502, "渠道请求失败: " + e.getMessage(),
                    "Channel request failed: " + e.getMessage(), e, null, null, true);
        }
    }

    String forwardGeminiRequest(Channel channel, String requestBody) {
        return forwardGeminiRequest(channel, requestBody, null);
    }

    /** @param readTimeoutMs 覆盖默认读超时（毫秒），语义同 {@link #forwardRequest(Channel, String, String, Long)} */
    String forwardGeminiRequest(Channel channel, String requestBody, Long readTimeoutMs) {
        if (isChannelRateLimited(channel)) {
            throw new BusinessException(429, "请求过于频繁，请稍后重试", "Too many requests, please try again later");
        }

        String model = "default";
        try {
            JsonNode node = objectMapper.readTree(requestBody);
            if (node.has("model")) model = node.get("model").asText();
        } catch (Exception e) {
            log.warn("解析 Gemini 请求 model 失败，使用默认值");
        }
        FailureLogContext.setChannelModel(model);

        String url = channel.getBaseUrl().replaceAll("/+$", "")
                + "/v1/models/" + model + ":generateContent";
        RequestBody body = RequestBody.create(requestBody, MediaType.parse("application/json"));
        Request.Builder reqBuilder = new Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Goog-Api-Key", channel.getApiKey())
                .post(body);

        OkHttpClient client = boundedReadTimeoutClient(readTimeoutMs);
        try (okhttp3.Response response = client.newCall(reqBuilder.build()).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("Gemini upstream API error: {} - {}", response.code(), responseBody);
                FailureLogContext.setChannelError(response.code(), responseBody);
                throw BusinessException.upstream(response.code(), "上游 API 错误: " + responseBody,
                        "Upstream API error: " + responseBody, responseBody,
                        parseRetryAfterSeconds(response, responseBody));
            }
            return responseBody;
        } catch (SocketTimeoutException e) {
            throw new BusinessException(504, "渠道请求超时: " + e.getMessage(),
                    "Channel request timed out: " + e.getMessage(), e, null, null, true);
        } catch (IOException e) {
            throw new BusinessException(502, "渠道请求失败: " + e.getMessage(),
                    "Channel request failed: " + e.getMessage(), e, null, null, true);
        }
    }

    // ==================== 流式读取与请求体处理 ====================

    private Long parseRetryAfterSeconds(okhttp3.Response response, String responseBody) {
        return resolveCooldownSeconds(response.header("Retry-After"),
                response.header("X-RateLimit-Reset"), responseBody);
    }

    static Long resolveCooldownSeconds(HttpURLConnection connection, String responseBody) {
        return resolveCooldownSeconds(connection.getHeaderField("Retry-After"),
                connection.getHeaderField("X-RateLimit-Reset"), responseBody);
    }

    static Long resolveCooldownSeconds(String retryAfter, String rateLimitReset, String responseBody) {
        Long seconds = parseRetryAfterValue(retryAfter);
        if (seconds != null) return seconds;
        seconds = parseEpochSeconds(rateLimitReset);
        if (seconds != null) return seconds;
        return parseJsonResetHint(responseBody);
    }

    private void captureChannelModel(String requestBody) {
        if (requestBody == null || requestBody.isBlank()) return;
        try {
            JsonNode body = objectMapper.readTree(requestBody);
            if (body.hasNonNull("model")) {
                FailureLogContext.setChannelModel(body.get("model").asText());
            }
        } catch (Exception ignored) {
            // Request validation owns malformed JSON handling; failure logging must never affect it.
        }
    }

    private static Long parseRetryAfterValue(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Math.max(0L, Long.parseLong(value.trim()));
        } catch (NumberFormatException ignored) {
            try {
                Instant at = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Math.max(0L, Duration.between(Instant.now(), at).getSeconds());
            } catch (Exception ignoredDate) {
                return null;
            }
        }
    }

    private static Long parseEpochSeconds(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Math.max(0L, Long.parseLong(value.trim()) - Instant.now().getEpochSecond());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Long parseJsonResetHint(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode root = new ObjectMapper().readTree(body);
            JsonNode error = root.path("error");
            if (error.isObject()) {
                Long hint = parseJsonResetFields(error);
                if (hint != null) return hint;
            }
            return parseJsonResetFields(root);
        } catch (Exception ignored) {
            // Invalid or unrelated JSON has no usable cooldown hint.
        }
        return null;
    }

    private static Long parseJsonResetFields(JsonNode source) {
        for (String field : List.of("retry_after", "retry_after_seconds")) {
            Long value = parseLongNode(source.path(field));
            if (value != null) return Math.max(0L, value);
        }
        for (String field : List.of("reset", "reset_at")) {
            Long value = parseLongNode(source.path(field));
            if (value != null) return Math.max(0L, value - Instant.now().getEpochSecond());
        }
        return null;
    }

    private static Long parseLongNode(JsonNode value) {
        if (value.isIntegralNumber() && value.canConvertToLong()) return value.asLong();
        if (value.isTextual()) {
            try {
                return Long.parseLong(value.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** SSE 透传结果：lastUsageData=最后一条包含 usage 的数据行；bytesWritten=是否确有任何数据写出过 */
    record SseStreamResult(String lastUsageData, boolean bytesWritten) {}

    static final class SseStreamingException extends IOException {
        private final SseStreamResult partialResult;

        SseStreamingException(IOException cause, SseStreamResult partialResult) {
            super(cause.getMessage(), cause);
            this.partialResult = partialResult;
        }

        SseStreamResult partialResult() {
            return partialResult;
        }
    }

    String streamSseResponse(HttpURLConnection conn, HttpServletResponse httpResponse,
                              Predicate<String> usageFilter) throws IOException {
        return streamSseResponseTracked(conn, httpResponse, usageFilter).lastUsageData();
    }

    /**
     * 与 {@link #streamSseResponse} 逻辑完全一致，额外返回是否确有数据写出：
     * 上游返回 200 后立即 EOF（一行都没有）时，客户端连接虽已建立但从未收到任何内容，
     * 调用方（模型组故障转移）据此判断此时仍可安全切换成员，而非当作已成功、零 usage 结束
     */
    RelaySupport.SseStreamResult streamSseResponseTracked(HttpURLConnection conn, HttpServletResponse httpResponse,
                                                           Predicate<String> usageFilter) throws IOException {
        String lastUsageData = null;
        boolean bytesWritten = false;
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
                bytesWritten = true;
                if (line.startsWith("data: ") && !line.equals("data: [DONE]")) {
                    String data = line.substring(6);
                    if (usageFilter != null ? usageFilter.test(data) : data.contains("\"usage\"")) {
                        lastUsageData = data;
                    }
                }
            }
        } catch (IOException e) {
            throw new SseStreamingException(e, new SseStreamResult(lastUsageData, bytesWritten));
        }
        return new SseStreamResult(lastUsageData, bytesWritten);
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

    // ==================== 媒体请求预扣计费 ====================

    /**
     * 媒体请求预扣：价格在调用上游前已完全可知，先原子校验余额并扣减积分，再放行请求；
     * 上游失败时由调用方通过 {@link #refundMediaCharge} 退回。
     * 图片 = 分辨率档位单价 × 张数；视频 = 分辨率档位单价 × 时长（秒）。
     * 分辨率/时长缺失或无法识别时直接拒绝（400），不允许按低档位漏计费。
     */
    MediaCharge prepareMediaCharge(RelayContext ctx, String requestBody) {
        MediaParams params = parseMediaParams(requestBody);
        return finishPrepareMediaCharge(ctx, ctx.modelConfig(), params.size(), params.n(), params.durationSeconds());
    }

    /**
     * 视频专用预扣：与 {@link #prepareMediaCharge} 计费逻辑一致，额外返回计费所用的
     * 分辨率档位与声明时长，供视频任务终态（failed/completed）结算时重新计算实际费用
     */
    VideoChargeInfo prepareVideoCharge(RelayContext ctx, String requestBody) {
        MediaParams params = parseMediaParams(requestBody);
        BigDecimal creditCost = usageLogService.calculateVideoCreditCost(ctx.modelConfig(), params.size(), params.durationSeconds());
        BigDecimal unitPrice = usageLogService.resolveVideoUnitPrice(ctx.modelConfig(), params.size());
        MediaCharge charge = chargeMediaCredits(ctx, creditCost);
        return new VideoChargeInfo(charge, params.size(), params.durationSeconds(), unitPrice);
    }

    MediaParams parseMediaParams(String requestBody) {
        String size = null;
        int n = 1;
        int durationSeconds = 0;
        try {
            JsonNode body = objectMapper.readTree(requestBody);
            if (body.hasNonNull("size")) {
                size = body.get("size").asText();
            } else if (body.hasNonNull("resolution")) {
                size = body.get("resolution").asText();
            }
            if (body.hasNonNull("n")) {
                n = body.get("n").asInt(1);
            }
            // duration 与 seconds 各自独立校验；同时出现时必须相等，防止用小值预扣、大值转发绕过按秒计费
            Integer durationField = readPositiveIntSeconds(body, "duration");
            Integer secondsField = readPositiveIntSeconds(body, "seconds");
            if (durationField != null && secondsField != null && !durationField.equals(secondsField)) {
                throw new BusinessException(400, "duration 与 seconds 参数值不一致，请只传其一或保持相等",
                        "duration and seconds differ; provide only one or make them equal");
            }
            if (durationField != null) {
                durationSeconds = durationField;
            } else if (secondsField != null) {
                durationSeconds = secondsField;
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "无法解析媒体请求参数: " + e.getMessage(),
                    "Unable to parse media request parameters: " + e.getMessage());
        }
        return new MediaParams(size, n, durationSeconds);
    }

    /** 解析请求体中的 size/resolution 参数，供图片任务结算时按同一分辨率档位重新计价 */
    String extractMediaSize(String requestBody) {
        try {
            JsonNode body = objectMapper.readTree(requestBody);
            if (body.hasNonNull("size")) {
                return body.get("size").asText();
            } else if (body.hasNonNull("resolution")) {
                return body.get("resolution").asText();
            }
        } catch (Exception e) {
            log.warn("解析媒体请求 size 参数失败: {}", e.getMessage());
        }
        return null;
    }

    /** 解析请求体中的 n 参数（请求图片张数），供图片任务结算时将实际返回张数封顶在请求张数内，缺省/非法时按 1 处理 */
    int extractRequestedCount(String requestBody) {
        try {
            JsonNode body = objectMapper.readTree(requestBody);
            if (body.hasNonNull("n") && body.get("n").isIntegralNumber()) {
                return Math.max(body.get("n").asInt(1), 1);
            }
        } catch (Exception e) {
            log.warn("解析媒体请求 n 参数失败: {}", e.getMessage());
        }
        return 1;
    }

    /** 每个出现的时长字段都必须独立通过正整数和共享上限校验；JSON null 视为未传 */
    private static Integer readPositiveIntSeconds(JsonNode body, String field) {
        if (!body.has(field) || body.get(field).isNull()) {
            return null;
        }
        JsonNode node = body.get(field);
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.asInt() <= 0) {
            throw new BusinessException(400, field + " 参数必须是正整数秒数",
                    field + " must be a positive integer number of seconds");
        }
        if (node.asInt() > MediaDurationLimits.MAX_VIDEO_DURATION_SECONDS) {
            throw new BusinessException(400, field + " 参数不能超过 "
                    + MediaDurationLimits.MAX_VIDEO_DURATION_SECONDS + " 秒",
                    field + " cannot exceed " + MediaDurationLimits.MAX_VIDEO_DURATION_SECONDS + " seconds");
        }
        return node.asInt();
    }

    private MediaCharge finishPrepareMediaCharge(RelayContext ctx, ModelConfig config,
                                                 String size, int n, int durationSeconds) {
        BigDecimal creditCost = switch (config.getType()) {
            case "video" -> usageLogService.calculateVideoCreditCost(config, size, durationSeconds);
            default -> usageLogService.calculateImageCreditCost(config, size, n);
        };
        return chargeMediaCredits(ctx, creditCost);
    }

    /**
     * 原子校验余额并预扣积分（admin 余额不足时放行但不扣减）
     */
    MediaCharge chargeMediaCredits(RelayContext ctx, BigDecimal creditCost) {
        boolean deducted = false;
        if (creditCost.compareTo(BigDecimal.ZERO) > 0) {
            deducted = userService.tryDeductCredits(ctx.token().getUserId(), creditCost);
            if (!deducted && !"admin".equals(ctx.user().getRole())) {
            throw new BusinessException(402,
                    "用户积分不足，本次请求需要 " + creditCost.stripTrailingZeros().toPlainString() + " 积分，请先充值",
                    "Insufficient credits, this request requires " + creditCost.stripTrailingZeros().toPlainString()
                            + " credits, please recharge");
            }
        }
        return new MediaCharge(creditCost, deducted);
    }

    /**
     * 按实际用量结算预扣积分：多退少补（补扣与文本后付费一致，允许透支）。
     * 结算落库失败时保持预扣金额并记录错误，不影响已成功的上游响应。
     *
     * @return 最终计费金额
     */
    BigDecimal settleMediaCharge(RelayContext ctx, MediaCharge charge, BigDecimal actualCost) {
        return settleMediaCharge(ctx.token().getUserId(), charge, actualCost);
    }

    /**
     * 按实际用量结算预扣积分（按 userId），供无完整 {@link RelayContext} 的场景使用
     * （如视频任务轮询接口的终态结算，此时只读接口未加载模型/Token 全量上下文）
     */
    BigDecimal settleMediaCharge(Long userId, MediaCharge charge, BigDecimal actualCost) {
        if (!charge.deducted()) {
            return actualCost;
        }
        BigDecimal diff = actualCost.subtract(charge.cost());
        if (diff.compareTo(BigDecimal.ZERO) == 0) {
            return actualCost;
        }
        try {
            if (diff.compareTo(BigDecimal.ZERO) > 0) {
                userService.deductCreditsSettlement(userId, diff);
            } else {
                userService.refundCredits(userId, diff.negate());
            }
            return actualCost;
        } catch (Exception e) {
            log.error("媒体计费结算失败，保持预扣金额: userId={}, prepaid={}, actual={}",
                    userId, charge.cost(), actualCost, e);
            return charge.cost();
        }
    }

    /**
     * 图片按实际返回张数结算：图片端点上游可能部分成功（如请求 4 张仅返回 1 张），
     * 按实际返回张数 × 同一分辨率档位单价重新计价并多退；实际张数同时封顶在请求张数内，
     * 防止上游异常多返回时被当作额外补扣（预扣金额本就只覆盖请求张数）
     */
    BigDecimal settleImageCharge(RelayContext ctx, MediaCharge charge, String requestBody, int actualCount) {
        String size = extractMediaSize(requestBody);
        int requestedN = extractRequestedCount(requestBody);
        int billedCount = Math.min(Math.max(actualCount, 0), requestedN);
        BigDecimal unitPrice = usageLogService.resolveImageUnitPrice(ctx.modelConfig(), size);
        BigDecimal actualCost = unitPrice.multiply(BigDecimal.valueOf(billedCount));
        return settleMediaCharge(ctx, charge, actualCost);
    }

    /**
     * 上游请求失败时退回预扣的积分
     *
     * @return true=已退回（或无需退回）；false=退款本身失败，已记录待人工补偿告警，
     *         调用方必须向客户端如实反映扣费状态，不得声称已退款
     */
    boolean refundMediaCharge(RelayContext ctx, MediaCharge charge) {
        return refundMediaCharge(ctx.token().getUserId(), charge);
    }

    /**
     * 退回预扣积分（按 userId），供无完整 {@link RelayContext} 的场景使用（如视频任务轮询接口）
     */
    boolean refundMediaCharge(Long userId, MediaCharge charge) {
        if (charge == null || !charge.deducted()) {
            return true;
        }
        try {
            userService.refundCredits(userId, charge.cost());
            return true;
        } catch (Exception e) {
            log.error("MANUAL_COMPENSATION_REQUIRED 退回预扣积分失败，需人工补偿: userId={}, amount={}",
                    userId, charge.cost(), e);
            return false;
        }
    }

    /**
     * 使用日志必须记录实际扣减的金额，而非"本应收取"的名义金额：admin 余额不足被放行、
     * 未实际扣减（{@link MediaCharge#deducted()} 为 false）时记 0，避免日志显示已收费
     * 而实际余额分文未动，造成对账/统计口径不一致
     */
    private static BigDecimal billedCost(MediaCharge charge) {
        return charge.deducted() ? charge.cost() : BigDecimal.ZERO;
    }

    /**
     * 记录已预扣积分的媒体使用日志（不再重复扣减积分）
     *
     * @return 落库后的使用日志 id，供视频任务保存该 id 以便结算阶段回写最终计费金额
     */
    Long recordPrepaidMediaUsage(Token token, Channel channel, String model, MediaCharge charge,
                                 long duration, HttpServletRequest httpRequest, String path) {
        UsageLog usageLog = UsageLog.builder()
                .tokenId(token.getId())
                .channelId(channel.getId())
                .model(model)
                .actualModel(model)
                .promptTokens(0)
                .completionTokens(0)
                .totalTokens(0)
                .creditCost(billedCost(charge))
                .ip(getClientIp(httpRequest))
                .duration(duration)
                .requestPath(path)
                .build();
        return usageLogService.recordPrepaidUsage(usageLog).getId();
    }

    /**
     * 视频任务专用：使用日志落库与 usage_log_id 回链在同一个本地事务内完成，
     * 避免两次独立提交之间的崩溃窗口导致"日志和任务都已存在但关联丢失、结算阶段无法回写最终金额"；
     * 任一环节失败整体回滚（不会留下没有关联的孤立日志），调用方需捕获异常，
     * 保证失败不影响创建响应（任务与预扣积分已在此之前落地，不受影响）
     *
     * @return 落库后的使用日志 id，失败时抛出异常由调用方捕获
     */
    Long recordPrepaidMediaUsageAndLink(Token token, Channel channel, String model, MediaCharge charge,
                                        long duration, HttpServletRequest httpRequest, String path,
                                        Long videoTaskId) {
        UsageLog usageLog = UsageLog.builder()
                .tokenId(token.getId())
                .channelId(channel.getId())
                .model(model)
                .actualModel(model)
                .promptTokens(0)
                .completionTokens(0)
                .totalTokens(0)
                .creditCost(billedCost(charge))
                .ip(getClientIp(httpRequest))
                .duration(duration)
                .requestPath(path)
                .build();
        return videoTaskUsageLogService.recordAndLink(videoTaskId, usageLog);
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

    void recordPassthroughUsage(Token token, Channel channel, String platformModel, String upstreamModel,
                                int promptTokens, int completionTokens, int totalTokens, int cachedTokens,
                                int cacheCreationTokens, int cacheReadTokens, long duration,
                                HttpServletRequest httpRequest, String path) {
        BigDecimal creditCost = usageLogService.calculateCreditCost(
                platformModel, promptTokens, completionTokens, cachedTokens);
        UsageLog usageLog = UsageLog.builder()
                .tokenId(token.getId()).channelId(channel.getId())
                .model(platformModel).actualModel(upstreamModel)
                .promptTokens(promptTokens).completionTokens(completionTokens).totalTokens(totalTokens)
                .promptTokensCacheHit(cachedTokens)
                .cachedTokensCacheCreation(cacheCreationTokens)
                .cachedTokensCacheRead(cacheReadTokens)
                .creditCost(creditCost).ip(getClientIp(httpRequest)).duration(duration)
                .requestPath(path).build();
        usageLogService.recordUsageAndQuotas(
                usageLog, token.getId(), channel.getId(), totalTokens, token.getUserId());
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
