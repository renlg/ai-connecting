package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiRelayServiceTest {

    private final OpenAiRelayService service = new OpenAiRelayService(null, null, null, null, null, null);

    @Test
    void speechEstimateRejectsInputOver4096Characters() {
        assertEquals(293, (int) ReflectionTestUtils.invokeMethod(
                service, "estimateSpeechSeconds", "a".repeat(4096), 1.0));

        BusinessException error = assertThrows(BusinessException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "estimateSpeechSeconds", "a".repeat(4097), 1.0));
        assertEquals(413, error.getCode());
    }
}
