package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.entity.Channel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
