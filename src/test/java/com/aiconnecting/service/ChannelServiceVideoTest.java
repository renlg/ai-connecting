package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.repository.ChannelRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 视频测试内容下载相关的纯逻辑单测：URL 主机白名单校验、终止态解析。
 * downloadVideoUrl / testVideoContent 涉及真实网络调用，未覆盖。
 */
class ChannelServiceVideoTest {

    private final ChannelService service = new ChannelService(mock(ChannelRepository.class));
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Object invokeDownloadVideoUrl(String baseUrl, String videoUrl) throws Exception {
        Method method = ChannelService.class.getDeclaredMethod("downloadVideoUrl",
                String.class, String.class, String.class, String.class, String.class);
        method.setAccessible(true);
        try {
            return method.invoke(service, baseUrl, "test-api-key", "agnes", "video-1", videoUrl);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    void rejectsUrlOnDisallowedHost() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invokeDownloadVideoUrl("https://api.agnes-ai.cn", "http://169.254.169.254/latest/meta-data"));
        assertTrue(ex.getMessage().contains("非法的视频下载地址"));
    }

    @Test
    void rejectsMalformedUrl() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invokeDownloadVideoUrl("https://api.agnes-ai.cn", "not a url"));
        assertTrue(ex.getMessage().contains("非法的视频下载地址"));
    }

    @Test
    void rejectsNonHttpScheme() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invokeDownloadVideoUrl("https://api.agnes-ai.cn", "file:///etc/passwd"));
        assertTrue(ex.getMessage().contains("非法的视频下载地址"));
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

    @Test
    void findsNestedVideoStatus() throws Exception {
        JsonNode topLevel = objectMapper.readTree("{\"status\":\"failed\"}");
        assertEquals("failed", invokeFindVideoStatus(topLevel));

        JsonNode nested = objectMapper.readTree("{\"data\":{\"status\":\"completed\"}}");
        assertEquals("completed", invokeFindVideoStatus(nested));

        JsonNode videoNested = objectMapper.readTree("{\"video\":{\"status\":\"queued\"}}");
        assertEquals("queued", invokeFindVideoStatus(videoNested));

        JsonNode none = objectMapper.readTree("{}");
        assertNull(invokeFindVideoStatus(none));
    }
}
