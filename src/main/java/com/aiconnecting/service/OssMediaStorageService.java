package com.aiconnecting.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.CannedAccessControlList;
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

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 将上游返回的媒体产物（图片/音频/视频）落盘到阿里云 OSS，并把响应中的上游 URL / base64
 * 替换为 OSS 公网直链，使客户端不再依赖上游（可能存在有效期或被限流的）地址。
 * OSS 未启用或凭据缺失时直接跳过重写，响应按上游原样透传，不影响主流程可用性；
 * 单个媒体项下载/上传失败同样只记录日志并保留该项原始字段，不影响响应中其它已计费产物的返回。
 *
 * 注意：本服务上传对象后会显式将 ACL 设置为 public-read 并直接返回匿名 HTTPS 直链，
 * 因此使用的 OSS Bucket 必须允许公共读（Bucket 策略/ACL 允许 public-read），
 * 否则客户端访问返回的地址会得到 403。若 Bucket 为私有读写，需改造为签名 URL 方案。
 */
@Slf4j
@Service
public class OssMediaStorageService {

    private static final Set<String> VIDEO_COMPLETED_STATUSES = Set.of("completed", "succeeded");

    private static final long IMAGE_MAX_BYTES = 25L * 1024 * 1024;
    private static final long AUDIO_MAX_BYTES = 50L * 1024 * 1024;
    private static final long VIDEO_MAX_BYTES = 200L * 1024 * 1024;

    /** 探测内容类型所需的前置字节数，仅用于 mark/reset 窥探，不影响后续流式上传 */
    private static final int SNIFF_BYTES = 16;

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
                // 整体下载耗时上限：仅有连接/读超时无法阻止“慢速涓流”服务端长期占用请求线程
                // （尤其是最大 200MB 的视频），callTimeout 覆盖包括连接、请求、响应在内的完整调用耗时
                .callTimeout(Duration.ofSeconds(180))
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

    /**
     * 精确计算 base64 解码后的字节数（忽略末尾空白，并按结尾 '=' padding 数量修正），
     * 而非粗略的 ceil(编码长度*3/4)，避免临界大小（如恰好 25MiB）的合法数据被过度估算而误判超限。
     */
    private long estimateBase64DecodedBytes(String base64) {
        int end = base64.length();
        while (end > 0 && Character.isWhitespace(base64.charAt(end - 1))) {
            end--;
        }
        int padding = 0;
        int i = end - 1;
        while (i >= 0 && padding < 2 && base64.charAt(i) == '=') {
            padding++;
            i--;
        }
        return (end / 4L) * 3L - padding;
    }

    private boolean rewriteImageItem(ObjectNode item) throws IOException {
        JsonNode b64Node = item.get("b64_json");
        if (b64Node != null && b64Node.isTextual() && !b64Node.asText().isBlank()) {
            String base64 = b64Node.asText();
            // 解码前按编码长度精确计算解码后大小（而非粗略 ceil(len*3/4)，否则临界大小的合法数据
            // 会因未扣除末尾 '=' padding 被高估 1-2 字节而被误判超限），避免对超大 base64 字符串
            // 直接整体解码造成堆内存耗尽
            long estimatedBytes = estimateBase64DecodedBytes(base64);
            if (estimatedBytes > IMAGE_MAX_BYTES) {
                throw new IOException("图片 base64 数据超出大小上限（编码长度估算 ~" + estimatedBytes + " > " + IMAGE_MAX_BYTES + "）");
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException e) {
                throw new IOException("图片 base64 数据格式无效", e);
            }
            // 估算基于编码长度，解码后仍需校验真实字节数，防止 padding/估算误差导致超限对象被放行
            if (bytes.length > IMAGE_MAX_BYTES) {
                throw new IOException("图片 base64 解码后超出大小上限: " + bytes.length + " > " + IMAGE_MAX_BYTES);
            }
            String contentType = sniffImageContentType(bytes);
            String ossUrl = uploadBytes(bytes, "images", extensionForImage(contentType), contentType);
            item.remove("b64_json");
            item.put("url", ossUrl);
            return true;
        }
        JsonNode urlNode = item.get("url");
        if (urlNode != null && urlNode.isTextual() && isHttpUrl(urlNode.asText())) {
            String ossUrl = downloadAndUpload(urlNode.asText(), IMAGE_MAX_BYTES, "images",
                    this::sniffImageContentType, "image/png", this::extensionForImage);
            item.put("url", ossUrl);
            return true;
        }
        return false;
    }

    /**
     * 视频任务响应重写：仅当任务处于终态 completed/succeeded 且存在可下载的播放地址时才重写。
     * 用于轮询响应 (GET /v1/videos/{id}) 及少数上游同步即返回完成态视频的创建响应。
     * 失败时记录日志并保留上游原始地址，不影响状态透传与已完成的计费结算。
     *
     * @param existingOssUrl 若任务此前已上传过 OSS（见 VideoTask.ossVideoUrl），传入缓存的直链，
     *                        本次直接复用回写、跳过重复下载上传；首次上传传 null
     * @param onNewOssUrl     首次上传成功后的回调，供调用方持久化新的 OSS 直链，避免下次轮询重复下载
     */
    public String rewriteVideoResponseIfCompleted(String response, String existingOssUrl, Consumer<String> onNewOssUrl) {
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
        ObjectNode obj = (ObjectNode) root;
        try {
            if (existingOssUrl != null && !existingOssUrl.isBlank()) {
                if (!replaceFirstVideoUrl(obj, existingOssUrl)) {
                    return response;
                }
                return objectMapper.writeValueAsString(root);
            }
            String uploadedUrl = uploadFirstVideoUrl(obj);
            if (uploadedUrl == null) {
                return response;
            }
            if (onNewOssUrl != null) {
                onNewOssUrl.accept(uploadedUrl);
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("视频媒体转存 OSS 失败，保留上游原始地址: {}", e.getMessage());
            return response;
        }
    }

    /**
     * 从上游视频任务响应中解析终态状态，兼容顶层 status/state 及嵌套在 data/video 下的同名字段。
     * 供本类的 OSS 重写与 {@link OpenAiRelayService} 的计费结算共用同一套解析逻辑，
     * 避免两处识别范围不一致导致「已转存 OSS 但从未结算」的计费遗漏。
     */
    public String extractVideoStatus(JsonNode json) {
        return findVideoStatus(json);
    }

    /** 依次尝试 OpenAI 兼容视频任务对象中出现过的地址位置，命中第一个即下载上传并回写，返回新 OSS 直链；均未命中返回 null */
    private String uploadFirstVideoUrl(ObjectNode root) throws IOException {
        String uploaded = uploadUrlFieldIfPresent(root, "url");
        if (uploaded != null) {
            return uploaded;
        }
        ObjectNode data = asObject(root.get("data"));
        if (data != null) {
            uploaded = uploadUrlFieldIfPresent(data, "url");
            if (uploaded != null) {
                return uploaded;
            }
        }
        ObjectNode metadata = asObject(root.get("metadata"));
        if (metadata != null) {
            uploaded = uploadUrlFieldIfPresent(metadata, "url");
            if (uploaded != null) {
                return uploaded;
            }
        }
        if (data != null) {
            ObjectNode dataMetadata = asObject(data.get("metadata"));
            if (dataMetadata != null) {
                uploaded = uploadUrlFieldIfPresent(dataMetadata, "url");
                if (uploaded != null) {
                    return uploaded;
                }
            }
        }
        ObjectNode video = asObject(root.get("video"));
        if (video != null) {
            ObjectNode videoMetadata = asObject(video.get("metadata"));
            if (videoMetadata != null) {
                uploaded = uploadUrlFieldIfPresent(videoMetadata, "url");
                if (uploaded != null) {
                    return uploaded;
                }
            }
        }
        return null;
    }

    /** 与 {@link #uploadFirstVideoUrl} 相同的位置探测顺序，但不下载，直接用缓存的 OSS 直链覆盖 */
    private boolean replaceFirstVideoUrl(ObjectNode root, String replacementUrl) {
        if (setUrlFieldIfPresent(root, "url", replacementUrl)) {
            return true;
        }
        ObjectNode data = asObject(root.get("data"));
        if (data != null && setUrlFieldIfPresent(data, "url", replacementUrl)) {
            return true;
        }
        ObjectNode metadata = asObject(root.get("metadata"));
        if (metadata != null && setUrlFieldIfPresent(metadata, "url", replacementUrl)) {
            return true;
        }
        if (data != null) {
            ObjectNode dataMetadata = asObject(data.get("metadata"));
            if (dataMetadata != null && setUrlFieldIfPresent(dataMetadata, "url", replacementUrl)) {
                return true;
            }
        }
        ObjectNode video = asObject(root.get("video"));
        if (video != null) {
            ObjectNode videoMetadata = asObject(video.get("metadata"));
            if (videoMetadata != null && setUrlFieldIfPresent(videoMetadata, "url", replacementUrl)) {
                return true;
            }
        }
        return false;
    }

    private ObjectNode asObject(JsonNode node) {
        return node != null && node.isObject() ? (ObjectNode) node : null;
    }

    private String uploadUrlFieldIfPresent(ObjectNode holder, String field) throws IOException {
        JsonNode node = holder.get(field);
        if (node == null || !node.isTextual() || !isHttpUrl(node.asText())) {
            return null;
        }
        String ossUrl = downloadAndUpload(node.asText(), VIDEO_MAX_BYTES, "videos",
                null, "video/mp4", this::extensionForVideo);
        holder.put(field, ossUrl);
        return ossUrl;
    }

    private boolean setUrlFieldIfPresent(ObjectNode holder, String field, String replacementUrl) {
        JsonNode node = holder.get(field);
        if (node == null || !node.isTextual() || !isHttpUrl(node.asText())) {
            return false;
        }
        holder.put(field, replacementUrl);
        return true;
    }

    /**
     * 优先取嵌套任务对象（data/video）自身的 status/state，只有当响应中不存在这类嵌套任务对象、
     * 或该对象自身未携带 status/state 时才回退到顶层同名字段——防止顶层泛化状态（如轮询壳层的
     * "processing"）遮蔽内层真正的任务终态（如 data.status="completed"），反之亦然。
     */
    private String findVideoStatus(JsonNode json) {
        String status = nestedTaskStatus(json.path("data"));
        if (status != null) return status;
        status = nestedTaskStatus(json.path("video"));
        if (status != null) return status;
        status = textOrNull(json.path("status"));
        if (status != null) return status;
        return textOrNull(json.path("state"));
    }

    private String nestedTaskStatus(JsonNode taskObject) {
        if (taskObject == null || !taskObject.isObject()) {
            return null;
        }
        String status = textOrNull(taskObject.path("status"));
        if (status != null) return status;
        return textOrNull(taskObject.path("state"));
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

    /**
     * 下载前校验协议与目标地址不解析到内网/环回地址，防止服务端被用作内网探测跳板；
     * 下载内容不落地为完整 byte[]，而是将响应体包装为受 maxBytes 限制的 InputStream 直接流式传给
     * OSS SDK 的 putObject(InputStream, ...)，避免为最大 200MB 的视频在应用内存中额外持有一份完整拷贝。
     * contentType 缺失时按需窥探响应体前若干字节（mark/reset，不消耗流式上传的数据）用于嗅探，
     * 嗅探仍未识别时回退到 defaultContentType。
     */
    /** 包可见性（非 private）便于测试通过 Mockito spy 打桩，跳过真实网络下载验证地址位置识别等逻辑 */
    String downloadAndUpload(String url, long maxBytes, String subFolder,
                              Function<byte[], String> contentTypeSniffer, String defaultContentType,
                              Function<String, String> extensionResolver) throws IOException {
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
            String contentType = resp.header("Content-Type");
            InputStream raw = resp.body().byteStream();
            BufferedInputStream buffered = new BufferedInputStream(raw, Math.max(SNIFF_BYTES, 8192));
            if ((contentType == null || contentType.isBlank()) && contentTypeSniffer != null) {
                buffered.mark(SNIFF_BYTES);
                byte[] head = buffered.readNBytes(SNIFF_BYTES);
                buffered.reset();
                contentType = contentTypeSniffer.apply(head);
            }
            if (contentType == null || contentType.isBlank()) {
                contentType = defaultContentType;
            }
            CappedInputStream capped = new CappedInputStream(buffered, maxBytes);
            String extension = extensionResolver.apply(contentType);
            return uploadStream(capped, declaredLength >= 0 ? declaredLength : -1, subFolder, extension, contentType);
        }
    }

    /** 包装输入流并在读取总量超过 maxBytes 时立即抛出异常，用于流式上传场景下的大小上限强制 */
    private static final class CappedInputStream extends FilterInputStream {
        private final long maxBytes;
        private long count;

        CappedInputStream(InputStream in, long maxBytes) {
            super(in);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                count++;
                checkLimit();
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
                checkLimit();
            }
            return n;
        }

        private void checkLimit() throws IOException {
            if (count > maxBytes) {
                throw new IOException("媒体文件超出大小上限: > " + maxBytes);
            }
        }
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
        // ACL 作为 PutObject 请求的一部分随对象原子写入（而非上传成功后再发一次独立的 setObjectAcl 调用），
        // 若 ACL 无法设置，putObject 本身失败并抛出异常，调用方据此不会缓存/返回任何 URL，
        // 不会出现"对象已存在但 ACL 未生效、403 直链被写入 oss_url"的中间态
        metadata.setObjectAcl(CannedAccessControlList.PublicRead);
        ossClient.putObject(new PutObjectRequest(bucket, key, new ByteArrayInputStream(bytes), metadata));
        return "https://" + bucket + "." + endpointHost + "/" + key;
    }

    private String uploadStream(InputStream in, long contentLength, String subFolder, String extension, String contentType) {
        String key = prefix + "/" + subFolder + "/" + System.currentTimeMillis() + "_" + randomToken() + "." + extension;
        ObjectMetadata metadata = new ObjectMetadata();
        if (contentLength >= 0) {
            metadata.setContentLength(contentLength);
        }
        if (contentType != null && !contentType.isBlank()) {
            metadata.setContentType(contentType);
        }
        // 同上：ACL 随 PutObject 原子写入，设置失败则整个上传失败，不会产生不可访问的已缓存 URL
        metadata.setObjectAcl(CannedAccessControlList.PublicRead);
        ossClient.putObject(new PutObjectRequest(bucket, key, in, metadata));
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
