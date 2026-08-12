package com.aiconnecting.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.test.util.ReflectionTestUtils;
import com.aiconnecting.service.FailureLogService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GlobalExceptionHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(mapper);

    @Test
    void genericExceptionsUseRequestedProtocolWithoutUpstreamTraceId() throws Exception {
        assertGenericEnvelope("/v1/chat/completions", "type", "api_error");
        assertGenericEnvelope("/v1/messages", "type", "api_error");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/models/gemini:generateContent");
        ResponseEntity<?> response = handler.handleException(new IllegalArgumentException("bad json"), request,
                new MockHttpServletResponse());
        JsonNode body = mapper.valueToTree(response.getBody());
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(body.path("error").path("code").asInt()).isEqualTo(500);
        assertThat(body.path("error").path("status").asText()).isEqualTo("INTERNAL");
        assertThat(body.path("error").has("traceId")).isFalse();
    }

    @Test
    void preservesUnlistedFiveHundredStatus() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        ResponseEntity<?> response = handler.handleBusinessException(
                new BusinessException(505, "版本不支持", "Version not supported"), request,
                new MockHttpServletResponse());
        JsonNode body = mapper.valueToTree(response.getBody());
        assertThat(response.getStatusCode().value()).isEqualTo(505);
        assertThat(body.path("error").path("code").asInt()).isEqualTo(505);
    }

    @Test
    void specializedFrameworkExceptionsAlsoUseRelayProtocolEnvelope() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/models/gemini:generateContent");
        ResponseEntity<?> response = handler.handleMissingParam(
                new MissingServletRequestParameterException("key", "String"), request);
        JsonNode body = mapper.valueToTree(response.getBody());
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(body.path("error").path("code").asInt()).isEqualTo(400);
        assertThat(body.path("error").path("status").asText()).isEqualTo("INVALID_ARGUMENT");
        assertThat(body.path("error").has("traceId")).isFalse();
    }

    @Test
    void upstreamFailureIsSentToAsyncFailureLoggerAtUnifiedOutlet() throws Exception {
        FailureLogService failureLogService = mock(FailureLogService.class);
        GlobalExceptionHandler loggingHandler = new GlobalExceptionHandler(mapper);
        ReflectionTestUtils.setField(loggingHandler, "failureLogService", failureLogService);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        String upstreamBody = "{\"error\":\"invalid request\"}";

        loggingHandler.handleBusinessException(
                BusinessException.upstream(400, "上游 API 错误", "Upstream API error", upstreamBody, null),
                request, new MockHttpServletResponse());

        verify(failureLogService).record(request, 400, SseUtils.GENERIC_UPSTREAM_ERROR_MESSAGE,
                "Upstream API error: 400 - " + upstreamBody);
    }

    private void assertGenericEnvelope(String uri, String field, String value) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        ResponseEntity<?> response = handler.handleException(new IllegalArgumentException("bad json"), request,
                new MockHttpServletResponse());
        JsonNode body = mapper.valueToTree(response.getBody());
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(body.path("error").path(field).asText()).isEqualTo(value);
        assertThat(body.path("error").has("traceId")).isFalse();
    }
}
