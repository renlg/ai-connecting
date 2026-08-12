package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.common.OpenAiUrlUtils;
import com.aiconnecting.common.SseUtils;
import com.aiconnecting.dto.ChannelRequest;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.repository.ChannelRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChannelService {

    private final ChannelRepository channelRepository;

    @Autowired(required = false)
    private CacheInvalidationService cacheInvalidationService;

    @Autowired(required = false)
    private ChannelHealthPersistenceService channelHealthPersistenceService;

    @Autowired(required = false)
    private okhttp3.Interceptor tracingInterceptor;

    @Autowired(required = false)
    private ModelConfigService modelConfigService;

    private OkHttpClient httpClient;
    private OkHttpClient streamHttpClient;
    private OkHttpClient videoDownloadHttpClient;

    @jakarta.annotation.PostConstruct
    void initHttpClients() {
        OkHttpClient.Builder baseBuilder = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS);
        OkHttpClient.Builder streamBuilder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false);
        // 禁用连接池复用，防止陈旧连接导致请求卡死
        streamBuilder.connectionPool(new okhttp3.ConnectionPool(5, 10, TimeUnit.SECONDS));
        if (tracingInterceptor != null) {
            baseBuilder.addInterceptor(tracingInterceptor);
            streamBuilder.addInterceptor(tracingInterceptor);
        }
        httpClient = baseBuilder.build();
        streamHttpClient = streamBuilder.build();
        // 视频文件下载：禁止自动跟随重定向，避免重定向目标绕过主机校验
        videoDownloadHttpClient = streamHttpClient.newBuilder().followRedirects(false).build();
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.channel.allowed-upstream-hosts:}")
    private String allowedUpstreamHosts;

    private void validateBaseUrlForSsrf(String baseUrl) {
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (Exception e) {
            throw new BusinessException("无效的 URL: " + e.getMessage(), "Invalid URL: " + e.getMessage());
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new BusinessException("仅允许 http/https 协议", "Only http/https protocols are allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BusinessException("无效的 URL: 缺少主机名", "Invalid URL: missing host");
        }
        if (allowedUpstreamHosts != null && !allowedUpstreamHosts.isBlank()) {
            boolean matched = false;
            for (String allowed : allowedUpstreamHosts.split(",")) {
                if (allowed.trim().equalsIgnoreCase(host)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                throw new BusinessException("主机不在白名单中: " + host, "Host is not allowlisted: " + host);
            }
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            throw new BusinessException("无法解析主机地址: " + host, "Unable to resolve host address: " + host);
        }
        for (InetAddress addr : addresses) {
            if (isInternalAddress(addr)) {
                throw new BusinessException("禁止访问内网地址: " + host, "Access to private network addresses is forbidden: " + host);
            }
        }
    }

    private boolean isInternalAddress(InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                || addr.isAnyLocalAddress()) {
            return true;
        }
        if (addr instanceof java.net.Inet6Address inet6) {
            byte[] bytes = inet6.getAddress();
            // Unique-local fc00::/7 and link-local fe80::/10.
            if ((bytes[0] & 0xfe) == 0xfc
                    || (bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xc0) == 0x80) {
                return true;
            }
            if (bytes.length == 16 && bytes[0] == 0x20 && bytes[1] == 0x02) {
                return true;
            }
            boolean allV4Mapped = true;
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) { allV4Mapped = false; break; }
            }
            if (allV4Mapped && bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff) {
                byte[] v4 = new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
                return isInternalIpv4(v4);
            }
            return false;
        }
        byte[] bytes = addr.getAddress();
        if (bytes.length != 4) return true;
        return isInternalIpv4(bytes);
    }

    private boolean isInternalIpv4(byte[] bytes) {
        int b0 = bytes[0] & 0xFF;
        int b1 = bytes[1] & 0xFF;
        if (b0 == 10) return true;
        if (b0 == 172 && b1 >= 16 && b1 <= 31) return true;
        if (b0 == 192 && b1 == 168) return true;
        if (b0 == 127) return true;
        if (b0 == 169 && b1 == 254) return true;
        if (b0 == 100 && b1 >= 64 && b1 <= 127) return true;
        return false;
    }

    public List<Channel> listAll() {
        return channelRepository.findAllOrderByCreatedAtDesc();
    }

    /**
     * 按名称模糊搜索 + 按模型多选过滤（渠道支持所选模型中的任意一个即匹配）
     */
    public List<Channel> listAll(String name, List<String> modelIds) {
        List<Channel> channels = (name != null && !name.isBlank())
                ? channelRepository.searchByName(name.trim())
                : channelRepository.findAllOrderByCreatedAtDesc();
        if (modelIds != null && !modelIds.isEmpty()) {
            channels = channels.stream()
                    .filter(c -> supportsAnyModel(c.getModelIds(), modelIds))
                    .collect(Collectors.toList());
        }
        return channels;
    }

    private boolean supportsAnyModel(String channelModelIds, List<String> modelIds) {
        if (channelModelIds == null || channelModelIds.isBlank()) return false;
        Set<String> supported = Arrays.stream(channelModelIds.split(","))
                .map(String::trim)
                .collect(Collectors.toSet());
        return modelIds.stream().anyMatch(supported::contains);
    }

    public Channel getById(Long id) {
        return channelRepository.findById(id)
                .orElseThrow(() -> new BusinessException("渠道不存在", "Channel not found"));
    }

    private static final Pattern SUPPORTED_LEVELS_PATTERN = Pattern.compile("^[1-5](,[1-5])*$");

    private void validateSupportedLevels(String supportedLevels) {
        if (supportedLevels == null || supportedLevels.isBlank()) {
            return;
        }
        if (!SUPPORTED_LEVELS_PATTERN.matcher(supportedLevels.trim()).matches()) {
            throw new BusinessException("支持等级格式非法，应为 1-5 的逗号分隔数字，例如: 1,2,3", "Invalid supported-level format; use comma-separated numbers from 1 to 5, for example: 1,2,3");
        }
    }

    public Channel create(ChannelRequest request) {
        validateSupportedLevels(request.getSupportedLevels());
        validateModelMapping(null, request.getType(), request.getModelIds(), request.getModelMapping());
        Channel channel = Channel.builder()
                .name(request.getName())
                .type(request.getType())
                .baseUrl(request.getBaseUrl())
                .apiKey(request.getApiKey())
                .modelIds(request.getModelIds())
                .modelMapping(request.getModelMapping())
                .status(request.getStatus() != null ? request.getStatus() : 1)
                .priority(request.getPriority() != null ? request.getPriority() : 0)
                .rateLimit(request.getRateLimit() != null ? request.getRateLimit() : 0)
                .supportedLevels(request.getSupportedLevels() != null && !request.getSupportedLevels().isBlank()
                        ? request.getSupportedLevels() : "1,2,3,4,5")
                .usedQuota(0L)
                .build();
        Channel saved = channelRepository.save(channel);
        publishChannelInvalidation();
        return saved;
    }

    public Channel update(Long id, ChannelRequest request) {
        validateSupportedLevels(request.getSupportedLevels());
        Channel channel = getById(id);
        String proposedType = request.getType() != null ? request.getType() : channel.getType();
        String proposedModelIds = request.getModelIds() != null ? request.getModelIds() : channel.getModelIds();
        String proposedMapping = request.getModelMapping() != null ? request.getModelMapping() : channel.getModelMapping();
        validateModelMapping(id, proposedType, proposedModelIds, proposedMapping);
        if (request.getName() != null) channel.setName(request.getName());
        if (request.getType() != null) channel.setType(request.getType());
        if (request.getBaseUrl() != null) channel.setBaseUrl(request.getBaseUrl());
        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            channel.setApiKey(request.getApiKey());
        }
        if (request.getModelIds() != null) channel.setModelIds(request.getModelIds());
        if (request.getModelMapping() != null || !"custom".equalsIgnoreCase(proposedType)) {
            channel.setModelMapping(request.getModelMapping());
        }
        if (request.getStatus() != null) channel.setStatus(request.getStatus());
        if (request.getPriority() != null) channel.setPriority(request.getPriority());
        if (request.getRateLimit() != null) channel.setRateLimit(request.getRateLimit());
        if (request.getSupportedLevels() != null) channel.setSupportedLevels(request.getSupportedLevels());
        Channel saved = channelRepository.save(channel);
        publishChannelInvalidation();
        return saved;
    }

    private void validateModelMapping(Long channelId, String type, String modelIds, String modelMapping) {
        Set<String> boundIds = splitModelIds(modelIds);
        if ("custom".equalsIgnoreCase(type)) {
            if (boundIds.isEmpty()) {
                throw new BusinessException("透传渠道必须绑定至少一个平台模型",
                        "Passthrough channels must bind at least one platform model");
            }
            if (modelMapping == null || modelMapping.isBlank()) {
                throw new BusinessException("透传渠道必须配置模型映射", "Passthrough channels require a model mapping");
            }
            JsonNode mapping;
            try {
                mapping = objectMapper.readTree(modelMapping);
            } catch (Exception e) {
                throw new BusinessException("模型映射必须是有效的 JSON 对象", "Model mapping must be a valid JSON object");
            }
            if (mapping == null || !mapping.isObject()) {
                throw new BusinessException("模型映射必须是 JSON 对象", "Model mapping must be a JSON object");
            }
            if (modelConfigService == null) {
                throw new BusinessException("模型服务不可用", "Model service unavailable");
            }
            Map<String, ModelConfig> boundModels = new LinkedHashMap<>();
            for (String id : boundIds) {
                ModelConfig config;
                try {
                    config = modelConfigService.getById(Long.valueOf(id));
                } catch (Exception e) {
                    throw new BusinessException("渠道绑定了不存在的模型: " + id, "Channel binds an unknown model: " + id);
                }
                boundModels.put(config.getName(), config);
            }
            boolean containsMediaModel = boundModels.values().stream()
                    .anyMatch(config -> !"text".equals(normalizedModelType(config)));
            if (containsMediaModel) {
                throw new BusinessException("custom 透传渠道仅支持文本模型",
                        "Custom passthrough channels only support text models");
            }
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = mapping.fields();
            Set<String> keys = new java.util.LinkedHashSet<>();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode value = entry.getValue();
                if (!value.isTextual() || value.textValue().isBlank()) {
                    throw new BusinessException("模型映射值必须是非空字符串: " + entry.getKey(),
                            "Model mapping values must be non-empty strings: " + entry.getKey());
                }
                if (modelConfigService.findByName(entry.getKey()).isEmpty()) {
                    throw new BusinessException("模型映射包含不存在的平台模型: " + entry.getKey(),
                            "Model mapping contains an unknown platform model: " + entry.getKey());
                }
                if (!boundModels.containsKey(entry.getKey())) {
                    throw new BusinessException("模型映射与渠道绑定模型不一致: " + entry.getKey(),
                            "Model mapping does not match the channel's bound models: " + entry.getKey());
                }
                keys.add(entry.getKey());
            }
            if (!keys.equals(boundModels.keySet())) {
                throw new BusinessException("每个渠道绑定模型都必须配置上游模型名",
                        "Every bound model must have an upstream model name");
            }
        }

        for (String modelId : boundIds) {
            for (Channel other : findChannelsByModel(modelId)) {
                if (channelId != null && channelId.equals(other.getId())) continue;
                if ("custom".equalsIgnoreCase(type) != "custom".equalsIgnoreCase(other.getType())) {
                    throw new BusinessException("同一模型不能同时绑定透传渠道和普通渠道",
                            "A model cannot be bound to both passthrough and normal channels");
                }
            }
        }
    }

    static String normalizedModelType(ModelConfig config) {
        return config.getType() == null || config.getType().isBlank() ? "text" : config.getType();
    }

    public boolean hasCustomChannelForModel(String modelId) {
        return findChannelsByModel(modelId).stream()
                .anyMatch(channel -> "custom".equalsIgnoreCase(channel.getType()));
    }

    private List<Channel> findChannelsByModel(String modelId) {
        return channelRepository.findChannelsByModel("%," + modelId + ",%");
    }

    private Set<String> splitModelIds(String modelIds) {
        if (modelIds == null || modelIds.isBlank()) return Set.of();
        return Arrays.stream(modelIds.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    public boolean isPassthroughOnlyModel(String modelId) {
        List<Channel> channels = findChannelsByModel(modelId);
        return !channels.isEmpty() && channels.stream()
                .allMatch(channel -> "custom".equalsIgnoreCase(channel.getType()));
    }

    public void delete(Long id) {
        if (!channelRepository.existsById(id)) {
            throw new BusinessException("渠道不存在", "Channel not found");
        }
        channelRepository.deleteById(id);
        channelHealthPersistenceService.deleteByChannelIdAsync(id);
        publishChannelInvalidation();
    }

    public void updateStatus(Long id, Integer status) {
        Channel channel = getById(id);
        channel.setStatus(status);
        channelRepository.save(channel);
        publishChannelInvalidation();
    }

    /**
     * 禁用渠道（自动禁用或手动操作）
     */
    public void disableChannel(Long id) {
        Channel channel = getById(id);
        if (channel.getStatus() != 0) {
            channel.setStatus(0);
            channelRepository.save(channel);
            publishChannelInvalidation();
            log.warn("渠道 {} ({}) 已被禁用", id, channel.getName());
        }
    }

    /**
     * 原子增加渠道已用额度
     */
    public void addUsedQuota(Long channelId, long quota) {
        if (quota > 0) {
            channelRepository.addUsedQuota(channelId, quota);
        }
    }

    private void publishChannelInvalidation() {
        if (cacheInvalidationService != null) {
            cacheInvalidationService.publish(CacheInvalidationService.CHANNEL_LIST);
        }
    }

    /**
     * 根据模型ID获取可用的渠道列表 (按优先级排序)
     */
    public List<Channel> getActiveChannelsByModel(String modelId) {
        String modelIdPattern = "%," + modelId + ",%";
        return channelRepository.findActiveChannelsByModel(modelIdPattern);
    }

    /**
     * 测试渠道连通性
     */
    public boolean testChannel(Long id) {
        Channel channel = getById(id);
        return channel.getBaseUrl() != null && !channel.getBaseUrl().isEmpty()
                && channel.getApiKey() != null && !channel.getApiKey().isEmpty();
    }

    /**
     * 将客户端传入的模型名解析为上游模型名：先按 name 精确匹配，
     * 再按 display_name 匹配并取其 name，否则原样透传。
     * 与 RelaySupport#resolveModelName 保持一致的解析规则，供测试类接口复用。
     */
    private String resolveTestModelName(String model) {
        if (model == null || model.isEmpty() || modelConfigService == null) {
            return model;
        }
        if (!modelConfigService.findByName(model).isEmpty()) {
            return model;
        }
        List<com.aiconnecting.entity.ModelConfig> byDisplayName = modelConfigService.findByDisplayName(model);
        if (!byDisplayName.isEmpty()) {
            return byDisplayName.get(0).getName();
        }
        return model;
    }

    /**
     * 测试渠道聊天功能 - 发送一条消息并返回响应
     */
    public Map<String, Object> testChat(String baseUrl, String apiKey, String type, String model, String message) {
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new BusinessException("请先填写 Base URL 和 API Key", "Please provide the Base URL and API key first");
        }
        if (model == null || model.isBlank()) {
            throw new BusinessException("请选择模型", "Please select a model");
        }
        model = resolveTestModelName(model);

        long startTime = System.currentTimeMillis();

        try {
            boolean isClaude = "claude".equalsIgnoreCase(type) || "anthropic".equalsIgnoreCase(type);
            return doTestChat(baseUrl, apiKey, model, message, startTime, isClaude);
        } catch (IOException e) {
            log.error("渠道测试请求失败: {}", e.getMessage());
            throw new BusinessException("连接上游失败: " + e.getMessage(), "Failed to connect to upstream: " + e.getMessage());
        }
    }

    /**
     * 直接使用表单中的渠道配置测试媒体模型。该路径不参与路由和计费，仅供管理员在保存渠道前验证上游。
     * 图片、视频保持上游 JSON 结构；音频二进制编码为 data URL，方便管理页直接播放。
     */
    public Map<String, Object> testMedia(Map<String, String> request) {
        String baseUrl = requireTestValue(request, "baseUrl", "请先填写 Base URL 和 API Key", "Please provide the Base URL and API key first");
        String apiKey = requireTestValue(request, "apiKey", "请先填写 Base URL 和 API Key", "Please provide the Base URL and API key first");
        String channelType = request.get("type");
        String modelType = requireTestValue(request, "modelType", "缺少模型类型", "Missing model type").toLowerCase();
        String model = resolveTestModelName(requireTestValue(request, "model", "请选择模型", "Please select a model"));
        String prompt = request.getOrDefault("message", "hi");
        validateBaseUrlForSsrf(baseUrl);

        if (!Set.of("image", "video", "audio").contains(modelType)) {
            throw new BusinessException("不支持的媒体模型类型: " + modelType,
                    "Unsupported media model type: " + modelType);
        }

        String path;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        if ("audio".equals(modelType)) {
            path = "/v1/audio/speech";
            body.put("input", prompt);
            body.put("voice", "alloy");
            body.put("response_format", "mp3");
        } else {
            path = "image".equals(modelType) ? "/v1/images/generations" : "/v1/videos";
            body.put("prompt", prompt);
        }

        long startTime = System.currentTimeMillis();
        try {
            Request upstreamRequest = buildTestRequest(baseUrl, apiKey, channelType, path,
                    objectMapper.writeValueAsString(body), false);
            try (Response response = streamHttpClient.newCall(upstreamRequest).execute()) {
                long duration = System.currentTimeMillis() - startTime;
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", response.isSuccessful());
                result.put("statusCode", response.code());
                result.put("duration", duration);
                if ("audio".equals(modelType) && response.isSuccessful()) {
                    byte[] bytes = response.body() != null ? response.body().bytes() : new byte[0];
                    String contentType = response.header("Content-Type", "audio/mpeg");
                    contentType = contentType.split(";", 2)[0];
                    result.put("dataUrl", "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes));
                } else {
                    String responseBody = response.isSuccessful()
                            ? (response.body() != null ? response.body().string() : "")
                            : readCappedResponseString(response, VIDEO_RESPONSE_MAX_BYTES);
                    if ("video".equals(modelType)) {
                        if (response.isSuccessful()) {
                            Object parsed = parseTestJsonOrText(responseBody);
                            String taskId = parsed instanceof JsonNode node
                                    ? textOrNull(node.path("id")) : null;
                            String taskStatus = parsed instanceof JsonNode node
                                    ? textOrNull(node.path("status")) : null;
                            log.info("video create task response model={} taskId={} status={} httpStatus={}",
                                    model, taskId, taskStatus, response.code());
                        } else {
                            log.info("video create task failed model={} httpStatus={} body={}",
                                    model, response.code(), abbreviateTestError(responseBody, 200));
                        }
                    }
                    if (response.isSuccessful()) {
                        result.put("data", parseTestJsonOrText(responseBody));
                    } else {
                        result.put("error", abbreviateTestError(responseBody));
                    }
                }
                return result;
            }
        } catch (IOException e) {
            log.error("渠道媒体测试请求失败: {}", e.getMessage());
            throw new BusinessException("连接上游失败: " + e.getMessage(), "Failed to connect to upstream: " + e.getMessage());
        }
    }

    /** 使用同一渠道配置轮询视频任务；Agnes 必须通过 video_id 查询 /agnesapi。 */
    public Map<String, Object> testVideoStatus(Map<String, String> request) {
        String baseUrl = requireTestValue(request, "baseUrl", "请先填写 Base URL 和 API Key", "Please provide the Base URL and API key first");
        String apiKey = requireTestValue(request, "apiKey", "请先填写 Base URL 和 API Key", "Please provide the Base URL and API key first");
        String channelType = request.get("type");
        String videoId = requireVideoId(request);
        validateBaseUrlForSsrf(baseUrl);
        long startTime = System.currentTimeMillis();
        try {
            Request upstreamRequest = buildTestRequest(baseUrl, apiKey, channelType,
                    videoStatusPath(baseUrl, channelType, videoId, request.get("model")), null, true);
            try (Response response = streamHttpClient.newCall(upstreamRequest).execute()) {
                String responseBody = readCappedResponseString(response, VIDEO_RESPONSE_MAX_BYTES);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", response.isSuccessful());
                result.put("statusCode", response.code());
                result.put("duration", System.currentTimeMillis() - startTime);
                if (response.isSuccessful()) {
                    Object parsed = parseTestJsonOrText(responseBody);
                    result.put("data", parsed);
                    String status = parsed instanceof JsonNode node ? findVideoStatus(node) : null;
                    String videoUrl = parsed instanceof JsonNode node ? findVideoUrl(node) : null;
                    log.info("video status poll videoId={} status={} videoUrl={}",
                            videoId, status, videoUrl != null ? "found" : "null");
                } else {
                    result.put("error", abbreviateTestError(responseBody));
                    log.info("video status poll videoId={} httpStatus={} error", videoId, response.code());
                }
                return result;
            }
        } catch (IOException e) {
            log.error("渠道视频测试轮询失败 videoId={}: {}", videoId, e.getMessage());
            throw new BusinessException("连接上游失败: " + e.getMessage(), "Failed to connect to upstream: " + e.getMessage());
        }
    }

    private static final int VIDEO_CONTENT_MAX_ATTEMPTS = 5;
    private static final long VIDEO_CONTENT_RETRY_DELAY_MS = 5000;
    private static final long VIDEO_DOWNLOAD_MAX_BYTES = 100L * 1024 * 1024;
    private static final long VIDEO_RESPONSE_MAX_BYTES = 10L * 1024 * 1024;
    private static final int VIDEO_ERROR_BODY_MAX_BYTES = 4 * 1024;
    private static final int VIDEO_ERROR_SUMMARY_MAX_CHARS = 200;
    /** 视频 URL 允许下载的跨域产物主机白名单（不是渠道 baseUrl，凭据不随此域名下发）。 */
    private static final Set<String> VIDEO_URL_ALLOWED_HOST_SUFFIXES = Set.of(
            "platform-outputs.agnes-ai.space", "cos-platform-outputs.agnes-ai.cn");
    private static final Set<String> TERMINAL_FAILURE_STATUSES = Set.of("failed", "failure", "cancelled", "canceled", "error");

    /**
     * 获取已完成视频的二进制内容。Agnes 上游没有 /v1/videos/{id}/content 端点，播放地址由
     * /agnesapi 状态响应返回，因此这里使用创建响应中的 video_id 轮询并下载最终 URL；
     * 对非 Agnes 上游，若轮询后仍无 URL，
     * 回退到旧的 GET /v1/videos/{id}/content 端点。
     */
    public TestMediaContent testVideoContent(Map<String, String> request) {
        String baseUrl = requireTestValue(request, "baseUrl", "请先填写 Base URL 和 API Key", "Please provide the Base URL and API key first");
        String apiKey = requireTestValue(request, "apiKey", "请先填写 Base URL 和 API Key", "Please provide the Base URL and API key first");
        String channelType = request.get("type");
        String videoId = requireVideoId(request);
        validateBaseUrlForSsrf(baseUrl);

        String videoUrl = null;
        boolean legacyFallbackRequired = false;
        boolean agnesChannel = isAgnesTypeChannel(baseUrl, channelType);
        String statusPath = videoStatusPath(baseUrl, channelType, videoId, request.get("model"));
        for (int attempt = 1; attempt <= VIDEO_CONTENT_MAX_ATTEMPTS; attempt++) {
            Request statusRequest = buildTestRequest(baseUrl, apiKey, channelType,
                    statusPath, null, true);
            try (Response response = streamHttpClient.newCall(statusRequest).execute()) {
                if (response.isSuccessful()) {
                    String body = readCappedResponseString(response, VIDEO_RESPONSE_MAX_BYTES);
                    JsonNode json;
                    try {
                        json = objectMapper.readTree(body);
                    } catch (IOException parseError) {
                        throw new BusinessException(502, "上游返回了无法解析的状态响应", "Upstream returned an unparseable status response");
                    }
                    if (json == null || !json.isObject()) {
                        throw new BusinessException(502, "上游返回了无法解析的状态响应", "Upstream returned an unparseable status response");
                    }
                    String status = findVideoStatus(json);
                    String terminalFailure = terminalVideoFailureMessage(json);
                    if (terminalFailure != null) {
                        log.warn("video content videoId={} terminal failure status={}", videoId, status);
                        throw new BusinessException(502, terminalFailure, "Upstream video task failed");
                    }
                    videoUrl = findVideoUrl(json);
                } else {
                    String body = readCappedResponseString(response, VIDEO_RESPONSE_MAX_BYTES);
                    if (response.code() == 404 && !agnesChannel) {
                        log.info("video content videoId={} query {} unavailable httpStatus=404; using legacy content endpoint body={}",
                                videoId, statusPath, abbreviateTestError(body, VIDEO_ERROR_SUMMARY_MAX_CHARS));
                        legacyFallbackRequired = true;
                        break;
                    }
                    if (response.code() == 429 || response.code() >= 500) {
                        log.info("video content retry #{} videoId={} query {} transient failure httpStatus={} body={}",
                                attempt, videoId, statusPath, response.code(),
                                abbreviateTestError(body, VIDEO_ERROR_SUMMARY_MAX_CHARS));
                    } else {
                        log.info("video content retry #{} videoId={} query {} permanent failure httpStatus={} body={}",
                                attempt, videoId, statusPath, response.code(), abbreviateTestError(body, VIDEO_ERROR_SUMMARY_MAX_CHARS));
                        throw new BusinessException(response.code(),
                                "上游视频状态查询失败 (HTTP " + response.code() + "): "
                                        + abbreviateTestError(body, VIDEO_ERROR_SUMMARY_MAX_CHARS),
                                "Upstream video status query failed (HTTP " + response.code() + "): "
                                        + abbreviateTestError(body, VIDEO_ERROR_SUMMARY_MAX_CHARS));
                    }
                }
            } catch (IOException e) {
                log.warn("video content retry #{} videoId={} query error: {}", attempt, videoId, e.getMessage());
            }

            log.info("video content retry #{} videoId={} status=queried videoUrl={}",
                    attempt, videoId, videoUrl != null ? "found" : "null");

            if (videoUrl != null && !videoUrl.isBlank()) {
                break;
            }
            if (attempt < VIDEO_CONTENT_MAX_ATTEMPTS) {
                try {
                    Thread.sleep(VIDEO_CONTENT_RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (legacyFallbackRequired || videoUrl == null || videoUrl.isBlank()) {
            if (!agnesChannel) {
                return downloadLegacyVideoContent(baseUrl, apiKey, channelType, videoId);
            }
            log.warn("video content videoId={} exhausted {} attempts without video URL",
                    videoId, VIDEO_CONTENT_MAX_ATTEMPTS);
            throw new BusinessException(504, "上游视频文件尚未就绪，请稍后重试", "Upstream video file is not ready, please try again later");
        }

        return downloadVideoUrl(baseUrl, apiKey, channelType, videoId, videoUrl);
    }

    /** 对非 Agnes 上游回退调用旧的 /v1/videos/{id}/content 端点（凭据始终来自渠道自身，同源）。 */
    private TestMediaContent downloadLegacyVideoContent(String baseUrl, String apiKey, String channelType, String videoId) {
        Request contentRequest = buildTestRequest(baseUrl, apiKey, channelType,
                "/v1/videos/" + videoId + "/content", null, true);
        VideoDownloadTarget target = validateVideoDownloadTarget(baseUrl, contentRequest.url().toString());
        if (!target.attachChannelCredentials()) {
            throw new BusinessException(400, "旧版视频内容地址与渠道不同源", "Legacy video content URL does not share the channel origin");
        }
        try (Response response = downloadClientFor(target).newCall(contentRequest).execute()) {
            if (response.isRedirect()) {
                throw new BusinessException(502, "上游视频内容地址返回了重定向，已拒绝跟随", "Upstream video content URL returned a redirect, which was rejected");
            }
            if (!response.isSuccessful()) {
                String body = readCappedResponseString(response, VIDEO_RESPONSE_MAX_BYTES);
                throw new BusinessException(response.code(),
                        "上游视频内容下载失败: " + abbreviateTestError(body, VIDEO_ERROR_SUMMARY_MAX_CHARS),
                        "Upstream video content download failed: " + abbreviateTestError(body, VIDEO_ERROR_SUMMARY_MAX_CHARS));
            }
            byte[] bytes = readCappedResponseBody(response, VIDEO_DOWNLOAD_MAX_BYTES);
            if (bytes.length == 0) {
                throw new BusinessException(502, "上游返回了空的视频内容", "Upstream returned empty video content");
            }
            String contentType = response.header("Content-Type", "video/mp4").split(";", 2)[0];
            return new TestMediaContent(bytes, contentType);
        } catch (IOException e) {
            log.error("渠道视频测试内容下载失败(legacy) videoId={}: {}", videoId, e.getMessage());
            throw new BusinessException("连接上游失败: " + e.getMessage(), "Failed to connect to upstream: " + e.getMessage());
        }
    }

    /** 下载状态响应指向的视频产物，校验目标主机并按情况决定是否携带渠道凭据。 */
    private TestMediaContent downloadVideoUrl(String baseUrl, String apiKey, String channelType,
                                               String videoId, String videoUrl) {
        VideoDownloadTarget target = validateVideoDownloadTarget(baseUrl, videoUrl);

        try {
            Request.Builder downloadBuilder = new Request.Builder().url(videoUrl).get();
            if (target.attachChannelCredentials()) {
                addAuthHeader(downloadBuilder, apiKey, channelType);
            }
            try (Response response = downloadClientFor(target).newCall(downloadBuilder.build()).execute()) {
                log.info("video content download videoId={} host={} httpStatus={}", videoId, target.host(), response.code());
                if (response.isRedirect()) {
                    throw new BusinessException(502, "上游视频下载地址返回了重定向，已拒绝跟随", "Upstream video download URL returned a redirect, which was rejected");
                }
                if (!response.isSuccessful()) {
                    String body = readCappedResponseString(response, VIDEO_RESPONSE_MAX_BYTES);
                    throw new BusinessException(response.code(),
                            "上游视频内容下载失败: " + abbreviateTestError(body, VIDEO_ERROR_SUMMARY_MAX_CHARS),
                            "Upstream video content download failed: " + abbreviateTestError(body, VIDEO_ERROR_SUMMARY_MAX_CHARS));
                }
                byte[] bytes = readCappedResponseBody(response, VIDEO_DOWNLOAD_MAX_BYTES);
                log.info("video content download videoId={} bytes={}", videoId, bytes.length);
                if (bytes.length == 0) {
                    throw new BusinessException(502, "上游返回了空的视频内容", "Upstream returned empty video content");
                }
                String contentType = response.header("Content-Type", "video/mp4").split(";", 2)[0];
                return new TestMediaContent(bytes, contentType);
            }
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "上游返回了非法的视频下载地址", "Upstream returned an invalid video download URL");
        } catch (IOException e) {
            log.error("渠道视频测试内容下载失败 videoId={}: {}", videoId, e.getMessage());
            throw new BusinessException("连接上游失败: " + e.getMessage(), "Failed to connect to upstream: " + e.getMessage());
        }
    }

    /** 校验下载主机，并明确只有渠道同源下载才能携带渠道凭据。 */
    VideoDownloadTarget validateVideoDownloadTarget(String baseUrl, String videoUrl) {
        try {
            URI parsedUrl = new URI(videoUrl);
            URI baseUri = new URI(baseUrl);
            String scheme = parsedUrl.getScheme();
            String baseScheme = baseUri.getScheme();
            String host = parsedUrl.getHost();
            String baseHost = baseUri.getHost();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
                    || baseScheme == null || (!baseScheme.equalsIgnoreCase("http") && !baseScheme.equalsIgnoreCase("https"))
                    || host == null || baseHost == null) {
                throw new IllegalArgumentException("missing scheme or host");
            }
            if ("https".equalsIgnoreCase(baseScheme) && "http".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("https downgrade is not allowed");
            }
            boolean sameOriginAsChannel = scheme.equalsIgnoreCase(baseScheme)
                    && host.equalsIgnoreCase(baseHost)
                    && effectivePort(parsedUrl) == effectivePort(baseUri);
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            boolean whitelistedOutputHost = VIDEO_URL_ALLOWED_HOST_SUFFIXES.stream()
                    .anyMatch(allowed -> normalizedHost.equals(allowed)
                            || normalizedHost.endsWith("." + allowed));
            if (!sameOriginAsChannel && !whitelistedOutputHost) {
                throw new IllegalArgumentException("host is not allowed");
            }
            List<InetAddress> resolvedAddresses = resolveVideoHost(host);
            if (resolvedAddresses.isEmpty() || resolvedAddresses.stream().anyMatch(this::isInternalAddress)) {
                throw new IllegalArgumentException("host resolves to an internal address");
            }
            boolean attachChannelCredentials = sameOriginAsChannel;
            return new VideoDownloadTarget(host, attachChannelCredentials, resolvedAddresses);
        } catch (Exception e) {
            throw new BusinessException(400, "上游返回了非法的视频下载地址", "Upstream returned an invalid video download URL");
        }
    }

    record VideoDownloadTarget(String host, boolean attachChannelCredentials, List<InetAddress> resolvedAddresses) {}

    List<InetAddress> resolveVideoHost(String host) throws IOException {
        return List.of(InetAddress.getAllByName(host));
    }

    /** Pin the addresses that passed validation so a second DNS lookup cannot rebind the download. */
    private OkHttpClient downloadClientFor(VideoDownloadTarget target) {
        return videoDownloadHttpClient.newBuilder()
                .dns(hostname -> hostname.equalsIgnoreCase(target.host())
                        ? target.resolvedAddresses()
                        : okhttp3.Dns.SYSTEM.lookup(hostname))
                .build();
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /** Reject a declared oversized response before reading, then enforce the cap while streaming. */
    private static byte[] readCappedResponseBody(Response response, long maxBytes) throws IOException {
        ResponseBody body = response.body();
        if (body == null) return new byte[0];
        if (body.contentLength() > maxBytes) {
            throw responseTooLarge(maxBytes);
        }
        return readCappedBody(body.byteStream(), maxBytes);
    }

    private static String readCappedResponseString(Response response, long maxBytes) throws IOException {
        if (!response.isSuccessful()) {
            return new String(readAtMost(response.body(), VIDEO_ERROR_BODY_MAX_BYTES), StandardCharsets.UTF_8);
        }
        return new String(readCappedResponseBody(response, maxBytes), StandardCharsets.UTF_8);
    }

    private static byte[] readAtMost(ResponseBody body, int maxBytes) throws IOException {
        if (body == null) return new byte[0];
        java.io.InputStream in = body.byteStream();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.min(maxBytes, 1024));
        byte[] buffer = new byte[Math.min(maxBytes, 1024)];
        int remaining = maxBytes;
        while (remaining > 0) {
            int read = in.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read == -1) break;
            out.write(buffer, 0, read);
            remaining -= read;
        }
        return out.toByteArray();
    }

    /** 限制大小地读取输入流，超限抛错而不是无限占用内存（参考 RelaySupport#readCapped）。 */
    private static byte[] readCappedBody(java.io.InputStream in, long maxBytes) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buf)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw responseTooLarge(maxBytes);
            }
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    private static BusinessException responseTooLarge(long maxBytes) {
        return new BusinessException(502,
                "上游响应超出大小限制 (" + maxBytes / (1024 * 1024) + "MB)",
                "Upstream response exceeds the size limit (" + maxBytes / (1024 * 1024) + "MB)");
    }

    private boolean isAgnesTypeChannel(String baseUrl, String channelType) {
        if ("agnes".equalsIgnoreCase(channelType)) {
            return true;
        }
        try {
            String host = new URI(baseUrl).getHost();
            return RelaySupport.isAgnesHost(host);
        } catch (Exception e) {
            return false;
        }
    }

    private String videoStatusPath(String baseUrl, String channelType, String videoId, String model) {
        if (!isAgnesTypeChannel(baseUrl, channelType)) {
            return "/v1/videos/" + videoId;
        }
        if (model == null || model.isBlank()) {
            throw new BusinessException("Agnes 视频状态查询缺少模型名称", "Agnes video status query is missing the model name");
        }
        return "/agnesapi?video_id=" + URLEncoder.encode(videoId, StandardCharsets.UTF_8)
                + "&model_name=" + URLEncoder.encode(model, StandardCharsets.UTF_8);
    }

    public record TestMediaContent(byte[] bytes, String contentType) {}

    private String requireVideoId(Map<String, String> request) {
        String videoId = requireTestValue(request, "videoId", "缺少视频任务 id", "Missing video task ID");
        if (!videoId.matches("[A-Za-z0-9_\\-.:+/=]+")) {
            throw new BusinessException("无效的视频任务 id", "Invalid video task ID");
        }
        return videoId;
    }

    private Request buildTestRequest(String baseUrl, String apiKey, String channelType,
                                     String path, String jsonBody, boolean get) {
        Request.Builder builder = new Request.Builder().url(baseUrl.replaceAll("/+$", "") + path);
        addAuthHeader(builder, apiKey, channelType);
        if (get) return builder.get().build();
        return builder.addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json"))).build();
    }

    private void addAuthHeader(Request.Builder builder, String apiKey, String channelType) {
        if ("claude".equalsIgnoreCase(channelType) || "anthropic".equalsIgnoreCase(channelType)) {
            builder.addHeader("x-api-key", apiKey).addHeader("anthropic-version", "2023-06-01");
        } else {
            builder.addHeader("Authorization", "Bearer " + apiKey);
        }
    }

    /**
     * 从视频任务状态响应中提取上游产出的最终 HTTP(S) 地址。Agnes 当前返回顶层 url，
     * 也兼容 data 包装和旧 metadata.url；视频 id 字段不是下载地址。
     */
    private String findVideoUrl(JsonNode json) {
        if (json == null) return null;
        String url = httpUrlOrNull(json.path("url"));
        if (url != null) return url;
        url = httpUrlOrNull(json.path("data").path("url"));
        if (url != null) return url;
        url = httpUrlOrNull(json.path("metadata").path("url"));
        if (url != null) return url;
        url = httpUrlOrNull(json.path("data").path("metadata").path("url"));
        if (url != null) return url;
        return httpUrlOrNull(json.path("video").path("metadata").path("url"));
    }

    private String httpUrlOrNull(JsonNode node) {
        String candidate = textOrNull(node);
        if (candidate == null) return null;
        try {
            URI uri = new URI(candidate);
            String scheme = uri.getScheme();
            return uri.getHost() != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    ? candidate : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 任务状态可能出现在顶层或 data/video 对象的 status/state 字段。 */
    private String findVideoStatus(JsonNode json) {
        if (json == null) return null;
        String status = textOrNull(json.path("status"));
        if (status != null) return status;
        status = textOrNull(json.path("data").path("status"));
        if (status != null) return status;
        status = textOrNull(json.path("video").path("status"));
        if (status != null) return status;
        status = textOrNull(json.path("state"));
        if (status != null) return status;
        status = textOrNull(json.path("data").path("state"));
        if (status != null) return status;
        return textOrNull(json.path("video").path("state"));
    }

    String terminalVideoFailureMessage(JsonNode json) {
        String status = findVideoStatus(json);
        if (status == null || !TERMINAL_FAILURE_STATUSES.contains(status.toLowerCase(Locale.ROOT))) {
            return null;
        }
        String errorMessage = firstTextOrNull(
                json.path("error").path("message"),
                json.path("data").path("error").path("message"),
                json.path("video").path("error").path("message"),
                json.path("error"),
                json.path("message"));
        return "上游视频任务失败 (" + status + ")"
                + (errorMessage != null
                ? ": " + abbreviateTestError(errorMessage, VIDEO_ERROR_SUMMARY_MAX_CHARS)
                : "");
    }

    private String textOrNull(JsonNode node) {
        return (node != null && node.isTextual() && !node.asText().isBlank()) ? node.asText() : null;
    }

    private String firstTextOrNull(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            String text = textOrNull(node);
            if (text != null) return text;
        }
        return null;
    }

    private String requireTestValue(Map<String, String> request, String key, String error, String englishError) {
        String value = request.get(key);
        if (value == null || value.isBlank()) throw new BusinessException(error, englishError);
        return value;
    }

    private String abbreviateTestError(String body) {
        return abbreviateTestError(body, 1000);
    }

    private String abbreviateTestError(String body, int maxLength) {
        if (body == null) return "";
        String sanitized = body
                .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1[REDACTED]")
                .replaceAll("(?i)(\\\"?(?:api[_-]?key|access[_-]?token|authorization)\\\"?\\s*[:=]\\s*\\\"?)[^\\\"\\s,}]+", "$1[REDACTED]")
                .replaceAll("(?i)([?&](?:x-amz-signature|x-amz-credential|x-amz-algorithm|x-amz-date|x-amz-security-token|token|sig|signature|expires)=)[^&#\\s\\\"']*", "$1[REDACTED]");
        return sanitized.length() > maxLength ? sanitized.substring(0, maxLength) + "…" : sanitized;
    }

    private Object parseTestJsonOrText(String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            return json != null ? json : body;
        } catch (IOException ignored) {
            return body;
        }
    }

    /**
     * 通用非流式测试方法，isClaude 控制 usage 字段输出格式
     */
    private Map<String, Object> doTestChat(String baseUrl, String apiKey, String model,
                                            String message, long startTime, boolean isClaude) throws IOException {
        String url = OpenAiUrlUtils.chatCompletionsUrl(baseUrl);
        String jsonBody = objectMapper.writeValueAsString(Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", message != null ? message : "hi")),
                "max_tokens", 100
        ));

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            long duration = System.currentTimeMillis() - startTime;
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("success", response.isSuccessful());
            result.put("statusCode", response.code());
            result.put("duration", duration);

            if (response.isSuccessful()) {
                JsonNode root = objectMapper.readTree(body);
                String content = root.path("choices").path(0).path("message").path("content").asText("");
                result.put("content", content);
                JsonNode usage = root.get("usage");
                if (usage != null) {
                    Map<String, Object> usageMap = new java.util.LinkedHashMap<>();
                    if (isClaude) {
                        usageMap.put("input_tokens", usage.path("prompt_tokens").asInt(0));
                        usageMap.put("output_tokens", usage.path("completion_tokens").asInt(0));
                    } else {
                        usageMap.put("prompt_tokens", usage.path("prompt_tokens").asInt(0));
                        usageMap.put("completion_tokens", usage.path("completion_tokens").asInt(0));
                        usageMap.put("total_tokens", usage.path("total_tokens").asInt(0));
                    }
                    result.put("usage", usageMap);
                }
            } else {
                result.put("error", body.length() > 500 ? body.substring(0, 500) : body);
            }
            return result;
        }
    }

    /**
     * 测试渠道聊天功能（流式）- 发送一条消息并流式返回响应
     */
    public void testChatStream(Map<String, String> request, HttpServletResponse response) throws Exception {
        SseUtils.setSseHeaders(response);

        String baseUrl = request.get("baseUrl");
        String apiKey = request.get("apiKey");
        String type = request.get("type");
        String model = request.get("model");
        String message = request.get("message");

        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new BusinessException("请先填写 Base URL 和 API Key", "Please provide the Base URL and API key first");
        }
        if (model == null || model.isBlank()) {
            throw new BusinessException("请选择模型", "Please select a model");
        }
        model = resolveTestModelName(model);

        try {
            boolean isClaude = "claude".equalsIgnoreCase(type) || "anthropic".equalsIgnoreCase(type);
            doTestChatStream(baseUrl, apiKey, model, message, response, isClaude);
        } catch (Exception e) {
            log.error("测试渠道流式对话失败: baseUrl={}, model={}", baseUrl, model, e);
            // 发送错误事件
            String errorMessage = (e.getMessage() == null || e.getMessage().isBlank())
                    ? e.getClass().getSimpleName() : e.getMessage();
            response.getWriter().write("data: {\"error\":\"" + SseUtils.escapeJson(errorMessage) + "\"}\n\n");
            response.getWriter().write("data: [DONE]\n\n");
            response.getWriter().flush();
        }
    }

    /**
     * 通用流式测试方法，isClaude 控制 SSE 输出格式
     */
    private void doTestChatStream(String baseUrl, String apiKey, String model,
                                   String message, HttpServletResponse response, boolean isClaude) throws Exception {
        String url = OpenAiUrlUtils.chatCompletionsUrl(baseUrl);
        log.info("{}流式请求: url={}, model={}", isClaude ? "Claude(转OpenAI) " : "", url, model);
        String jsonBody = objectMapper.writeValueAsString(Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", message != null ? message : "hi")),
                "max_tokens", 4096,
                "stream", true
        ));

        // 使用 HttpURLConnection 避免 OkHttp 连接池问题
        java.net.URL urlObj = new java.net.URL(url);
        java.net.HttpURLConnection conn = null;

        log.info("准备发送HTTP请求");
        long httpStart = System.currentTimeMillis();
        try {
            conn = (java.net.HttpURLConnection) urlObj.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Connection", "close");
            conn.getOutputStream().write(jsonBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            conn.getOutputStream().flush();

            int code = conn.getResponseCode();
            long httpDuration = System.currentTimeMillis() - httpStart;
            log.info("HTTP请求返回, 耗时: {}ms, code: {}", httpDuration, code);

            if (code != 200) {
                String errorBody = conn.getErrorStream() != null
                        ? new String(conn.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) : "";
                log.error("上游返回错误: {}", errorBody);
                response.getWriter().write("data: {\"error\":\"HTTP " + code + (errorBody.isEmpty() ? "" : ": " + SseUtils.escapeJson(errorBody)) + "\"}\n\n");
                response.getWriter().write("data: [DONE]\n\n");
                response.getWriter().flush();
                return;
            }

            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            String line;
            int chunkCount = 0;
            int lineCount = 0;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                line = line.trim();
                if (!line.startsWith("data:")) continue;

                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    if (!isClaude) {
                        response.getWriter().write("data: [DONE]\n\n");
                        response.getWriter().flush();
                    }
                    log.info("流式传输完成, 共 {} 个 chunk, {} 行数据", chunkCount, lineCount);
                    break;
                }

                try {
                    JsonNode json = objectMapper.readTree(data);
                    JsonNode delta = json.path("choices").path(0).path("delta");
                    String content = delta.path("content").asText("");
                    String reasoningContent = delta.path("reasoning_content").asText("");
                    String text = content.isEmpty() ? reasoningContent : content;
                    if (!text.isEmpty()) {
                        if (isClaude) {
                            String claudeEvt = "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":" + objectMapper.writeValueAsString(text) + "}}";
                            response.getWriter().write("data: " + claudeEvt + "\n\n");
                        } else {
                            Map<String, Object> chunk = new java.util.LinkedHashMap<>();
                            chunk.put("content", text);
                            if (!reasoningContent.isEmpty()) {
                                chunk.put("reasoning", true);
                            }
                            response.getWriter().write("data: " + objectMapper.writeValueAsString(chunk) + "\n\n");
                        }
                        response.getWriter().flush();
                        chunkCount++;
                    }
                } catch (Exception e) {
                    log.warn("解析 SSE 数据失败: {}", data);
                }
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 从上游渠道获取支持的模型列表
     */
    public List<String> fetchUpstreamModels(String baseUrl, String apiKey, String type) {
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new BusinessException("请先填写 Base URL 和 API Key", "Please provide the Base URL and API key first");
        }
        validateBaseUrlForSsrf(baseUrl);

        if ("gemini".equalsIgnoreCase(type)) {
            String url = OpenAiUrlUtils.modelsUrl(baseUrl, "v1beta") + "?key=" + apiKey;
            Request.Builder reqBuilder = new Request.Builder().url(url).get();
            try (Response response = httpClient.newCall(reqBuilder.build()).execute()) {
                if (!response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "";
                    throw new BusinessException("上游接口返回 " + response.code() + ": " + body,
                            "Upstream endpoint returned " + response.code() + ": " + body);
                }
                String body = response.body() != null ? response.body().string() : "";
                JsonNode root = objectMapper.readTree(body);
                List<String> models = new ArrayList<>();
                JsonNode modelsArray = root.has("models") ? root.get("models") : root;
                if (modelsArray.isArray()) {
                    for (JsonNode node : modelsArray) {
                        String name = node.has("name") ? node.get("name").asText() : null;
                        if (name != null && !name.isBlank()) {
                            if (name.startsWith("models/")) {
                                name = name.substring("models/".length());
                            }
                            models.add(name);
                        }
                    }
                }
                return models;
            } catch (IOException e) {
                log.error("获取 Gemini 上游模型列表失败: {}", e.getMessage());
                throw new BusinessException("连接上游失败: " + e.getMessage(),
                        "Failed to connect to upstream: " + e.getMessage());
            }
        }

        String url = OpenAiUrlUtils.modelsUrl(baseUrl);
        Request.Builder reqBuilder = new Request.Builder().url(url).get();

        // 根据渠道类型设置认证头
        if ("claude".equalsIgnoreCase(type) || "anthropic".equalsIgnoreCase(type)) {
            reqBuilder.addHeader("x-api-key", apiKey);
            reqBuilder.addHeader("anthropic-version", "2023-06-01");
        } else {
            reqBuilder.addHeader("Authorization", "Bearer " + apiKey);
        }

        try (Response response = httpClient.newCall(reqBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                if (response.code() == 404 || response.code() == 405) {
                    log.warn("上游不支持 GET /v1/models (HTTP {})，返回空模型列表", response.code());
                    return new ArrayList<>();
                }
                String body = response.body() != null ? response.body().string() : "";
                throw new BusinessException("上游接口返回 " + response.code() + ": " + body,
                        "Upstream endpoint returned " + response.code() + ": " + body);
            }
            String body = response.body() != null ? response.body().string() : "";
            JsonNode root = objectMapper.readTree(body);
            List<String> models = new ArrayList<>();
            JsonNode data = root.has("data") ? root.get("data") : root;
            if (data.isArray()) {
                for (JsonNode node : data) {
                    String id = node.has("id") ? node.get("id").asText() : null;
                    if (id != null && !id.isBlank()) {
                        models.add(id);
                    }
                }
            }
            return models;
        } catch (IOException e) {
            log.error("获取上游模型列表失败: {}", e.getMessage());
            throw new BusinessException("连接上游失败: " + e.getMessage(),
                    "Failed to connect to upstream: " + e.getMessage());
        }
    }
}
