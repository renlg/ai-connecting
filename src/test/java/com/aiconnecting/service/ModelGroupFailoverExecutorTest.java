package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.entity.ModelGroup;
import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.Token;
import com.aiconnecting.entity.User;
import com.aiconnecting.repository.VideoTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModelGroupFailoverExecutorTest {

    @Test
    void directGroupRequest_returnsModelNotFoundForUnsupportedUserLevel() {
        RelaySupport support = mock(RelaySupport.class);
        ModelGroupService groupService = mock(ModelGroupService.class);
        ModelGroupRoutingService routingService = mock(ModelGroupRoutingService.class);
        ModelGroup group = ModelGroup.builder()
                .id(10L).name("bailian-good").type("text").enabled(true)
                .supportedLevels("2,3,4,5").build();
        User user = User.builder().id(7L).role("user").level(1).build();
        Token token = Token.builder().id(8L).userId(7L).build();

        when(groupService.findByName("bailian-good")).thenReturn(Optional.of(group));
        when(support.prepareGroupContext("sk-level-one", "bailian-good"))
                .thenReturn(new RelaySupport.RelayContext(token, null, 1, user, null));

        ModelGroupFailoverExecutor executor = new ModelGroupFailoverExecutor(
                support, groupService, routingService, mock(ModelGroupBillingService.class),
                mock(UsageLogService.class),
                mock(VideoTaskRepository.class), mock(VideoTaskUsageLogService.class),
                mock(PassthroughRelayService.class));

        assertThatThrownBy(() -> executor.relayRequest(
                "sk-level-one", "/v1/chat/completions", "{\"model\":\"bailian-good\"}",
                "bailian-good", null))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(404);
                    assertThat(error.getEnglishMessage())
                            .isEqualTo("Model group not found or disabled: bailian-good");
                    assertThat(error.isUpstreamResponse()).isFalse();
                });
        verify(routingService, never()).resolveOrderedCandidates(group, false, 1);
    }

    @Test
    void customMemberUsesMappedModelChannelAuthorizationAndVerbatimResponseWithGroupUsage() throws Exception {
        byte[] responseBytes = "{\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5},\"n\":1.00}"
                .getBytes(StandardCharsets.UTF_8);
        GroupFixture fixture = groupFixture("application/json", responseBytes, 200);
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String rawBody = "{ \"model\" : \"public-group\", \"temperature\":1.00 }";

        String result = fixture.executor.relayRequest(
                "client-key", "/v1/chat/completions", rawBody, "public-group", request, response);

        assertThat(result).isNull();
        Request sent = fixture.sentRequest.getValue();
        okio.Buffer body = new okio.Buffer();
        sent.body().writeTo(body);
        assertThat(body.readUtf8()).isEqualTo(
                "{ \"model\" : \"vendor-model\", \"temperature\":1.00 }");
        assertThat(sent.header("Authorization")).isEqualTo("Bearer channel-key");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEqualTo(responseBytes);

        org.mockito.ArgumentCaptor<com.aiconnecting.entity.UsageLog> usage =
                org.mockito.ArgumentCaptor.forClass(com.aiconnecting.entity.UsageLog.class);
        verify(fixture.usageLogs).recordUsageAndQuotas(usage.capture(), eq(8L), eq(9L), eq(5), eq(7L));
        assertThat(usage.getValue().getModel()).isEqualTo("public-group");
        assertThat(usage.getValue().getActualModel()).isEqualTo("vendor-model");
    }

    @Test
    void imagePartIsDetectedOnceBeforeRoutingAndCustomPassthroughStillWorks() throws Exception {
        GroupFixture fixture = groupFixture("application/json", "{\"usage\":{\"total_tokens\":0}}"
                .getBytes(StandardCharsets.UTF_8), 200);
        String body = "{\"model\":\"public-group\",\"messages\":[{\"role\":\"user\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"describe\"},{\"type\":\"image_url\","
                + "\"image_url\":{\"url\":\"https://example.test/image.png\"}}]}]}";

        fixture.executor.relayRequest("client-key", "/v1/chat/completions", body,
                "public-group", request(), new MockHttpServletResponse());

        verify(fixture.routing).resolveOrderedCandidates(fixture.group, false, 1, true);
        Request sent = fixture.sentRequest.getValue();
        okio.Buffer sentBody = new okio.Buffer();
        sent.body().writeTo(sentBody);
        assertThat(sentBody.readUtf8()).contains("\"messages\"").contains("\"image_url\"");
    }

    @Test
    void openAiToolResultNestedImageIsDetectedBeforeRouting() throws Exception {
        assertVisionRouting(RelayProtocol.OPENAI, "/v1/chat/completions", null,
                "{\"model\":\"public-group\",\"messages\":[{\"role\":\"tool\",\"content\":["
                        + "{\"type\":\"tool_result\",\"content\":[{\"type\":\"image_url\","
                        + "\"image_url\":{\"url\":\"https://example.test/image.png\"}}]}]}]}", true);
    }

    @Test
    void claudeToolResultNestedImageIsDetectedBeforeRouting() throws Exception {
        assertVisionRouting(RelayProtocol.CLAUDE, "/v1/messages", null,
                "{\"model\":\"public-group\",\"messages\":[{\"role\":\"user\",\"content\":["
                        + "{\"type\":\"tool_result\",\"tool_use_id\":\"tool-1\",\"content\":["
                        + "{\"type\":\"image\",\"source\":{\"type\":\"base64\","
                        + "\"media_type\":\"image/png\",\"data\":\"aW1hZ2U=\"}}]}]}]}", true);
    }

    @Test
    void geminiInlineDataImageIsDetectedBeforeRouting() throws Exception {
        assertVisionRouting(RelayProtocol.GEMINI, "/v1/models/public-group:generateContent", "public-group",
                "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"describe\"},"
                        + "{\"inline_data\":{\"mime_type\":\"image/png\",\"data\":\"aW1hZ2U=\"}}]}]}",
                true);
    }

    @Test
    void geminiFileDataImageIsDetectedBeforeRouting() throws Exception {
        assertVisionRouting(RelayProtocol.GEMINI, "/v1/models/public-group:generateContent", "public-group",
                "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"fileData\":{"
                        + "\"mimeType\":\"image/jpeg\",\"fileUri\":\"gs://bucket/image.jpg\"}}]}]}", true);
    }

    @Test
    void pureTextRequestPreservesConfiguredMemberOrderWithoutVisionPartition() throws Exception {
        assertVisionRouting(RelayProtocol.GEMINI, "/v1/models/public-group:generateContent", "public-group",
                "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hello\"}]}]}", null);
    }

    @Test
    void videoTransformationRunsBeforeMemberPrechargeAndFailureRecording() {
        RelaySupport support = spy(new RelaySupport(null, null, null, null, null, null, null, null));
        ModelGroupService groupService = mock(ModelGroupService.class);
        ModelGroupRoutingService routing = mock(ModelGroupRoutingService.class);
        ModelGroupBillingService billing = mock(ModelGroupBillingService.class);
        RequestBodyTransformerRegistry registry = mock(RequestBodyTransformerRegistry.class);
        FailureLogService failureLogs = mock(FailureLogService.class);
        ModelGroup group = ModelGroup.builder().id(10L).name("video-group").type("video")
                .strategy("priority").maxAttempts(3).enabled(true).build();
        ModelConfig member = ModelConfig.builder().id(11L).name("agnes-video-v2.0").status(1).build();
        User user = User.builder().id(7L).role("user").level(1).build();
        Token token = Token.builder().id(8L).userId(7L).build();
        RelaySupport.RelayContext context = new RelaySupport.RelayContext(token, null, 1, user, null);
        when(groupService.findByName("video-group")).thenReturn(Optional.of(group));
        doReturn(context).when(support).prepareGroupContext("sk-test", "video-group");
        doReturn(new RelaySupport.MediaParams("720p", 1, 0)).when(support)
                .parseMediaParams("{\"model\":\"video-group\",\"duration\":0}");
        when(routing.resolveOrderedCandidates(group, false, 1)).thenReturn(List.of(
                new ModelGroupRoutingService.Candidate(member, "11")));
        BusinessException invalid = new BusinessException(400, "duration 参数必须是正整数秒数",
                "duration must be a positive integer number of seconds");
        when(registry.transform(eq("agnes-video-v2.0"), anyString())).thenThrow(invalid);

        ModelGroupFailoverExecutor executor = new ModelGroupFailoverExecutor(
                support, groupService, routing, billing, mock(UsageLogService.class),
                mock(VideoTaskRepository.class), mock(VideoTaskUsageLogService.class),
                mock(PassthroughRelayService.class));
        ReflectionTestUtils.setField(executor, "requestBodyTransformerRegistry", registry);
        ReflectionTestUtils.setField(executor, "failureLogService", failureLogs);

        assertThatThrownBy(() -> executor.relayVideoRequest("sk-test", "/v1/videos",
                "{\"model\":\"video-group\",\"duration\":0}", "video-group", request()))
                .isSameAs(invalid);
        verify(registry, times(1)).transform(eq("agnes-video-v2.0"), anyString());
        verify(support, never()).chargeMediaCredits(any(), any());
        verify(failureLogs, never()).recordChannelFailure(any(), any(), any(), any(), any(), any());
    }

    @Test
    void customMemberStreamsSseBytesIncludingDoneVerbatimWithoutInjectingOptions() throws Exception {
        byte[] responseBytes = ("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n"
                + "data: {\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}\n\n"
                + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
        GroupFixture fixture = groupFixture("text/event-stream", responseBytes, 200);
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String rawBody = "{\"model\":\"public-group\",\"stream\":true}";

        fixture.executor.relayStreamRequest(
                "client-key", "/v1/chat/completions", rawBody, "public-group", request, response);

        assertThat(response.getContentAsByteArray()).isEqualTo(responseBytes);
        Request sent = fixture.sentRequest.getValue();
        okio.Buffer body = new okio.Buffer();
        sent.body().writeTo(body);
        assertThat(body.readUtf8()).isEqualTo("{\"model\":\"vendor-model\",\"stream\":true}");
        verify(fixture.support, never()).injectStreamOptions(anyString(), anyString());
    }

    @Test
    void normalMemberInGroupKeepsNormalForwardingPath() throws Exception {
        GroupFixture fixture = groupFixture("application/json", "unused".getBytes(StandardCharsets.UTF_8), 200);
        fixture.channel.setType("openai");
        doReturn("{\"usage\":{\"total_tokens\":0}}").when(fixture.support)
                .forwardRequest(eq(fixture.channel), eq("/v1/chat/completions"), anyString(), anyLong());

        String result = fixture.executor.relayRequest(
                "client-key", "/v1/chat/completions", "{\"model\":\"public-group\"}",
                "public-group", request());

        assertThat(result).isEqualTo("{\"usage\":{\"total_tokens\":0}}");
        verify(fixture.calls, never()).newCall(any());
        verify(fixture.support).forwardRequest(eq(fixture.channel), eq("/v1/chat/completions"),
                eq("{\"model\":\"member-model\"}"), anyLong());
    }

    @Test
    void claudeGroupRoutesThroughOpenAiMemberAndBillsAtGroupPrice() throws Exception {
        GroupFixture fixture = groupFixture("application/json", new byte[0], 200);
        fixture.channel.setType("openai");
        String openAiResponse = "{\"model\":\"member-model\",\"choices\":[{\"message\":{\"content\":\"ok\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":6,\"total_tokens\":10}}";
        doReturn(openAiResponse).when(fixture.support)
                .forwardRequest(eq(fixture.channel), eq("/v1/chat/completions"), anyString(), anyLong());
        String raw = "{\"model\":\"public-group\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":64}";
        UnifiedRelayRequest unified = new RelayProtocolAdapter(new ObjectMapper()).adaptRequest(
                RelayProtocol.CLAUDE, "/v1/messages", raw, null);

        String result = fixture.executor.relayRequest(
                "client-key", unified, request(), new MockHttpServletResponse());

        JsonNode resultJson = new ObjectMapper().readTree(result);
        assertThat(resultJson.path("type").asText()).isEqualTo("message");
        assertThat(resultJson.path("content").path(0).path("text").asText()).isEqualTo("ok");
        verify(fixture.billing).calculateTextCreditCost(any(), eq(4), eq(6), eq(0));
        org.mockito.ArgumentCaptor<com.aiconnecting.entity.UsageLog> usage =
                org.mockito.ArgumentCaptor.forClass(com.aiconnecting.entity.UsageLog.class);
        verify(fixture.usageLogs).recordUsageAndQuotas(usage.capture(), eq(8L), eq(9L), eq(10), eq(7L));
        assertThat(usage.getValue().getModel()).isEqualTo("public-group");
        assertThat(usage.getValue().getActualModel()).isEqualTo("member-model");
    }

    @Test
    void geminiGroupRoutesThroughOpenAiMemberAndBillsAtGroupPrice() throws Exception {
        GroupFixture fixture = groupFixture("application/json", new byte[0], 200);
        fixture.channel.setType("openai");
        String openAiResponse = "{\"choices\":[{\"message\":{\"content\":\"gemini-ok\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":5,\"total_tokens\":8}}";
        doReturn(openAiResponse).when(fixture.support)
                .forwardRequest(eq(fixture.channel), eq("/v1/chat/completions"), anyString(), anyLong());
        String raw = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}]}";
        UnifiedRelayRequest unified = new RelayProtocolAdapter(new ObjectMapper()).adaptRequest(
                RelayProtocol.GEMINI, "/v1/models/public-group:generateContent", raw, "public-group");

        String result = fixture.executor.relayRequest(
                "client-key", unified, request(), new MockHttpServletResponse());

        JsonNode resultJson = new ObjectMapper().readTree(result);
        assertThat(resultJson.path("candidates").path(0).path("content").path("parts").path(0)
                .path("text").asText()).isEqualTo("gemini-ok");
        verify(fixture.billing).calculateTextCreditCost(any(), eq(3), eq(5), eq(0));
    }

    @Test
    void claudeGroupCustomMemberPreservesBodyAndResponse() throws Exception {
        byte[] responseBytes = "{\"type\":\"message\",\"usage\":{\"input_tokens\":2,\"output_tokens\":3}}"
                .getBytes(StandardCharsets.UTF_8);
        GroupFixture fixture = groupFixture("application/json", responseBytes, 200);
        String raw = "{ \"model\" : \"public-group\", \"messages\":[] }";
        UnifiedRelayRequest unified = new RelayProtocolAdapter(new ObjectMapper()).adaptRequest(
                RelayProtocol.CLAUDE, "/v1/messages", raw, null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(fixture.executor.relayRequest("client-key", unified, request(), response)).isNull();
        assertThat(response.getContentAsByteArray()).isEqualTo(responseBytes);
        okio.Buffer sentBody = new okio.Buffer();
        fixture.sentRequest.getValue().body().writeTo(sentBody);
        assertThat(sentBody.readUtf8()).isEqualTo("{ \"model\" : \"vendor-model\", \"messages\":[] }");
    }

    @Test
    void geminiGroupCustomMemberMapsUrlModelWhenBodyHasNoModel() throws Exception {
        byte[] responseBytes = "{\"candidates\":[],\"usageMetadata\":{\"totalTokenCount\":0}}"
                .getBytes(StandardCharsets.UTF_8);
        GroupFixture fixture = groupFixture("application/json", responseBytes, 200);
        String raw = "{\"contents\":[]}";
        UnifiedRelayRequest unified = new RelayProtocolAdapter(new ObjectMapper()).adaptRequest(
                RelayProtocol.GEMINI, "/v1/models/public-group:generateContent", raw, "public-group");
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/v1/models/public-group:generateContent");
        request.addHeader("Authorization", "Bearer client-key");

        assertThat(fixture.executor.relayRequest(
                "client-key", unified, request, new MockHttpServletResponse())).isNull();
        Request sent = fixture.sentRequest.getValue();
        assertThat(sent.url().encodedPath()).isEqualTo("/v1/models/vendor-model:generateContent");
        okio.Buffer sentBody = new okio.Buffer();
        sent.body().writeTo(sentBody);
        assertThat(sentBody.readUtf8()).isEqualTo(raw);
    }

    @Test
    void customMemberMissingCanonicalMappingFailsLocallyWith404() throws Exception {
        GroupFixture fixture = groupFixture("application/json", new byte[0], 200);
        fixture.channel.setModelMapping("{}");

        assertThatThrownBy(() -> fixture.executor.relayRequest(
                "client-key", "/v1/chat/completions", "{\"model\":\"public-group\"}",
                "public-group", request(), new MockHttpServletResponse()))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(404);
                    assertThat(error.isUpstreamResponse()).isFalse();
                });
        verify(fixture.calls, never()).newCall(any());
    }

    @Test
    void fastFailModelNotOpenFromFirstMemberFailsOverToSecondMember() {
        StandardGroupFixture fixture = standardGroupFixture();
        BusinessException modelNotOpen = BusinessException.upstream(404,
                "upstream error", "upstream error",
                "{\"error\":{\"code\":\"ModelNotOpen\",\"message\":\"model is not open\"}}", null);
        doThrow(modelNotOpen).when(fixture.support).forwardRequest(eq(fixture.firstChannel),
                eq("/v1/chat/completions"), anyString(), anyLong());
        doReturn("{\"usage\":{\"total_tokens\":0},\"result\":\"fallback\"}")
                .when(fixture.support).forwardRequest(eq(fixture.secondChannel),
                        eq("/v1/chat/completions"), anyString(), anyLong());

        String result = fixture.executor.relayRequest("client-key", "/v1/chat/completions",
                "{\"model\":\"public-group\"}", "public-group", request());

        assertThat(result).contains("fallback");
        verify(fixture.support).forwardRequest(eq(fixture.secondChannel),
                eq("/v1/chat/completions"), eq("{\"model\":\"second-model\"}"), anyLong());
    }

    @Test
    void realMemberFailureIsForwardedToDetailedFailureLogger() {
        StandardGroupFixture fixture = standardGroupFixture();
        FailureLogService failureLogs = mock(FailureLogService.class);
        ReflectionTestUtils.setField(fixture.executor, "failureLogService", failureLogs);
        BusinessException upstream = BusinessException.upstream(503, "timeout", "timeout body", null);
        doThrow(upstream).when(fixture.support).forwardRequest(eq(fixture.firstChannel),
                eq("/v1/chat/completions"), anyString(), anyLong());
        doReturn("{\"usage\":{\"total_tokens\":0}}")
                .when(fixture.support).forwardRequest(eq(fixture.secondChannel), anyString(), anyString(), anyLong());
        MockHttpServletRequest request = request();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            fixture.executor.relayRequest("client-key", "/v1/chat/completions",
                    "{\"model\":\"public-group\"}", "public-group", request);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        verify(failureLogs).recordChannelFailure(eq(request), eq(91L), eq(11L), isNull(), isNull(), eq(upstream));
    }

    @Test
    void allFastFailMembersReturnLastErrorAfterAttemptsAreExhausted() {
        StandardGroupFixture fixture = standardGroupFixture();
        doThrow(BusinessException.upstream(404, "first", "first",
                "{\"error\":{\"code\":\"ModelNotOpen\"}}", null))
                .when(fixture.support).forwardRequest(eq(fixture.firstChannel),
                        eq("/v1/chat/completions"), anyString(), anyLong());
        doThrow(BusinessException.upstream(422, "second", "second",
                "{\"error\":{\"code\":\"content_policy_violation\"}}", null))
                .when(fixture.support).forwardRequest(eq(fixture.secondChannel),
                        eq("/v1/chat/completions"), anyString(), anyLong());

        assertThatThrownBy(() -> fixture.executor.relayRequest("client-key", "/v1/chat/completions",
                "{\"model\":\"public-group\"}", "public-group", request()))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(422);
                    assertThat(error.getMessage()).contains("所有成员均不可用");
                    assertThat(error.isUpstreamResponse()).isTrue();
                });
        verify(fixture.support).forwardRequest(eq(fixture.firstChannel), anyString(), anyString(), anyLong());
        verify(fixture.support).forwardRequest(eq(fixture.secondChannel), anyString(), anyString(), anyLong());
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        request.setContentType("application/json");
        request.addHeader("Authorization", "Bearer client-key");
        return request;
    }

    private void assertVisionRouting(RelayProtocol protocol, String path, String pathModel,
                                     String body, Boolean expectedHasImage) throws Exception {
        GroupFixture fixture = groupFixture("application/json",
                "{\"usage\":{\"total_tokens\":0}}".getBytes(StandardCharsets.UTF_8), 200);
        UnifiedRelayRequest unified = new RelayProtocolAdapter(new ObjectMapper()).adaptRequest(
                protocol, path, body, pathModel);

        fixture.executor.relayRequest(
                "client-key", unified, request(), new MockHttpServletResponse());

        verify(fixture.routing).resolveOrderedCandidates(
                fixture.group, false, 1, expectedHasImage);
    }

    private GroupFixture groupFixture(String contentType, byte[] responseBytes, int status) throws Exception {
        ChannelRouter router = mock(ChannelRouter.class);
        UsageLogService usageLogs = mock(UsageLogService.class);
        RelaySupport support = spy(new RelaySupport(mock(ChannelService.class), router,
                mock(TokenService.class), usageLogs, mock(ModelConfigService.class),
                mock(ModelGroupService.class), mock(UserService.class), mock(VideoTaskUsageLogService.class)));
        ModelGroupService groupService = mock(ModelGroupService.class);
        ModelGroupRoutingService routing = mock(ModelGroupRoutingService.class);
        ModelGroupBillingService billing = mock(ModelGroupBillingService.class);
        Call.Factory calls = mock(Call.Factory.class);
        Call call = mock(Call.class);
        when(call.timeout()).thenReturn(new okio.Timeout());
        org.mockito.ArgumentCaptor<Request> sentRequest = org.mockito.ArgumentCaptor.forClass(Request.class);
        when(calls.newCall(sentRequest.capture())).thenReturn(call);
        when(call.execute()).thenAnswer(invocation -> new Response.Builder()
                .request(sentRequest.getValue()).protocol(Protocol.HTTP_1_1).code(status).message("upstream")
                .addHeader("Content-Type", contentType)
                .body(ResponseBody.create(responseBytes, MediaType.parse(contentType))).build());

        ModelGroup group = ModelGroup.builder().id(10L).name("public-group").type("text")
                .strategy("priority").maxAttempts(2).enabled(true).build();
        ModelConfig member = ModelConfig.builder().id(11L).name("member-model").status(1).build();
        Channel channel = Channel.builder().id(9L).type("custom").baseUrl("https://upstream.example")
                .apiKey("channel-key").modelMapping("{\"member-model\":\"vendor-model\"}")
                .rateLimit(0).build();
        Token token = Token.builder().id(8L).userId(7L).build();
        User user = User.builder().id(7L).role("user").level(1).build();
        RelaySupport.RelayContext context = new RelaySupport.RelayContext(token, null, 1, user, null);

        when(groupService.findByName("public-group")).thenReturn(Optional.of(group));
        doReturn(context).when(support).prepareGroupContext("client-key", "public-group");
        when(routing.resolveOrderedCandidates(eq(group), eq(false), eq(1), nullable(Boolean.class))).thenReturn(List.of(
                new ModelGroupRoutingService.Candidate(member, "11")));
        when(router.selectChannel("11", Set.of(), 1)).thenReturn(channel);
        doReturn(false).when(support).isChannelRateLimited(channel);
        when(billing.calculateTextCreditCost(any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(BigDecimal.ZERO);

        PassthroughRelayService passthrough = new PassthroughRelayService(
                support, mock(ChannelService.class), new ObjectMapper(), calls);
        ModelGroupFailoverExecutor executor = new ModelGroupFailoverExecutor(
                support, groupService, routing, billing, usageLogs,
                mock(VideoTaskRepository.class), mock(VideoTaskUsageLogService.class), passthrough);
        return new GroupFixture(executor, support, usageLogs, billing, routing, group, calls, sentRequest, channel);
    }

    private StandardGroupFixture standardGroupFixture() {
        ChannelRouter router = mock(ChannelRouter.class);
        UsageLogService usageLogs = mock(UsageLogService.class);
        RelaySupport support = spy(new RelaySupport(mock(ChannelService.class), router,
                mock(TokenService.class), usageLogs, mock(ModelConfigService.class),
                mock(ModelGroupService.class), mock(UserService.class), mock(VideoTaskUsageLogService.class)));
        ModelGroupService groupService = mock(ModelGroupService.class);
        ModelGroupRoutingService routing = mock(ModelGroupRoutingService.class);
        ModelGroup group = ModelGroup.builder().id(10L).name("public-group").type("text")
                .strategy("priority").maxAttempts(2).enabled(true).build();
        ModelConfig first = ModelConfig.builder().id(11L).name("first-model").status(1).build();
        ModelConfig second = ModelConfig.builder().id(12L).name("second-model").status(1).build();
        Channel firstChannel = Channel.builder().id(91L).type("openai").rateLimit(0).build();
        Channel secondChannel = Channel.builder().id(92L).type("openai").rateLimit(0).build();
        Token token = Token.builder().id(8L).userId(7L).build();
        User user = User.builder().id(7L).role("user").level(1).build();

        when(groupService.findByName("public-group")).thenReturn(Optional.of(group));
        doReturn(new RelaySupport.RelayContext(token, null, 1, user, null))
                .when(support).prepareGroupContext("client-key", "public-group");
        when(routing.resolveOrderedCandidates(group, false, 1, null)).thenReturn(List.of(
                new ModelGroupRoutingService.Candidate(first, "11"),
                new ModelGroupRoutingService.Candidate(second, "12")));
        when(router.selectChannel("11", Set.of(), 1)).thenReturn(firstChannel);
        when(router.selectChannel("12", Set.of(), 1)).thenReturn(secondChannel);
        doReturn(false).when(support).isChannelRateLimited(any(Channel.class));

        ModelGroupFailoverExecutor executor = new ModelGroupFailoverExecutor(
                support, groupService, routing, mock(ModelGroupBillingService.class), usageLogs,
                mock(VideoTaskRepository.class), mock(VideoTaskUsageLogService.class),
                mock(PassthroughRelayService.class));
        return new StandardGroupFixture(executor, support, firstChannel, secondChannel);
    }

    private record GroupFixture(ModelGroupFailoverExecutor executor, RelaySupport support,
                                UsageLogService usageLogs, ModelGroupBillingService billing,
                                ModelGroupRoutingService routing, ModelGroup group,
                                Call.Factory calls,
                                org.mockito.ArgumentCaptor<Request> sentRequest, Channel channel) {}

    private record StandardGroupFixture(ModelGroupFailoverExecutor executor, RelaySupport support,
                                        Channel firstChannel,
                                        Channel secondChannel) {}
}
