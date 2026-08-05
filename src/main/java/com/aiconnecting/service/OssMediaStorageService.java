package com.aiconnecting.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * 将上游返回的媒体产物（图片/音频/视频）落盘到阿里云 OSS，并把响应中的上游 URL / base64
 * 替换为 OSS 公网直链，使客户端不再依赖上游（可能存在有效期或被限流的）地址。
 * OSS 未启用或凭据缺失时直接跳过重写，响应按上游原样透传，不影响主流程可用性；
 * 单个媒体项下载/上传失败同样只记录日志并保留该项原始字段，不影响响应中其它已计费产物的返回。
 */
@Slf4j
@Service
public class OssMediaStorageService {

    private static final Set<String> VIDEO_COMPLETED_STATUSES = Set.of("completed", "succeeded");

    private static final long IMAGE_MAX_BYTES = 25L * 1024 * 1024;
    private static final long AUDIO_MAX_BYTES = 50L * 1024 * 1024;
    private static final long VIDEO_MAX_BYTES = 200L * 1024 * 1024;

    @Value("${app.oss.enabled:true}")
    private boolean enabled;

    @Value("${app.oss.access-key-id:}")
    private String accessKeyId;

    @Value("${app.oss.access-key-secret:}")
    private String accessKeySecret;

    @Value("${app.oss.bucket:renlg}")
    private String bucket;

    @Value("${app.oss.endpoint:oss-cn-hangzhou.aliyuncs.com}")
    private String endpointHost;

    @Value("${app.oss.prefix:ai-connect}")
    private String prefix;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private OkHttpClient downloadClient;
    private OSS ossClient;
    private volatile boolean available;

    @PostConstruct
    void init() {
        downloadClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(60))
                .writeTimeout(Duration.ofSeconds(30))
                .followRedirects(true)
                .build();
        if (!enabled) {
            log.info("OSS 媒体持久化已通过配置关闭 (app.oss.enabled=false)");
            available = false;
            return;
        }
        if (accessKeyId.isBlank() || accessKeySecret.isBlank() || bucket.isBlank() || endpointHost.isBlank()) {
            log.warn("OSS 凭据/配置不完整，媒体持久化将被跳过，响应按上游原样透传");
            available = false;
            return;
        }
        try {
            ossClient = new OSSClientBuilder().build("https://" + endpointHost, accessKeyId, accessKeySecret);
            available = true;
        } catch (Exception e) {
            log.error("OSS 客户端初始化失败，媒体持久化将被跳过: {}", e.getMessage());
            available = false;
        }
    }

    @PreDestroy
    void shutdown() {
        if (ossClient != null) {
            ossClient.shutdown();
        }
    }

    boolean isAvailable() {
        return available;
    }

    /**
     * 图片生成响应重写：data[].url 下载后上传 OSS 并回写为 OSS 直链；
     * data[].b64_json 解码后上传 OSS，字段替换为 url（不再返回 base64，避免重复下发相同数据）。
     * 单项失败仅记录日志、保留原字段，不影响整体响应返回（图片计费按原始 data 数组长度结算，早于本方法调用）。
     */
    public String rewriteImageResponse(String response) {
        if (!available) {
            return response;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(response);
        } catch (Exception e) {
            return response;
        }
        if (root == null || !root.isObject()) {
            return response;
        }
        JsonNode dataNode = root.get("data");
        if (dataNode == null || !dataNode.isArray()) {
            return response;
        }
        ArrayNode data = (ArrayNode) dataNode;
        boolean mutated = false;
        for (JsonNode item : data) {
            if (!item.isObject()) {
                continue;
            }
            ObjectNode obj = (ObjectNode) item;
            try {
                if (rewriteImageItem(obj)) {
                    mutated = true;
                }
            } catch (Exception e) {
                log.warn("图片媒体项转存 OSS 失败，保留上游原始字段: {}", e.getMessage());
            }
        }
        if (!mutated) {
            return response;
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return response;
        }
    }

    private boolean rewriteImageItem(ObjectNode item) throws IOException {
        JsonNode b64Node = item.get("b64_json");
        if (b64Node != null && b64Node.isTextual() && !b64Node.asText().isBlank()) {
            byte[] bytes = Base64.getDecoder().decode(b64Node.asText());
            String contentType = sniffImageContentType(bytes);
            String ossUrl = uploadBytes(bytes, "images", extensionForImage(contentType), contentType);
            item.remove("b64_json");
            item.put("url", ossUrl);
            return true;
        }
        JsonNode urlNode = item.get("url");
        if (urlNode != null && urlNode.isTextual() && isHttpUrl(urlNode.asText())) {
            Downloaded downloaded = download(urlNode.asText(), IMAGE_MAX_BYTES);
            String contentType = downloaded.contentType() != null ? downloaded.contentType() : sniffImageContentType(downloaded.bytes());
            String ossUrl = uploadBytes(downloaded.bytes(), "images", extensionForImage(contentType), contentType);
            item.put("url", ossUrl);
            return true;
        }
        return false;
    }

    /**
     * 视频任务响应重写：仅当任务处于终态 completed/succeeded 且存在可下载的播放地址时，
     * 下载后转存 OSS 并回写地址；用于轮询响应 (GET /v1/videos/{id}) 及少数上游同步即返回
     * 完成态视频的创建响应。失败时记录日志并保留上游原始地址，不影响状态透传与已完成的计费结算。
     */
    public String rewriteVideoResponseIfCompleted(String response) {
        if (!available) {
            return response;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(response);
        } catch (Exception e) {
            return response;
        }
        if (root == null || !root.isObject()) {
            return response;
        }
        String status = findVideoStatus(root);
        if (status == null || !VIDEO_COMPLETED_STATUSES.contains(status.toLowerCase(Locale.ROOT))) {
            return response;
        }
        try {
            if (!rewriteFirstVideoUrl((ObjectNode) root)) {
                return response;
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("视频媒体转存 OSS 失败，保留上游原始地址: {}", e.getMessage());
            return response;
        }
    }

    /** 依次尝试 OpenAI 兼容视频任务对象中出现过的地址位置，命中第一个即回写，其余位置保持不变 */
    private boolean rewriteFirstVideoUrl(ObjectNode root) throws IOException {
        if (rewriteUrlField(root, "url")) {
            return true;
        }
        ObjectNode data = asObject(root.get("data"));
        if (data != null && rewriteUrlField(data, "url")) {
            return true;
        }
        ObjectNode metadata = asObject(root.get("metadata"));
        if (metadata != null && rewriteUrlField(metadata, "url")) {
            return true;
        }
        if (data != null) {
            ObjectNode dataMetadata = asObject(data.get("metadata"));
            if (dataMetadata != null && rewriteUrlField(dataMetadata, "url")) {
                return true;
            }
        }
        ObjectNode video = asObject(root.get("video"));
        if (video != null) {
            ObjectNode videoMetadata = asObject(video.get("metadata"));
            if (videoMetadata != null && rewriteUrlField(videoMetadata, "url")) {
                return true;
            }
        }
        return false;
    }

    private ObjectNode asObject(JsonNode node) {
        return node != null && node.isObject() ? (ObjectNode) node : null;
    }

    private boolean rewriteUrlField(ObjectNode holder, String field) throws IOException {
        JsonNode node = holder.get(field);
        if (node == null || !node.isTextual() || !isHttpUrl(node.asText())) {
            return false;
        }
        Downloaded downloaded = download(node.asText(), VIDEO_MAX_BYTES);
        String contentType = downloaded.contentType() != null ? downloaded.contentType() : "video/mp4";
        String ossUrl = uploadBytes(downloaded.bytes(), "videos", extensionForVideo(contentType), contentType);
        holder.put(field, ossUrl);
        return true;
    }

    private String findVideoStatus(JsonNode json) {
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

    private String textOrNull(JsonNode node) {
        return (node != null && node.isTextual() && !node.asText().isBlank()) ? node.asText() : null;
    }

    private boolean isHttpUrl(String candidate) {
        try {
            URI uri = new URI(candidate);
            String scheme = uri.getScheme();
            return uri.getHost() != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (Exception e) {
            return false;
        }
    }

    private record Downloaded(byte[] bytes, String contentType) {
    }

    /** 下载前校验协议与目标地址不解析到内网/环回地址，防止服务端被用作内网探测跳板；限制响应体大小与超时 */
    private Downloaded download(String url, long maxBytes) throws IOException {
        URI uri = URI.create(url);
        String host = uri.getHost();
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (isInternalAddress(addr)) {
                    throw new IOException("媒体地址解析到内网地址，已拒绝下载: " + host);
                }
            }
        } catch (java.net.UnknownHostException e) {
            throw new IOException("媒体地址域名解析失败: " + host, e);
        }
        Request request = new Request.Builder().url(url).get().build();
        try (Response resp = downloadClient.newCall(request).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("媒体下载失败，上游返回状态码: " + resp.code());
            }
            long declaredLength = resp.body().contentLength();
            if (declaredLength > maxBytes) {
                throw new IOException("媒体文件超出大小上限: " + declaredLength + " > " + maxBytes);
            }
            byte[] bytes = readAtMost(resp.body().byteStream(), maxBytes);
            String contentType = resp.header("Content-Type");
            return new Downloaded(bytes, contentType);
        }
    }

    private byte[] readAtMost(java.io.InputStream in, long maxBytes) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("媒体文件超出大小上限: > " + maxBytes);
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private boolean isInternalAddress(InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                || addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = addr.getAddress();
        if (bytes.length == 16) {
            if ((bytes[0] & 0xFE) == 0xFC) { // fc00::/7 unique local
                return true;
            }
            if (bytes[0] == 0x20 && bytes[1] == 0x02) { // 2002::/16 6to4
                return true;
            }
        }
        return false;
    }

    private String uploadBytes(byte[] bytes, String subFolder, String extension, String contentType) {
        String key = prefix + "/" + subFolder + "/" + System.currentTimeMillis() + "_" + randomToken() + "." + extension;
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(bytes.length);
        if (contentType != null && !contentType.isBlank()) {
            metadata.setContentType(contentType);
        }
        ossClient.putObject(new PutObjectRequest(bucket, key, new ByteArrayInputStream(bytes), metadata));
        return "https://" + bucket + "." + endpointHost + "/" + key;
    }

    private String randomToken() {
        byte[] bytes = new byte[8];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String sniffImageContentType(byte[] bytes) {
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50) {
            return "image/png";
        }
        if (bytes.length >= 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8) {
            return "image/jpeg";
        }
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return "image/gif";
        }
        if (bytes.length >= 12 && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return "image/png";
    }

    private String extensionForImage(String contentType) {
        if (contentType == null) return "png";
        String ct = contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("jpeg") || ct.contains("jpg")) return "jpg";
        if (ct.contains("webp")) return "webp";
        if (ct.contains("gif")) return "gif";
        return "png";
    }

    private String extensionForVideo(String contentType) {
        if (contentType == null) return "mp4";
        String ct = contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("webm")) return "webm";
        if (ct.contains("quicktime") || ct.contains("mov")) return "mov";
        return "mp4";
    }
}
