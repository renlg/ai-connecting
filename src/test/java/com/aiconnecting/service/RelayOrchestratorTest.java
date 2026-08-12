package com.aiconnecting.service;

import com.aiconnecting.entity.ModelGroup;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RelayOrchestratorTest {

    @Test
    void resolvesClaudeGroupBeforeSingleModelSender() {
        Fixture f = fixture();
        String body = "{\"model\":\"public-group\",\"messages\":[],\"max_tokens\":16}";
        when(f.groups.relayRequest(eq("key"), any(UnifiedRelayRequest.class),
                any(), any())).thenReturn("{\"type\":\"message\"}");

        String result = f.orchestrator.relay("key", RelayProtocol.CLAUDE, "/v1/messages",
                body, "public-group", new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(result).isEqualTo("{\"type\":\"message\"}");
        verify(f.groups).relayRequest(eq("key"), argThat(r -> r.protocol() == RelayProtocol.CLAUDE
                && r.model().equals("public-group")), any(), any());
        verifyNoInteractions(f.claude);
    }

    @Test
    void resolvesGeminiGroupFromPathModel() {
        Fixture f = fixture();
        String body = "{\"contents\":[]}";
        when(f.groups.relayRequest(eq("key"), any(UnifiedRelayRequest.class),
                any(), any())).thenReturn("{\"candidates\":[]}");

        String result = f.orchestrator.relay("key", RelayProtocol.GEMINI,
                "/v1/models/public-group:generateContent", body, "public-group",
                new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(result).isEqualTo("{\"candidates\":[]}");
        verify(f.groups).relayRequest(eq("key"), argThat(r -> r.protocol() == RelayProtocol.GEMINI
                && r.model().equals("public-group")), any(), any());
        verifyNoInteractions(f.gemini);
    }

    private Fixture fixture() {
        RelaySupport support = mock(RelaySupport.class);
        ModelGroupFailoverExecutor groups = mock(ModelGroupFailoverExecutor.class);
        ClaudeRelayService claude = mock(ClaudeRelayService.class);
        GeminiRelayService gemini = mock(GeminiRelayService.class);
        OpenAiRelayService openAi = mock(OpenAiRelayService.class);
        when(support.findModelConfigCached("public-group")).thenReturn(null);
        when(groups.findEnabledGroup("public-group")).thenReturn(Optional.of(
                ModelGroup.builder().name("public-group").enabled(true).type("text").build()));
        RelayOrchestrator orchestrator = new RelayOrchestrator(support,
                new RelayProtocolAdapter(new ObjectMapper()), openAi, claude, gemini, groups);
        return new Fixture(orchestrator, groups, claude, gemini);
    }

    private record Fixture(RelayOrchestrator orchestrator, ModelGroupFailoverExecutor groups,
                           ClaudeRelayService claude, GeminiRelayService gemini) {}
}
