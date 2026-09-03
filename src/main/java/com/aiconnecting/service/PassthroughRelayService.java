package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.OpenAiUrlUtils;
import com.aiconnecting.common.SseUtils;
import com.aiconnecting.common.UpstreamErrorUtils;
import com.aiconnecting.config.RelayTimeoutProperties;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.Token;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Byte-for-byte response relay for channels whose type is {@code custom}. */
@Service
@Slf4j
public class PassthroughRelayService {

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "transfer-encoding", "keep-alive", "te", "trailer", "upgrade");

    /**
     * 客户端认证凭据头：与本站的会话/密钥绝不能跨信任边界发往管理员配置的第三方上游，
     * 转发前一律剥离（上游认证只使用渠道自身的 apiKey）
     */
    private static final Set<String> CLIENT_CREDENTIAL_HEADERS = Set.of(
            "cookie", "x-api-key", "x-goog-api-key", "api-key", "x-auth-token", "x-session-token");

    /**
     * 客户端习惯放在 query 中的认证参数（如 Gemini 客户端的 ?key=<中转token>），
     * 拼接上游 URL 前剥离，避免把本站凭据泄漏给第三方上游
     */
    private static final Set<String> CLIENT_CREDENTIAL_QUERY_PARAMS = Set.of(
            "key", "api_key", "apikey", "api-key", "access_token", "auth_token", "token", "secret");

    private final RelaySupport support;
    private final ChannelService channelService;
    private final ObjectMapper objectMapper;
    private final okhttp3.Call.Factory callFactory;
    private final RelayTimeoutProperties timeoutProperties;

    @Autowired(required = false)
    private FailureLogService failureLogService;

    @org.springframework.beans.factory.annotation.Autowired
    public PassthroughRelayService(RelaySupport support, ChannelService channelService, ObjectMapper objectMapper,
                                   RelayTimeoutProperties timeoutProperties) {
        this(support, channelService, objectMapper, buildPassthroughClient(timeoutProperties), timeoutProperties);
    }

    public PassthroughRelayService(RelaySupport support, ChannelService channelService, ObjectMapper objectMapper) {
        this(support, channelService, objectMapper, new RelayTimeoutProperties());
    }

    private static OkHttpClient buildPassthroughClient(RelayTimeoutProperties timeouts) {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(128);
        dispatcher.setMaxRequestsPerHost(32);
        return new OkHttpClient.Builder()
                .connectTimeout(Math.max(1L, timeouts.getConnectMs()), TimeUnit.MILLISECONDS)
                .readTimeout(Math.max(1L, timeouts.getPassthroughReadMs()), TimeUnit.MILLISECONDS)
                .writeTimeout(Math.max(1L, timeouts.getWriteMs()), TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .connectionPool(new ConnectionPool(32, 5, TimeUnit.MINUTES))
                .dispatcher(dispatcher)
                .build();
    }

    PassthroughRelayService(RelaySupport support, ChannelService channelService, ObjectMapper objectMapper,
                            okhttp3.Call.Factory callFactory) {
        this(support, channelService, objectMapper, callFactory, new RelayTimeoutProperties());
    }

    PassthroughRelayService(RelaySupport support, ChannelService channelService, ObjectMapper objectMapper,
                            okhttp3.Call.Factory callFactory, RelayTimeoutProperties timeoutProperties) {
        this.support = support;
        this.channelService = channelService;
        this.objectMapper = objectMapper;
        this.callFactory = callFactory;
        this.timeoutProperties = timeoutProperties;
    }

    /**
     * @return true when this request belonged to a passthrough-only model and has been fully handled.
     */
    public boolean tryPassthrough(String rawBody, HttpServletRequest request,
                                  HttpServletResponse servletResponse) throws IOException {
        String requestedModel;
        try {
            requestedModel = extractTopLevelModel(rawBody);
        } catch (BusinessException e) {
            request.setAttribute("passthrough.local-json-error", Boolean.TRUE);
            throw e;
        }
        return tryPassthroughForModel(rawBody, requestedModel, request, servletResponse);
    }

    /** Gemini supplies its model in the URL; keep its body byte-for-byte intact on custom channels. */
    public boolean tryPassthroughForModel(String rawBody, String requestedModel,
                                          HttpServletRequest request,
                                          HttpServletResponse servletResponse) throws IOException {
        FailureLogContext.initialize(request, requestedModel, protocol(request.getRequestURI()));
        String canonicalModel = support.resolveModelName(requestedModel);
        String channelModelId = support.resolveToChannelModelId(canonicalModel);
        if (!support.channelRouter.isPassthroughOnlyModel(channelModelId)) {
            return false;
        }
        if (request.getRequestURI() != null && request.getRequestURI().startsWith("/v1/models/")) {
            try {
                JsonNode json = objectMapper.readTree(rawBody);
                if (json == null || !json.isObject()) throw new IOException("body is not a JSON object");
            } catch (Exception e) {
                request.setAttribute("passthrough.local-json-error", Boolean.TRUE);
                throw new BusinessException(400, "请求 JSON 格式无效", "Invalid request JSON", e);
            }
        }

        String tokenKey = extractTokenKey(request);
        RelaySupport.RelayContext context = support.validateAndPrepare(
                tokenKey, canonicalModel, endpointType(request.getRequestURI()));
        Long modelConfigId = context.modelConfig() != null ? context.modelConfig().getId() : null;
        Set<Long> attempted = new HashSet<>();
        IOException lastConnectionFailure = null;
        String requestType = endpointType(request.getRequestURI());
        boolean mediaRequest = !"text".equals(requestType);
        boolean sseRequest = acceptsSse(request);
        if (!mediaRequest) {
            support.checkTextBalanceEstimate(context, canonicalModel, rawBody);
        }
        long callBudgetMs = sseRequest ? timeoutProperties.getSseMaxDurationMs()
                : mediaRequest ? timeoutProperties.getPassthroughMediaCallMs()
                : timeoutProperties.getPassthroughTextCallMs();
        long deadline = System.currentTimeMillis() + Math.max(1L, callBudgetMs);

        for (int attempt = 0; attempt < RelaySupport.MAX_RETRIES && System.currentTimeMillis() < deadline; attempt++) {
            Channel channel;
            try {
                channel = support.channelRouter.selectChannel(
                        context.channelModelId(), attempted, context.userLevel());
            } catch (BusinessException e) {
                if (lastConnectionFailure != null && e.getCode() == 503) break;
                if (e.getCode() != 503) throw e;
                throw modelNotFound(canonicalModel);
            }
            if (!"custom".equalsIgnoreCase(channel.getType())) {
                throw modelNotFound(canonicalModel);
            }
            if (support.isChannelRateLimited(channel, context.channelModelId())) {
                throw new BusinessException(429, "请求过于频繁，请稍后重试",
                        "Too many requests, please try again later");
            }
            attempted.add(channel.getId());
            String upstreamModel = mappedModel(channel, canonicalModel);
            FailureLogContext.setChannelModel(upstreamModel);
            String mappedPath = mappedGeminiPath(request.getRequestURI(), upstreamModel);
            String rewrittenBody = mappedPath != null
                    ? rawBody : rewriteTopLevelModelVerbatim(rawBody, upstreamModel);
            Request upstreamRequest = buildPassthroughRequest(channel, request, rewrittenBody,
                    mappedPath);
            long startedAt = System.currentTimeMillis();

            final Response upstreamResponse;
            try {
                long attemptTimeoutMs = !sseRequest && !mediaRequest
                        ? support.failoverAttemptTimeoutMs(deadline, attempt + 1, RelaySupport.MAX_RETRIES)
                        : Math.max(1L, deadline - System.currentTimeMillis());
                upstreamResponse = executePassthrough(upstreamRequest, attemptTimeoutMs);
            } catch (IOException e) {
                lastConnectionFailure = e;
                boolean timedOut = e instanceof InterruptedIOException;
                BusinessException failure = new BusinessException(timedOut ? 504 : 502,
                        timedOut ? "渠道请求超时: " + e.getMessage() : "渠道连接失败: " + e.getMessage(),
                        timedOut ? "Channel request timed out: " + e.getMessage()
                                : "Channel connection failed: " + e.getMessage(), e, null, null, true);
                support.dispatchRelayFailure(channel.getId(), channel.getName(), modelConfigId, failure);
                if (mediaRequest && timedOut) throw failure;
                continue; // no response bytes exist yet; another custom channel may be attempted
            }

            try (upstreamResponse) {
                String failureBody = null;
                if (!upstreamResponse.isSuccessful()) {
                    failureBody = upstreamResponse.peekBody(FailureLogService.MAX_ERROR_LENGTH).string();
                    FailureLogContext.setChannelError(upstreamResponse.code(), failureBody);
                }
                UsageObserver observer = new UsageObserver(
                        upstreamResponse.header("Content-Type"), objectMapper);
                try {
                    if (upstreamResponse.code() == 429) {
                        new RelayProtocolAdapter(objectMapper).writeError(
                                protocol(request.getRequestURI()), servletResponse, upstreamResponse.code(),
                                UpstreamErrorUtils.clientFacingMessage(upstreamResponse.code(), failureBody), true);
                    } else {
                        copyUpstreamResponse(upstreamResponse, servletResponse, observer);
                    }
                    if (upstreamResponse.isSuccessful()) {
                    } else {
                        BusinessException upstreamFailure = BusinessException.upstream(
                                upstreamResponse.code(),
                                "上游 API 错误: " + failureBody,
                                "Upstream API error: " + failureBody,
                                failureBody, null);
                        support.dispatchRelayFailure(channel.getId(), channel.getName(), modelConfigId, upstreamFailure);
                    }
                } catch (IOException streamFailure) {
                    // The upstream response has already been selected/started. Never retry or append an error.
                    log.warn("透传响应流中断: channel={}, path={}, error={}",
                            channel.getId(), request.getRequestURI(), streamFailure.getMessage());
                } finally {
                    Usage usage = observer.finish();
                    if (!usage.found()) {
                        if (upstreamResponse.isSuccessful() && !mediaRequest) {
                            // 上游未回 usage：按已传输字节与请求体长度估算，避免零计费
                            log.warn("透传响应未包含 usage，按已传输字节估算 token 计费: channel={}, model={}",
                                    channel.getId(), canonicalModel);
                            usage = estimatedUsage(observer.observedByteCount(), rawBody);
                        } else {
                            log.warn("透传响应未包含 usage，按零 token 记录: channel={}, model={}",
                                    channel.getId(), canonicalModel);
                        }
                    }
                    try {
                        support.recordPassthroughUsage(context.token(), channel, canonicalModel, upstreamModel,
                                usage.promptTokens(), usage.completionTokens(), usage.totalTokens(),
                                usage.cachedTokens(), usage.cacheCreationTokens(), usage.cacheReadTokens(),
                                System.currentTimeMillis() - startedAt, request, request.getRequestURI());
                    } catch (Exception billingError) {
                        log.error("透传用量记录失败（响应不受影响）: channel={}, model={}",
                                channel.getId(), canonicalModel, billingError);
                    }
                }
                if (!upstreamResponse.isSuccessful() && failureLogService != null) {
                    failureLogService.record(request, upstreamResponse.code(),
                            upstreamResponse.code() == 429
                                    ? SseUtils.GENERIC_UPSTREAM_ERROR_MESSAGE
                                    : (failureBody == null || failureBody.isBlank()
                                    ? "Upstream API error" : failureBody),
                            "Upstream API error: " + upstreamResponse.code() + " - " + failureBody);
                }
                return true;
            }
        }
        FailureLogContext.setChannelError(lastConnectionFailure != null
                ? "Upstream connection failed: " + lastConnectionFailure.getMessage()
                : "Upstream connection failed");
        throw new BusinessException(502, "渠道连接失败", "Upstream connection failed", lastConnectionFailure);
    }

    private RelayProtocol protocol(String uri) {
        if ("/v1/messages".equals(uri)) return RelayProtocol.CLAUDE;
        if (uri != null && uri.startsWith("/v1/models/") && uri.contains("Content")) return RelayProtocol.GEMINI;
        return RelayProtocol.OPENAI;
    }

    public String extractTopLevelModel(String rawBody) {
        try (JsonParser parser = objectMapper.getFactory().createParser(rawBody)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw invalidModelRequest();
            }
            String model = null;
            int count = 0;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) throw invalidModelRequest();
                String field = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                if ("model".equals(field)) {
                    count++;
                    if (valueToken != JsonToken.VALUE_STRING) throw invalidModelRequest();
                    model = parser.getText();
                } else {
                    parser.skipChildren();
                }
            }
            if (count != 1 || model == null) throw invalidModelRequest();
            if (parser.nextToken() != null) throw invalidModelRequest();
            return model;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw invalidModelRequest();
        }
    }

    /** Replaces only the character span occupied by the single top-level model string token. */
    public String rewriteTopLevelModelVerbatim(String rawBody, String upstreamModel) {
        try (JsonParser parser = objectMapper.getFactory().createParser(rawBody)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw invalidModelRequest();
            int count = 0;
            int start = -1;
            int end = -1;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) throw invalidModelRequest();
                String field = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                if ("model".equals(field)) {
                    count++;
                    if (valueToken != JsonToken.VALUE_STRING) throw invalidModelRequest();
                    start = Math.toIntExact(parser.getTokenLocation().getCharOffset());
                    parser.getText(); // force Jackson to consume the complete lazy string token
                    end = Math.toIntExact(parser.getCurrentLocation().getCharOffset());
                } else {
                    parser.skipChildren();
                }
            }
            if (count != 1 || start < 0 || end <= start) throw invalidModelRequest();
            String escaped = objectMapper.writeValueAsString(upstreamModel);
            return rawBody.substring(0, start) + escaped + rawBody.substring(end);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw invalidModelRequest();
        }
    }

    public Request buildPassthroughRequest(Channel channel, HttpServletRequest originalRequest,
                                           String rewrittenBody) {
        return buildPassthroughRequest(channel, originalRequest, rewrittenBody, null);
    }

    public Request buildPassthroughRequest(Channel channel, HttpServletRequest originalRequest,
                                           String rewrittenBody, String overridePath) {
        String path = overridePath != null ? overridePath : originalRequest.getRequestURI();
        StringBuilder url = new StringBuilder(OpenAiUrlUtils.endpointUrl(channel.getBaseUrl(), path));
        String filteredQuery = stripCredentialQueryParams(originalRequest.getQueryString());
        if (filteredQuery != null && !filteredQuery.isEmpty()) {
            url.append(url.indexOf("?") >= 0 ? '&' : '?').append(filteredQuery);
        }
        Request.Builder builder = new Request.Builder().url(url.toString());
        Enumeration<String> names = originalRequest.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            String lower = name.toLowerCase(Locale.ROOT);
            if ("authorization".equals(lower) || "host".equals(lower) || "content-length".equals(lower)
                    || HOP_BY_HOP.contains(lower) || lower.startsWith("proxy-")
                    || CLIENT_CREDENTIAL_HEADERS.contains(lower)) continue;
            Enumeration<String> values = originalRequest.getHeaders(name);
            while (values.hasMoreElements()) builder.addHeader(name, values.nextElement());
        }
        builder.header("Authorization", "Bearer " + channel.getApiKey());
        String contentType = originalRequest.getContentType() != null
                ? originalRequest.getContentType() : "application/json";
        RequestBody body = RequestBody.create(rewrittenBody.getBytes(StandardCharsets.UTF_8),
                MediaType.parse(contentType));
        return builder.post(body).build();
    }

    /**
     * 从原始 query string 中剥离凭据参数，其余参数名值原样保留（不做重新编码，保持逐字节转发语义）
     */
    static String stripCredentialQueryParams(String queryString) {
        if (queryString == null || queryString.isEmpty()) {
            return queryString;
        }
        StringBuilder kept = new StringBuilder();
        for (String segment : queryString.split("&")) {
            int eq = segment.indexOf('=');
            String name = eq >= 0 ? segment.substring(0, eq) : segment;
            if (CLIENT_CREDENTIAL_QUERY_PARAMS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append('&');
            }
            kept.append(segment);
        }
        return kept.toString();
    }

    private String mappedGeminiPath(String uri, String upstreamModel) {
        if (uri == null || !uri.startsWith("/v1/models/")) return null;
        int colon = uri.indexOf(':', "/v1/models/".length());
        if (colon < 0) return null;
        return "/v1/models/" + upstreamModel + uri.substring(colon);
    }

    public void copyUpstreamResponse(Response response, HttpServletResponse servletResponse,
                                     UsageObserver usageObserver) throws IOException {
        servletResponse.setStatus(response.code());
        Headers headers = response.headers();
        for (int i = 0; i < headers.size(); i++) {
            servletResponse.addHeader(headers.name(i), headers.value(i));
        }
        ResponseBody body = response.body();
        if (body == null) return;
        try (InputStream in = body.byteStream()) {
            ServletOutputStream out = servletResponse.getOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                usageObserver.observe(buffer, 0, read);
                out.write(buffer, 0, read);
                out.flush();
            }
        }
    }

    String mappedModel(Channel channel, String canonicalModel) {
        try {
            JsonNode mapping = objectMapper.readTree(channel.getModelMapping());
            JsonNode value = mapping != null ? mapping.get(canonicalModel) : null;
            if (value == null || !value.isTextual() || value.textValue().isBlank()) {
                throw modelNotFound(canonicalModel);
            }
            return value.textValue();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw modelNotFound(canonicalModel);
        }
    }

    Response executePassthrough(Request request) throws IOException {
        return callFactory.newCall(request).execute();
    }

    Response executePassthrough(Request request, long timeoutMs) throws IOException {
        okhttp3.Call call = callFactory.newCall(request);
        okio.Timeout timeout = call.timeout();
        if (timeout != null) timeout.timeout(timeoutMs, TimeUnit.MILLISECONDS);
        return call.execute();
    }

    private String extractTokenKey(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bea" + "rer ")) return auth.substring(7);
        String apiKey = request.getHeader("x-api-key");
        if (apiKey != null && !apiKey.isBlank()) return apiKey;
        throw new BusinessException(401, "缺少认证信息", "Missing or malformed Authorization header");
    }

    private String endpointType(String path) {
        if (path.equals("/v1/images") || path.startsWith("/v1/images/")) return "image";
        if (path.equals("/v1/videos") || path.startsWith("/v1/videos/")) return "video";
        if (path.equals("/v1/audio") || path.startsWith("/v1/audio/")) return "audio";
        return "text";
    }

    private boolean acceptsSse(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept != null && accept.toLowerCase(Locale.ROOT).contains("text/event-stream")) return true;
        String uri = request.getRequestURI();
        return uri != null && uri.contains("streamGenerateContent");
    }

    private BusinessException invalidModelRequest() {
        return new BusinessException(400, "请求必须是包含唯一字符串 model 字段的 JSON 对象",
                "Request must be a JSON object with exactly one top-level string model field");
    }

    private BusinessException modelNotFound(String model) {
        return new BusinessException(404, "模型不存在: " + model, "Model not found: " + model);
    }

    public record Usage(boolean found, int promptTokens, int completionTokens, int totalTokens,
                        int cachedTokens, int cacheCreationTokens, int cacheReadTokens) {
        static Usage empty() { return new Usage(false, 0, 0, 0, 0, 0, 0); }
    }

    /** 无 usage 时的兜底估算：输出按已观测字节数、输入按请求体长度折算（约 4 字符/token），宁可轻微高估也不允许零计费 */
    static Usage estimatedUsage(long observedBytes, String requestBody) {
        int promptTokens = RelayServiceUtils.estimateTokensFromChars(requestBody != null ? requestBody.length() : 0);
        int completionTokens = RelayServiceUtils.estimateTokensFromChars(observedBytes);
        return new Usage(true, promptTokens, completionTokens, promptTokens + completionTokens, 0, 0, 0);
    }

    /** Observes a copy of chunks while the original bytes continue directly to the servlet stream. */
    public static final class UsageObserver {
        private final boolean sse;
        private final boolean jsonResponse;
        private final ObjectMapper mapper;
        private final ByteArrayOutputStream json = new ByteArrayOutputStream();
        private final ByteArrayOutputStream pendingLine = new ByteArrayOutputStream();
        private final StringBuilder eventData = new StringBuilder();
        private Usage lastUsage = Usage.empty();
        private long observedBytes;

        UsageObserver(String contentType, ObjectMapper mapper) {
            String normalized = contentType != null ? contentType.toLowerCase(Locale.ROOT) : "";
            this.sse = normalized.contains("text/event-stream");
            this.jsonResponse = normalized.contains("json");
            this.mapper = mapper;
        }

        void observe(byte[] bytes, int offset, int length) {
            observedBytes += length;
            if (!sse) {
                if (jsonResponse) json.write(bytes, offset, length);
                return;
            }
            for (int i = offset; i < offset + length; i++) {
                if (bytes[i] == '\n') {
                    String line = pendingLine.toString(StandardCharsets.UTF_8);
                    pendingLine.reset();
                    parseSseLine(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
                } else {
                    pendingLine.write(bytes[i]);
                }
            }
        }

        boolean bytesObserved() {
            return observedBytes > 0;
        }

        long observedByteCount() {
            return observedBytes;
        }

        Usage finish() {
            if (sse) {
                if (pendingLine.size() > 0) parseSseLine(pendingLine.toString(StandardCharsets.UTF_8));
                parseSseEvent();
                return lastUsage;
            }
            if (!jsonResponse) return Usage.empty();
            try {
                return usageFromRoot(mapper.readTree(json.toByteArray()));
            } catch (Exception ignored) {
                return Usage.empty();
            }
        }

        private void parseSseLine(String line) {
            if (line.isEmpty()) {
                parseSseEvent();
                return;
            }
            if (!line.startsWith("data:")) return;
            String data = line.substring(5);
            if (data.startsWith(" ")) data = data.substring(1);
            if (!eventData.isEmpty()) eventData.append('\n');
            eventData.append(data);
        }

        private void parseSseEvent() {
            String data = eventData.toString().trim();
            eventData.setLength(0);
            if (data.isEmpty() || "[DONE]".equals(data)) return;
            try {
                Usage usage = usageFromRoot(mapper.readTree(data));
                if (usage.found()) lastUsage = usage;
            } catch (Exception ignored) {
                // Non-JSON data events are valid and do not affect byte forwarding.
            }
        }

        private static Usage usageFromRoot(JsonNode root) {
            JsonNode usage = root != null ? root.get("usage") : null;
            if (usage == null || !usage.isObject()) return Usage.empty();
            int prompt = intValue(usage, "prompt_tokens", "input_tokens");
            int completion = intValue(usage, "completion_tokens", "output_tokens");
            int total = usage.has("total_tokens") ? usage.path("total_tokens").asInt() : prompt + completion;
            int cached = usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);
            int creation = usage.path("cache_creation_input_tokens").asInt(0);
            int read = usage.path("cache_read_input_tokens").asInt(0);
            if (cached == 0) cached = read;
            return new Usage(true, prompt, completion, total, cached, creation, read);
        }

        private static int intValue(JsonNode node, String first, String second) {
            return node.has(first) ? node.path(first).asInt() : node.path(second).asInt(0);
        }
    }
}
