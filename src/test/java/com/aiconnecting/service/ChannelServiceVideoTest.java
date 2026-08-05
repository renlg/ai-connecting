package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 视频测试内容下载相关的纯逻辑单测：URL 主机白名单、凭据隔离和终止态解析。
 */
class ChannelServiceVideoTest {

    private final ChannelService service = new ChannelService(null) {
        @Override
        List<InetAddress> resolveVideoHost(String host) throws IOException {
            return List.of(InetAddress.getByAddress(host, new byte[]{8, 8, 8, 8}));
        }
    };
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rejectsUrlOnDisallowedHost() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validateVideoDownloadTarget(
                        "https://api.agnes-ai.cn", "http://169.254.169.254/latest/meta-data"));
        assertTrue(ex.getMessage().contains("非法的视频下载地址"));
    }

    @Test
    void rejectsMalformedUrl() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validateVideoDownloadTarget("https://api.agnes-ai.cn", "not a url"));
        assertTrue(ex.getMessage().contains("非法的视频下载地址"));
    }

    @Test
    void rejectsNonHttpScheme() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validateVideoDownloadTarget("https://api.agnes-ai.cn", "file:///etc/passwd"));
        assertTrue(ex.getMessage().contains("非法的视频下载地址"));
    }

    @Test
    void allowsWhitelistedOutputHostWithoutChannelCredentials() {
        ChannelService.VideoDownloadTarget target = service.validateVideoDownloadTarget(
                "https://api.agnes-ai.cn", "https://cos-platform-outputs.agnes-ai.cn/videos/video.mp4");
        assertEquals("cos-platform-outputs.agnes-ai.cn", target.host());
        assertFalse(target.attachChannelCredentials());
    }

    @Test
    void allowsChannelHostAndAttachesChannelCredentials() {
        ChannelService.VideoDownloadTarget target = service.validateVideoDownloadTarget(
                "https://videos.example.com", "https://videos.example.com/v1/videos/id/content");
        assertTrue(target.attachChannelCredentials());
    }

    @Test
    void rejectsChannelHostOnHttpsToHttpDowngrade() {
        assertThrows(BusinessException.class, () -> service.validateVideoDownloadTarget(
                "https://videos.example.com", "http://videos.example.com/v1/videos/id/content"));
    }

    @Test
    void rejectsChannelHostOnDifferentEffectivePort() {
        assertThrows(BusinessException.class, () -> service.validateVideoDownloadTarget(
                "https://videos.example.com:8443", "https://videos.example.com/v1/videos/id/content"));
    }

    @Test
    void treatsExplicitDefaultPortAsSameOrigin() {
        ChannelService.VideoDownloadTarget target = service.validateVideoDownloadTarget(
                "https://videos.example.com", "https://videos.example.com:443/v1/videos/id/content");
        assertTrue(target.attachChannelCredentials());
    }

    private String invokeIsAgnesTypeChannel(String baseUrl, String channelType) throws Exception {
        Method method = ChannelService.class.getDeclaredMethod("isAgnesTypeChannel", String.class, String.class);
        method.setAccessible(true);
        return method.invoke(service, baseUrl, channelType).toString();
    }

    @Test
    void identifiesAgnesChannelByTypeOrHost() throws Exception {
        assertEquals("true", invokeIsAgnesTypeChannel("https://example.com", "agnes"));
        assertEquals("true", invokeIsAgnesTypeChannel("https://api.agnes-ai.cn", "openai"));
        assertEquals("true", invokeIsAgnesTypeChannel("https://apihub.agnes-ai.com", "openai"));
        assertEquals("false", invokeIsAgnesTypeChannel("https://evilagnes-ai.com", "openai"));
        assertEquals("false", invokeIsAgnesTypeChannel("https://example.com", "openai"));
    }

    private String invokeFindVideoStatus(JsonNode json) throws Exception {
        Method method = ChannelService.class.getDeclaredMethod("findVideoStatus", JsonNode.class);
        method.setAccessible(true);
        Object result = method.invoke(service, json);
        return result != null ? result.toString() : null;
    }

    private String invokeFindVideoUrl(JsonNode json) throws Exception {
        Method method = ChannelService.class.getDeclaredMethod("findVideoUrl", JsonNode.class);
        method.setAccessible(true);
        return (String) method.invoke(service, json);
    }

    @Test
    void findsTopLevelVideoUrlBeforeLegacyFallbacks() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {
                  "url": "https://cos-platform-outputs.agnes-ai.cn/videos/direct.mp4",
                  "metadata": {"url": "https://example.com/metadata.mp4"},
                  "remixed_from_video_id": "legacy-video-id"
                }
                """);
        assertEquals("https://cos-platform-outputs.agnes-ai.cn/videos/direct.mp4", invokeFindVideoUrl(response));
    }

    @Test
    void findsWrappedTopLevelVideoUrlAndLegacyFallbacks() throws Exception {
        JsonNode wrapped = objectMapper.readTree("{\"data\":{\"url\":\"https://example.com/wrapped.mp4\"}}");
        assertEquals("https://example.com/wrapped.mp4", invokeFindVideoUrl(wrapped));

        JsonNode metadata = objectMapper.readTree("{\"metadata\":{\"url\":\"https://example.com/metadata.mp4\"}}");
        assertEquals("https://example.com/metadata.mp4", invokeFindVideoUrl(metadata));

        JsonNode remixed = objectMapper.readTree("{\"remixed_from_video_id\":\"legacy-video-id\"}");
        assertNull(invokeFindVideoUrl(remixed));

        JsonNode invalidDirectUrl = objectMapper.readTree(
                "{\"url\":\"opaque-video-id\",\"metadata\":{\"url\":\"https://example.com/metadata.mp4\"}}");
        assertEquals("https://example.com/metadata.mp4", invokeFindVideoUrl(invalidDirectUrl));
    }

    @Test
    void findsNestedVideoStatus() throws Exception {
        JsonNode topLevel = objectMapper.readTree("{\"status\":\"failed\"}");
        assertEquals("failed", invokeFindVideoStatus(topLevel));

        JsonNode nested = objectMapper.readTree("{\"data\":{\"status\":\"completed\"}}");
        assertEquals("completed", invokeFindVideoStatus(nested));

        JsonNode videoNested = objectMapper.readTree("{\"video\":{\"status\":\"queued\"}}");
        assertEquals("queued", invokeFindVideoStatus(videoNested));

        JsonNode nestedState = objectMapper.readTree("{\"data\":{\"state\":\"in_progress\"}}");
        assertEquals("in_progress", invokeFindVideoStatus(nestedState));

        JsonNode none = objectMapper.readTree("{}");
        assertNull(invokeFindVideoStatus(none));
    }

    @Test
    void reportsTerminalFailureImmediatelyWithUpstreamMessage() throws Exception {
        JsonNode failed = objectMapper.readTree(
                "{\"data\":{\"state\":\"failed\",\"error\":{\"message\":\"render rejected\"}}}");
        assertEquals("上游视频任务失败 (failed): render rejected",
                service.terminalVideoFailureMessage(failed));
        assertNull(service.terminalVideoFailureMessage(
                objectMapper.readTree("{\"status\":\"in_progress\"}")));
    }

    @Test
    void scrubsSignedUrlQueryParametersFromErrorSummary() throws Exception {
        Method method = ChannelService.class.getDeclaredMethod("abbreviateTestError", String.class, int.class);
        method.setAccessible(true);
        String body = "https://output.example/video.mp4?X-Amz-Signature=secret-signature"
                + "&X-Amz-Credential=secret-credential&X-Amz-Algorithm=secret-algorithm"
                + "&X-Amz-Date=secret-date&X-Amz-Security-Token=secret-security-token"
                + "&token=secret-token&sig=secret-sig&signature=secret-signature-2&expires=secret-expiry";

        String scrubbed = (String) method.invoke(service, body, 2000);

        assertFalse(scrubbed.contains("secret-"));
        assertEquals(9, scrubbed.split("\\[REDACTED]", -1).length - 1);
    }
}
