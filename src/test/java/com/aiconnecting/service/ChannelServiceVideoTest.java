package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 视频测试内容下载相关的纯逻辑单测：URL 主机白名单、凭据隔离和终止态解析。
 */
class ChannelServiceVideoTest {

    private final ChannelService service = new ChannelService(null);
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

    private String invokeIsAgnesTypeChannel(String baseUrl, String channelType) throws Exception {
        Method method = ChannelService.class.getDeclaredMethod("isAgnesTypeChannel", String.class, String.class);
        method.setAccessible(true);
        return method.invoke(service, baseUrl, channelType).toString();
    }

    @Test
    void identifiesAgnesChannelByTypeOrHost() throws Exception {
        assertEquals("true", invokeIsAgnesTypeChannel("https://example.com", "agnes"));
        assertEquals("true", invokeIsAgnesTypeChannel("https://api.agnes-ai.cn", "openai"));
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
        assertEquals("legacy-video-id", invokeFindVideoUrl(remixed));
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
}
