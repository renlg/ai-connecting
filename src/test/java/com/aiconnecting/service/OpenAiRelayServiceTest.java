package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.ModelConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiRelayServiceTest {

    private final OpenAiRelayService service = new OpenAiRelayService(null, null, null, null, null, null, null);
    private final RelaySupport support = new RelaySupport(null, null, null, null, null, null, null, null, null);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void speechEstimateRejectsInputOver4096Characters() {
        assertEquals(293, (int) ReflectionTestUtils.invokeMethod(
                service, "estimateSpeechSeconds", "a".repeat(4096), 1.0));

        BusinessException error = assertThrows(BusinessException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "estimateSpeechSeconds", "a".repeat(4097), 1.0));
        assertEquals(413, error.getCode());
    }

    @Test
    void imageRequestDropsResponseFormatForAgnesHost() throws Exception {
        Channel channel = Channel.builder()
                .type("openai")
                .baseUrl("https://api.agnes-ai.cn")
                .build();

        String result = support.prepareImageRequestBody(channel,
                "{\"model\":\"agnes-t2i-general-model\",\"prompt\":\"cat\",\"response_format\":\"b64_json\"}");
        JsonNode body = objectMapper.readTree(result);

        assertFalse(body.has("response_format"));
        assertEquals("agnes-t2i-general-model", body.get("model").asText());
        assertEquals("cat", body.get("prompt").asText());
    }

    @Test
    void imageRequestDropsResponseFormatForAgnesChannelType() throws Exception {
        Channel channel = Channel.builder()
                .type("agnes")
                .baseUrl("https://example.com")
                .build();

        JsonNode body = objectMapper.readTree(support.prepareImageRequestBody(channel,
                "{\"response_format\":\"url\"}"));

        assertFalse(body.has("response_format"));
    }

    @Test
    void imageRequestKeepsResponseFormatForOtherChannels() {
        Channel channel = Channel.builder()
                .type("openai")
                .baseUrl("https://api.openai.com")
                .build();
        String request = "{\"response_format\":\"b64_json\"}";

        assertEquals(request, support.prepareImageRequestBody(channel, request));
        assertTrue(support.isAgnesTypeChannel(Channel.builder()
                .type("openai")
                .baseUrl("https://images.agnes-ai.cn/v1")
                .build()));
        assertTrue(support.isAgnesTypeChannel(Channel.builder()
                .type("openai")
                .baseUrl("https://apihub.agnes-ai.com")
                .build()));
    }

    @Test
    void mediaContentPolicyViolationFailsImmediatelyWithoutRetryOrHealthRecord() {
        ChannelRouter router = mock(ChannelRouter.class);
        ChannelHealthTracker healthTracker = mock(ChannelHealthTracker.class);
        RelaySupport relaySupport = relaySupport(router, healthTracker);
        OpenAiRelayService relayService = relayService(relaySupport);
        Channel first = Channel.builder().id(7L).build();
        Channel second = Channel.builder().id(9L).build();
        when(router.selectChannel(eq("media-model"), anySet(), anyInt())).thenReturn(first, second);

        @SuppressWarnings("unchecked")
        Function<Channel, String> upstreamCall = mock(Function.class);
        String body = "{\"error\":{\"message\":\"Unable to generate this content\","
                + "\"type\":\"invalid_request_error\",\"param\":\"prompt\","
                + "\"code\":\"content_policy_violation\"}}";
        BusinessException refusal = BusinessException.upstream(400, "上游 API 错误: " + body, body, null);
        when(upstreamCall.apply(first)).thenThrow(refusal);

        BusinessException thrown = assertThrows(BusinessException.class, () ->
                ReflectionTestUtils.invokeMethod(relayService, "forwardWithRetry", context(), upstreamCall));

        assertEquals(refusal, thrown);
        assertEquals(body, thrown.getUpstreamResponseBody());
        verify(upstreamCall, times(1)).apply(any());
        verify(router, times(1)).selectChannel(eq("media-model"), anySet(), anyInt());
        verify(relaySupport, never()).dispatchRelayFailure(any(), any(), any(), any());
        verify(healthTracker, never()).recordFailure(any(), any(), any());
    }

    @Test
    void mediaSwitchableFailureStillRetriesAnotherChannel() {
        ChannelRouter router = mock(ChannelRouter.class);
        ChannelHealthTracker healthTracker = mock(ChannelHealthTracker.class);
        RelaySupport relaySupport = relaySupport(router, healthTracker);
        OpenAiRelayService relayService = relayService(relaySupport);
        Channel first = Channel.builder().id(7L).build();
        Channel second = Channel.builder().id(9L).build();
        when(router.selectChannel(eq("media-model"), anySet(), anyInt())).thenReturn(first, second);

        @SuppressWarnings("unchecked")
        Function<Channel, String> upstreamCall = mock(Function.class);
        BusinessException unavailable = BusinessException.upstream(503, "upstream unavailable", "busy", null);
        when(upstreamCall.apply(first)).thenThrow(unavailable);
        when(upstreamCall.apply(second)).thenReturn("{\"data\":[]}");

        Object result = ReflectionTestUtils.invokeMethod(relayService, "forwardWithRetry", context(), upstreamCall);

        assertNotNull(result);
        verify(upstreamCall, times(2)).apply(any());
        verify(router, times(2)).selectChannel(eq("media-model"), anySet(), anyInt());
        verify(relaySupport).dispatchRelayFailure(eq(7L), eq(42L), eq(unavailable), any());
        verify(healthTracker).recordFailure(eq(7L), any(), eq("upstream unavailable"));
        verify(healthTracker).recordSuccess(9L);
    }

    @Test
    void moderationCompatibilitySignalsAreFastFail() {
        String messageOnly = "{\"error\":{\"message\":\"Unable to generate this content\"}}";
        String promptRejection = "{\"error\":{\"message\":\"Rejected\","
                + "\"type\":\"invalid_request_error\",\"param\":\"prompt\"}}";

        assertFalse(FailureClassifier.isSwitchable(400, messageOnly));
        assertFalse(FailureClassifier.isSwitchable(400, promptRejection));
        assertTrue(FailureClassifier.isSwitchable(400,
                "{\"error\":{\"message\":\"generic invalid request\",\"type\":\"invalid_request_error\"}}"));
    }

    private RelaySupport relaySupport(ChannelRouter router, ChannelHealthTracker healthTracker) {
        RelaySupport relaySupport = org.mockito.Mockito.spy(new RelaySupport(
                null, router, healthTracker, null, null, null, null, null, null));
        doReturn(false).when(relaySupport).isChannelRateLimited(any());
        return relaySupport;
    }

    private OpenAiRelayService relayService(RelaySupport relaySupport) {
        return new OpenAiRelayService(relaySupport, null, null, null, null, null, null);
    }

    private RelaySupport.RelayContext context() {
        return new RelaySupport.RelayContext(null, "media-model", 1, null,
                ModelConfig.builder().id(42L).build());
    }
}
