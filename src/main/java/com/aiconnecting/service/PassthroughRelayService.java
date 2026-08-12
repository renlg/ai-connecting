package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
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
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

    private final RelaySupport support;
    private final ChannelService channelService;
    private final ObjectMapper objectMapper;
    private final okhttp3.Call.Factory callFactory;

    @org.springframework.beans.factory.annotation.Autowired
    public PassthroughRelayService(RelaySupport support, ChannelService channelService, ObjectMapper objectMapper) {
        this(support, channelService, objectMapper, new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build());
    }

    PassthroughRelayService(RelaySupport support, ChannelService channelService, ObjectMapper objectMapper,
                            okhttp3.Call.Factory callFactory) {
        this.support = support;
        this.channelService = channelService;
        this.objectMapper = objectMapper;
        this.callFactory = callFactory;
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
        String canonicalModel = support.resolveModelName(requestedModel);
        String channelModelId = support.resolveToChannelModelId(canonicalModel);
        if (!channelService.isPassthroughOnlyModel(channelModelId)) {
            return false;
        }

        String tokenKey = extractTokenKey(request);
        RelaySupport.RelayContext context = support.validateAndPrepare(
                tokenKey, canonicalModel, endpointType(request.getRequestURI()));
        Set<Long> attempted = new HashSet<>();
        IOException lastConnectionFailure = null;

        for (int attempt = 0; attempt < RelaySupport.MAX_RETRIES; attempt++) {
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
            attempted.add(channel.getId());
            String upstreamModel = mappedModel(channel, canonicalModel);
            String rewrittenBody = rewriteTopLevelModelVerbatim(rawBody, upstreamModel);
            Request upstreamRequest = buildPassthroughRequest(channel, request, rewrittenBody);
            long startedAt = System.currentTimeMillis();

            final Response upstreamResponse;
            try {
                upstreamResponse = executePassthrough(upstreamRequest);
            } catch (IOException e) {
                lastConnectionFailure = e;
                support.channelHealthTracker.recordFailure(channel.getId(),
                        ChannelHealthTracker.ErrorCategory.fromException(e), e.getMessage());
                continue; // no response bytes exist yet; another custom channel may be attempted
            }

            try (upstreamResponse) {
                UsageObserver observer = new UsageObserver(
                        upstreamResponse.header("Content-Type"), objectMapper);
                try {
                    copyUpstreamResponse(upstreamResponse, servletResponse, observer);
                    if (upstreamResponse.isSuccessful()) {
                        support.channelHealthTracker.recordSuccess(channel.getId());
                    } else {
                        support.channelHealthTracker.recordFailure(channel.getId(),
                                ChannelHealthTracker.ErrorCategory.fromStatusCode(upstreamResponse.code()),
                                "HTTP " + upstreamResponse.code());
                    }
                } catch (IOException streamFailure) {
                    // The upstream response has already been selected/started. Never retry or append an error.
                    log.warn("透传响应流中断: channel={}, path={}, error={}",
                            channel.getId(), request.getRequestURI(), streamFailure.getMessage());
                } finally {
                    Usage usage = observer.finish();
                    if (!usage.found()) {
                        log.warn("透传响应未包含 usage，按零 token 记录: channel={}, model={}",
                                channel.getId(), canonicalModel);
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
                return true;
            }
        }
        throw new BusinessException(502, "渠道连接失败", "Upstream connection failed", lastConnectionFailure);
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
        StringBuilder url = new StringBuilder(channel.getBaseUrl().replaceAll("/+$", ""))
                .append(originalRequest.getRequestURI());
        if (originalRequest.getQueryString() != null && !originalRequest.getQueryString().isEmpty()) {
            url.append('?').append(originalRequest.getQueryString());
        }
        Request.Builder builder = new Request.Builder().url(url.toString());
        Enumeration<String> names = originalRequest.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            String lower = name.toLowerCase(Locale.ROOT);
            if ("authorization".equals(lower) || "host".equals(lower) || "content-length".equals(lower)
                    || HOP_BY_HOP.contains(lower) || lower.startsWith("proxy-")) continue;
            Enumeration<String> values = originalRequest.getHeaders(name);
            while (values.hasMoreElements()) builder.addHeader(name, values.nextElement());
        }
        builder.header("Authorization", "Bea" + "rer" + " " + channel.getApiKey());
        String contentType = originalRequest.getContentType() != null
                ? originalRequest.getContentType() : "application/json";
        RequestBody body = RequestBody.create(rewrittenBody.getBytes(StandardCharsets.UTF_8),
                MediaType.parse(contentType));
        return builder.post(body).build();
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
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS);
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
