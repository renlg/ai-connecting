package com.aiconnecting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RelayProtocolAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RelayProtocolAdapter adapter = new RelayProtocolAdapter(mapper);

    @Test
    void buildsCanonicalErrorEnvelopesAndOnlyAddsTraceIdForUpstreamErrors() {
        MDC.put("traceId", "trace-test");
        try {
            JsonNode openAi = adapter.errorEnvelope(RelayProtocol.OPENAI, 404, "missing", false);
            assertThat(openAi.path("error").path("message").asText()).isEqualTo("missing");
            assertThat(openAi.path("error").path("type").asText()).isEqualTo("invalid_request_error");
            assertThat(openAi.path("error").path("code").asInt()).isEqualTo(404);
            assertThat(openAi.path("error").has("traceId")).isFalse();

            JsonNode claude = adapter.errorEnvelope(RelayProtocol.CLAUDE, 502, "upstream", true);
            assertThat(claude.path("type").asText()).isEqualTo("error");
            assertThat(claude.path("error").path("type").asText()).isEqualTo("api_error");
            assertThat(claude.path("error").path("traceId").asText()).isEqualTo("trace-test");

            JsonNode gemini = adapter.errorEnvelope(RelayProtocol.GEMINI, 504, "timeout", true);
            assertThat(gemini.path("error").path("code").asInt()).isEqualTo(504);
            assertThat(gemini.path("error").path("status").asText()).isEqualTo("DEADLINE_EXCEEDED");
            assertThat(gemini.path("error").path("traceId").asText()).isEqualTo("trace-test");
        } finally {
            MDC.remove("traceId");
        }
    }

    @Test
    void writesProtocolCompleteSseErrors() throws Exception {
        MockHttpServletResponse openAi = new MockHttpServletResponse();
        adapter.writeSseError(RelayProtocol.OPENAI, openAi, 500, "broken", false);
        assertThat(openAi.getContentAsString()).isEqualTo(
                "event: error\ndata: {\"error\":{\"message\":\"broken\",\"type\":\"api_error\",\"code\":500}}\n\n");

        MockHttpServletResponse claude = new MockHttpServletResponse();
        adapter.writeSseError(RelayProtocol.CLAUDE, claude, 502, "broken", false);
        assertThat(claude.getContentAsString()).isEqualTo(
                "event: error\ndata: {\"error\":{\"type\":\"api_error\",\"message\":\"broken\"},\"type\":\"error\"}\n\n");

        MockHttpServletResponse gemini = new MockHttpServletResponse();
        adapter.writeSseError(RelayProtocol.GEMINI, gemini, 503, "broken", false);
        assertThat(gemini.getContentAsString()).isEqualTo(
                "data: {\"error\":{\"code\":503,\"message\":\"broken\",\"status\":\"UNAVAILABLE\"}}\n\n");
    }

    @Test
    void adaptsClaudeRequestToUnifiedAndOpenAiWithoutDroppingCoreFields() throws Exception {
        String body = "{\"model\":\"public-group\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                + "\"max_tokens\":321,\"temperature\":0.25,\"stream\":true}";
        UnifiedRelayRequest request = adapter.adaptRequest(
                RelayProtocol.CLAUDE, "/v1/messages", body, null);

        assertThat(request.model()).isEqualTo("public-group");
        assertThat(request.stream()).isTrue();
        assertThat(request.maxTokens()).isEqualTo(321);
        assertThat(request.temperature()).isEqualTo(0.25);
        assertThat(request.content()).hasSize(1);

        JsonNode upstream = mapper.readTree(adapter.toUpstreamBody(
                request, "claude-member", RelayProtocol.OPENAI));
        assertThat(upstream.path("model").asText()).isEqualTo("claude-member");
        assertThat(upstream.path("messages").path(0).path("content").asText()).isEqualTo("hi");
        assertThat(upstream.path("max_tokens").asInt()).isEqualTo(321);
    }

    @Test
    void adaptsGeminiPathModelAndRoundTripsOpenAiResponse() throws Exception {
        String body = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hello\"}]}],"
                + "\"generationConfig\":{\"maxOutputTokens\":77,\"temperature\":0.4}}";
        UnifiedRelayRequest request = adapter.adaptRequest(RelayProtocol.GEMINI,
                "/v1/models/public-group:generateContent", body, "public-group");

        assertThat(request.model()).isEqualTo("public-group");
        assertThat(request.maxTokens()).isEqualTo(77);
        assertThat(request.temperature()).isEqualTo(0.4);
        JsonNode upstream = mapper.readTree(adapter.toUpstreamBody(
                request, "gemini-member", RelayProtocol.OPENAI));
        assertThat(upstream.path("model").asText()).isEqualTo("gemini-member");
        assertThat(upstream.path("messages").path(0).path("content").asText()).isEqualTo("hello");

        String openAi = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5}}";
        JsonNode gemini = mapper.readTree(adapter.fromUpstreamResponse(
                openAi, RelayProtocol.OPENAI, RelayProtocol.GEMINI));
        assertThat(gemini.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText())
                .isEqualTo("ok");
        assertThat(gemini.path("usageMetadata").path("totalTokenCount").asInt()).isEqualTo(5);
    }

    @Test
    void convertsOpenAiStreamingTextAndToolCallToClaudeLifecycle() {
        RelayProtocolAdapter.StreamState state = adapter.newStreamState(
                "member", RelayProtocol.OPENAI, RelayProtocol.CLAUDE);
        assertThat(adapter.streamPrefix(state)).hasSize(2);
        assertThat(adapter.convertStreamData(
                "{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}", state).get(0))
                .contains("content_block_delta", "hi");
        adapter.convertStreamData("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"id\":\"call_1\",\"function\":{\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\"}}]}}]}", state);
        adapter.convertStreamData("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\"\\\"Paris\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}", state);

        assertThat(adapter.streamSuffix(state))
                .anySatisfy(event -> assertThat(event).contains("tool_use", "call_1", "weather"))
                .anySatisfy(event -> assertThat(event).contains("input_json_delta", "Paris"))
                .anySatisfy(event -> assertThat(event).contains("\"stop_reason\":\"tool_use\""));
    }

    @Test
    void convertsClaudeStreamingToolEventsToOpenAiChunks() {
        RelayProtocolAdapter.StreamState state = adapter.newStreamState(
                "member", RelayProtocol.CLAUDE, RelayProtocol.OPENAI);
        String start = adapter.convertStreamData("{\"type\":\"content_block_start\",\"index\":1,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"tool_1\",\"name\":\"lookup\"}}", state).get(0);
        String delta = adapter.convertStreamData("{\"type\":\"content_block_delta\",\"index\":1,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}", state).get(0);

        assertThat(start).contains("tool_calls", "tool_1", "lookup");
        assertThat(delta).contains("tool_calls", "arguments");
        assertThat(adapter.streamSuffix(state)).containsExactly("[DONE]");
    }
}
