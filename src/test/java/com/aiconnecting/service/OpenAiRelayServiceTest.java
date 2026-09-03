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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiRelayServiceTest {

    private final OpenAiRelayService service = new OpenAiRelayService(null, null, null, null, null, null, null);
    private final RelaySupport support = new RelaySupport(null, null, null, null, null, null, null, null);
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
        RelaySupport relaySupport = relaySupport(router);
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
    }

    @Test
    void videoTransformationRunsOnceBeforePrechargeAndRetry() {
        RelaySupport relaySupport = mock(RelaySupport.class);
        OpenAiRelayService relayService = relayService(relaySupport);
        RequestBodyTransformerRegistry registry = mock(RequestBodyTransformerRegistry.class);
        FailureLogService failureLogs = mock(FailureLogService.class);
        ReflectionTestUtils.setField(relayService, "requestBodyTransformerRegistry", registry);
        ReflectionTestUtils.setField(relayService, "failureLogService", failureLogs);
        RelaySupport.RelayContext ctx = new RelaySupport.RelayContext(
                null, "agnes-video-v2.0", 1, null, ModelConfig.builder().id(42L).build());
        when(relaySupport.validateAndPrepare("sk-test", "agnes-video-v2.0", "video")).thenReturn(ctx);
        BusinessException invalid = new BusinessException(400, "duration 参数必须是正整数秒数",
                "duration must be a positive integer number of seconds");
        when(registry.transform("agnes-video-v2.0", "{\"duration\":0}")).thenThrow(invalid);

        BusinessException thrown = assertThrows(BusinessException.class, () -> relayService.relayMediaRequest(
                "sk-test", "/v1/videos", "{\"duration\":0}", "agnes-video-v2.0", null, "video"));

        assertEquals(invalid, thrown);
        verify(registry, times(1)).transform("agnes-video-v2.0", "{\"duration\":0}");
        verify(relaySupport, never()).prepareVideoCharge(any(), any());
        verify(failureLogs, never()).recordChannelFailure(any(), any(), any(), any(), any(), any());
    }

    @Test
    void mediaSwitchableFailureStillRetriesAnotherChannel() {
        ChannelRouter router = mock(ChannelRouter.class);
        RelaySupport relaySupport = relaySupport(router);
        OpenAiRelayService relayService = relayService(relaySupport);
        FailureLogService failureLogService = mock(FailureLogService.class);
        ReflectionTestUtils.setField(relayService, "failureLogService", failureLogService);
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
        verify(relaySupport).dispatchRelayFailure(eq(7L), isNull(), eq(42L), eq(unavailable));
        verify(failureLogService).recordChannelFailure(isNull(), eq(7L), eq(42L), isNull(), isNull(), eq(unavailable));
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

    @Test
    void quotaFailureRecordsChannelCircuit() {
        RelaySupport relaySupport = relaySupport(mock(ChannelRouter.class));
        String body = "{\"error\":{\"message\":\"Free quota exhausted\"}}";
        BusinessException quota = BusinessException.upstream(403, "upstream quota exhausted", body, null);

        relaySupport.dispatchRelayFailure(7L, null, 42L, quota);
    }

    private RelaySupport relaySupport(ChannelRouter router) {
        RelaySupport relaySupport = org.mockito.Mockito.spy(new RelaySupport(
                null, router, null, null, null, null, null, null));
        doReturn(false).when(relaySupport).isChannelRateLimited(
                any(), org.mockito.ArgumentMatchers.anyString());
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
