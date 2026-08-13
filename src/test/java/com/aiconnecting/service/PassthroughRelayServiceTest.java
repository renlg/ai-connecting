package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.Token;
import com.aiconnecting.entity.UsageLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PassthroughRelayServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private PassthroughRelayService service;

    @BeforeEach
    void setUp() {
        service = new PassthroughRelayService(null, null, mapper);
    }

    @Test
    void rewritesOnlyTopLevelModelAndPreservesVerbatimBody() {
        String raw = "{ \"model\" : \"display\", \"n\":1.00, \"nested\":{\"model\":\"keep\"} }";
        assertEquals("{ \"model\" : \"upstream\\\"name\", \"n\":1.00, \"nested\":{\"model\":\"keep\"} }",
                service.rewriteTopLevelModelVerbatim(raw, "upstream\"name"));
    }

    @Test
    void rejectsMissingDuplicateNonStringAndNonObjectModelBodies() {
        assertThrows(BusinessException.class, () -> service.extractTopLevelModel("{}"));
        assertThrows(BusinessException.class, () -> service.extractTopLevelModel("{\"model\":\"a\",\"model\":\"b\"}"));
        assertThrows(BusinessException.class, () -> service.extractTopLevelModel("{\"model\":3}"));
        assertThrows(BusinessException.class, () -> service.extractTopLevelModel("[]"));
    }

    @Test
    void buildsRequestWithoutClientAuthorizationOrHopByHopHeadersAndPreservesPathQuery() {
        Channel channel = Channel.builder().baseUrl("https://upstream.example/").apiKey("secret").build();
        MockHttpServletRequest servlet = new MockHttpServletRequest("POST", "/v1/vendor/path");
        servlet.setQueryString("x=1&raw=a%2Fb");
        servlet.setContentType("application/json");
        servlet.addHeader("Authorization", "Bearer client-secret");
        servlet.addHeader("X-Custom", "one");
        servlet.addHeader("X-Custom", "two");
        servlet.addHeader("Connection", "close");
        servlet.addHeader("Proxy-Authorization", "bad");

        Request request = service.buildPassthroughRequest(channel, servlet, "{\"model\":\"up\"}");

        assertEquals("https://upstream.example/v1/vendor/path?x=1&raw=a%2Fb", request.url().toString());
        assertEquals("Bearer secret", request.header("Authorization"));
        assertFalse(request.headers("Authorization").contains("Bearer client-secret"));
        assertEquals(2, request.headers("X-Custom").size());
        assertNull(request.header("Connection"));
        assertNull(request.header("Proxy-Authorization"));
    }

    @Test
    void buildsRequestWithoutDuplicatingBaseUrlVersionSegment() {
        Channel channel = Channel.builder().baseUrl("https://upstream.example/v1/").apiKey("secret").build();
        MockHttpServletRequest servlet = new MockHttpServletRequest("POST", "/v1/images/edits");

        Request request = service.buildPassthroughRequest(channel, servlet, "binary-safe-body");

        assertEquals("https://upstream.example/v1/images/edits", request.url().toString());
        assertEquals("binary-safe-body", new String(requestBodyBytes(request), StandardCharsets.UTF_8));
    }

    private byte[] requestBodyBytes(Request request) {
        try {
            okio.Buffer buffer = new okio.Buffer();
            request.body().writeTo(buffer);
            return buffer.readByteArray();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void copiesUpstreamStatusHeadersAndBytesVerbatimAndObservesJsonUsage() throws Exception {
        byte[] bytes = "{\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":9}}"
                .getBytes(StandardCharsets.UTF_8);
        Request request = new Request.Builder().url("https://upstream.example/v1/x").build();
        Response upstream = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(429).message("limited")
                .addHeader("Content-Type", "application/json")
                .addHeader("Set-Cookie", "a=1").addHeader("Set-Cookie", "b=2")
                .body(ResponseBody.create(bytes, MediaType.parse("application/json"))).build();
        MockHttpServletResponse servlet = new MockHttpServletResponse();
        PassthroughRelayService.UsageObserver observer =
                new PassthroughRelayService.UsageObserver("application/json", mapper);

        service.copyUpstreamResponse(upstream, servlet, observer);

        assertEquals(429, servlet.getStatus());
        assertEquals(2, servlet.getHeaders("Set-Cookie").size());
        assertArrayEquals(bytes, servlet.getContentAsByteArray());
        PassthroughRelayService.Usage usage = observer.finish();
        assertEquals(9, usage.totalTokens());
        assertEquals(2, usage.promptTokens());
        assertEquals(3, usage.completionTokens());
    }

    @Test
    void sseObserverDoesNotAlterChunksOrInjectDoneAndUsesLastUsageEvent() throws Exception {
        byte[] bytes = ("data: {\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}\n\n"
                + "data: {\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":5,\"total_tokens\":9}}\n\n")
                .getBytes(StandardCharsets.UTF_8);
        Request request = new Request.Builder().url("https://upstream.example/v1/x").build();
        Response upstream = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("ok").addHeader("Content-Type", "text/event-stream")
                .body(ResponseBody.create(bytes, MediaType.parse("text/event-stream"))).build();
        MockHttpServletResponse servlet = new MockHttpServletResponse();
        PassthroughRelayService.UsageObserver observer =
                new PassthroughRelayService.UsageObserver("text/event-stream", mapper);

        service.copyUpstreamResponse(upstream, servlet, observer);

        assertArrayEquals(bytes, servlet.getContentAsByteArray());
        assertFalse(servlet.getContentAsString().contains("stream_options"));
        assertFalse(servlet.getContentAsString().contains("[DONE]"));
        assertEquals(9, observer.finish().totalTokens());
    }

    @Test
    void usageLogUsesCanonicalModelAndActualUpstreamModelAndUpstreamTotalTokens() {
        UsageLogService usageLogService = mock(UsageLogService.class);
        when(usageLogService.calculateCreditCost("platform-model", 2, 3, 0))
                .thenReturn(new BigDecimal("1.25"));
        RelaySupport support = new RelaySupport(mock(ChannelService.class), mock(ChannelRouter.class),
                mock(ChannelHealthTracker.class), mock(TokenService.class), usageLogService,
                mock(ModelConfigService.class), mock(ModelGroupService.class), mock(UserService.class),
                mock(VideoTaskUsageLogService.class));
        Token token = Token.builder().id(10L).userId(20L).build();
        Channel channel = Channel.builder().id(30L).build();

        support.recordPassthroughUsage(token, channel, "platform-model", "vendor-model",
                2, 3, 9, 0, 0, 0, 12, new MockHttpServletRequest(), "/v1/vendor");

        org.mockito.ArgumentCaptor<UsageLog> captor = org.mockito.ArgumentCaptor.forClass(UsageLog.class);
        verify(usageLogService).recordUsageAndQuotas(captor.capture(), eq(10L), eq(30L), eq(9), eq(20L));
        assertEquals("platform-model", captor.getValue().getModel());
        assertEquals("vendor-model", captor.getValue().getActualModel());
        assertEquals(new BigDecimal("1.25"), captor.getValue().getCreditCost());
    }

    @Test
    void displayNameRoutesToCanonicalMappingAndUpstreamResponseRemainsVerbatim() throws Exception {
        byte[] responseBytes = "{\"usage\":{\"total_tokens\":0},\"vendor\":1.00}"
                .getBytes(StandardCharsets.UTF_8);
        okhttp3.Call.Factory calls = mock(okhttp3.Call.Factory.class);
        okhttp3.Call call = mock(okhttp3.Call.class);
        org.mockito.ArgumentCaptor<Request> requestCaptor = org.mockito.ArgumentCaptor.forClass(Request.class);
        when(calls.newCall(requestCaptor.capture())).thenReturn(call);
        when(call.execute()).thenAnswer(invocation -> {
            Request sent = requestCaptor.getValue();
            return new Response.Builder().request(sent).protocol(Protocol.HTTP_1_1).code(418).message("vendor")
                    .addHeader("Content-Type", "application/json").addHeader("X-Upstream", "yes")
                    .body(ResponseBody.create(responseBytes, MediaType.parse("application/json"))).build();
        });
            ChannelRouter router = mock(ChannelRouter.class);
            ChannelHealthTracker health = mock(ChannelHealthTracker.class);
            ChannelService channels = mock(ChannelService.class);
            UsageLogService logs = mock(UsageLogService.class);
            when(logs.calculateCreditCost(anyString(), anyInt(), anyInt(), anyInt())).thenReturn(BigDecimal.ZERO);
            RelaySupport support = spy(new RelaySupport(channels, router, health, mock(TokenService.class), logs,
                    mock(ModelConfigService.class), mock(ModelGroupService.class), mock(UserService.class),
                    mock(VideoTaskUsageLogService.class)));
            Token token = Token.builder().id(1L).userId(2L).build();
            com.aiconnecting.entity.User user = com.aiconnecting.entity.User.builder().id(2L).level(1).build();
            com.aiconnecting.entity.ModelConfig config = com.aiconnecting.entity.ModelConfig.builder()
                    .id(7L).name("platform-model").build();
            doReturn("platform-model").when(support).resolveModelName("Display Name");
            doReturn("7").when(support).resolveToChannelModelId("platform-model");
            doReturn(new RelaySupport.RelayContext(token, "7", 1, user, config))
                    .when(support).validateAndPrepare("client-key", "platform-model", "text");
            when(router.isPassthroughOnlyModel("7")).thenReturn(true);
            Channel channel = Channel.builder().id(9L).type("custom")
                    .baseUrl("https://upstream.example").apiKey("channel-key")
                    .modelMapping("{\"platform-model\":\"vendor-model\"}").build();
            when(router.selectChannel("7", Set.of(), 1)).thenReturn(channel);
            doReturn(false).when(support).isChannelRateLimited(channel);
            PassthroughRelayService relay = new PassthroughRelayService(support, channels, mapper, calls);
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/vendor");
            request.setQueryString("x=1");
            request.setContentType("application/json");
            request.addHeader("Authorization", "Bearer client-key");
            MockHttpServletResponse response = new MockHttpServletResponse();
            String raw = "{ \"model\" : \"Display Name\", \"n\":1.00 }";

            assertTrue(relay.tryPassthrough(raw, request, response));

            Request sent = requestCaptor.getValue();
            okio.Buffer sentBody = new okio.Buffer();
            sent.body().writeTo(sentBody);
            assertEquals("{ \"model\" : \"vendor-model\", \"n\":1.00 }", sentBody.readUtf8());
            assertEquals("Bearer channel-key", sent.header("Authorization"));
            assertEquals("https://upstream.example/v1/vendor?x=1", sent.url().toString());
            assertEquals(418, response.getStatus());
            assertEquals("yes", response.getHeader("X-Upstream"));
            assertArrayEquals(responseBytes, response.getContentAsByteArray());
            verify(support).isChannelRateLimited(channel);
    }

    @Test
    void missingMappingReturns404BeforeAnyUpstreamCall() throws Exception {
        okhttp3.Call.Factory calls = mock(okhttp3.Call.Factory.class);
            ChannelRouter router = mock(ChannelRouter.class);
            ChannelService channels = mock(ChannelService.class);
            RelaySupport support = spy(new RelaySupport(channels, router, mock(ChannelHealthTracker.class),
                    mock(TokenService.class), mock(UsageLogService.class), mock(ModelConfigService.class),
                    mock(ModelGroupService.class), mock(UserService.class), mock(VideoTaskUsageLogService.class)));
            doReturn("platform-model").when(support).resolveModelName("platform-model");
            doReturn("7").when(support).resolveToChannelModelId("platform-model");
            doReturn(new RelaySupport.RelayContext(Token.builder().id(1L).build(), "7", 1, null, null))
                    .when(support).validateAndPrepare("client", "platform-model", "text");
            when(router.isPassthroughOnlyModel("7")).thenReturn(true);
            when(router.selectChannel("7", Set.of(), 1)).thenReturn(Channel.builder().id(9L).type("custom")
                    .baseUrl("https://upstream.example").apiKey("key")
                    .modelMapping("{}").build());
            PassthroughRelayService relay = new PassthroughRelayService(support, channels, mapper, calls);
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/vendor");
            request.setContentType("application/json");
            request.addHeader("Authorization", "Bearer client");

            BusinessException error = assertThrows(BusinessException.class, () -> relay.tryPassthrough(
                    "{\"model\":\"platform-model\"}", request, new MockHttpServletResponse()));

            assertEquals(404, error.getCode());
            verifyNoInteractions(calls);
    }

    @Test
    void channelRateLimitReturns429BeforeUpstreamCall() throws Exception {
        okhttp3.Call.Factory calls = mock(okhttp3.Call.Factory.class);
        ChannelRouter router = mock(ChannelRouter.class);
        ChannelService channels = mock(ChannelService.class);
        RelaySupport support = spy(new RelaySupport(channels, router, mock(ChannelHealthTracker.class),
                mock(TokenService.class), mock(UsageLogService.class), mock(ModelConfigService.class),
                mock(ModelGroupService.class), mock(UserService.class), mock(VideoTaskUsageLogService.class)));
        doReturn("platform-model").when(support).resolveModelName("platform-model");
        doReturn("7").when(support).resolveToChannelModelId("platform-model");
        doReturn(new RelaySupport.RelayContext(Token.builder().id(1L).build(), "7", 1, null, null))
                .when(support).validateAndPrepare("client", "platform-model", "text");
        when(router.isPassthroughOnlyModel("7")).thenReturn(true);
        Channel channel = Channel.builder().id(9L).type("custom")
                .baseUrl("https://upstream.example").apiKey("key")
                .modelMapping("{\"platform-model\":\"upstream\"}").build();
        when(router.selectChannel("7", Set.of(), 1)).thenReturn(channel);
        doReturn(true).when(support).isChannelRateLimited(channel);
        PassthroughRelayService relay = new PassthroughRelayService(support, channels, mapper, calls);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/vendor");
        request.addHeader("Authorization", "Bearer client");

        BusinessException error = assertThrows(BusinessException.class, () -> relay.tryPassthrough(
                "{\"model\":\"platform-model\"}", request, new MockHttpServletResponse()));

        assertEquals(429, error.getCode());
        assertFalse(error.isUpstreamResponse());
        verifyNoInteractions(calls);
    }
}
