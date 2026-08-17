package com.aiconnecting.service;

import com.aiconnecting.common.RedisDistributedLock;
import com.aiconnecting.common.BusinessException;
import com.aiconnecting.entity.FailureLog;
import com.aiconnecting.repository.FailureLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FailureLogServiceTest {
    private final FailureLogRepository repository = mock(FailureLogRepository.class);
    private final FailureLogService service = new FailureLogService(repository,
            mock(RedisDistributedLock.class), mock(TransactionTemplate.class));

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void asynchronouslyRecordsCompleteSanitizedSnapshot() throws Exception {
        CountDownLatch saved = new CountDownLatch(1);
        ArgumentCaptor<FailureLog> captor = ArgumentCaptor.forClass(FailureLog.class);
        when(repository.save(captor.capture())).thenAnswer(invocation -> {
            saved.countDown();
            return invocation.getArgument(0);
        });
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        request.setAttribute(FailureLogContext.TRACE_ID, "trace-123");
        request.setAttribute(FailureLogContext.MODEL_NAME, "public-group");
        request.setAttribute(FailureLogContext.CHANNEL_MODEL_NAME, "vendor-model");
        request.setAttribute(FailureLogContext.PROTOCOL, RelayProtocol.OPENAI.name());

        service.record(request, 400, "Client rejected", "Upstream API error: 400 - "
                + "{\"api_key\":\"sk-secret\",\"error\":\"bad\"} Authorization: Bearer token-secret "
                + "https://vendor.test/error?key=query-secret&code=400");

        assertThat(saved.await(2, TimeUnit.SECONDS)).isTrue();
        FailureLog entry = captor.getValue();
        assertThat(entry.getTraceId()).isEqualTo("trace-123");
        assertThat(entry.getUserError()).isEqualTo("Client rejected");
        assertThat(entry.getHttpStatus()).isEqualTo(400);
        assertThat(entry.getModelName()).isEqualTo("public-group");
        assertThat(entry.getChannelModelName()).isEqualTo("vendor-model");
        assertThat(entry.getProtocol()).isEqualTo("OPENAI");
        assertThat(entry.getChannelError()).contains("[REDACTED]")
                .doesNotContain("sk-secret", "token-secret", "query-secret");
        assertThat(entry.getCreatedAt()).isPositive();
    }

    @Test
    void persistenceFailureNeverEscapesRequestThread() throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        when(repository.save(any())).thenAnswer(invocation -> {
            attempted.countDown();
            throw new IllegalStateException("database unavailable");
        });
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/messages");

        assertThatCode(() -> service.record(request, 502, "Upstream request failed", "socket reset"))
                .doesNotThrowAnyException();
        assertThat(attempted.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void channelFailuresAreDeduplicatedPerChannelMemberAndKeepSanitizedUpstreamBody() throws Exception {
        CountDownLatch saved = new CountDownLatch(2);
        ArgumentCaptor<FailureLog> captor = ArgumentCaptor.forClass(FailureLog.class);
        when(repository.save(captor.capture())).thenAnswer(invocation -> {
            saved.countDown();
            return invocation.getArgument(0);
        });
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        request.setAttribute(FailureLogContext.TRACE_ID, "trace-detail");
        request.setAttribute(FailureLogContext.MODEL_NAME, "group-name");
        request.setAttribute(FailureLogContext.PROTOCOL, RelayProtocol.OPENAI.name());
        BusinessException first = BusinessException.upstream(503, "vendor failed",
                "{\"error\":\"busy\",\"api_key\":\"sk-secret\"}", null);

        service.recordChannelFailure(request, 151L, 68L, "deepseek-v4-flash", first);
        service.recordChannelFailure(request, 151L, 68L, "deepseek-v4-flash",
                new BusinessException(504, "timeout"));
        service.recordChannelFailure(request, 152L, 68L, "deepseek-v4-flash", first);

        assertThat(saved.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(captor.getAllValues()).hasSize(2);
        FailureLog detail = captor.getAllValues().get(0);
        assertThat(detail.getTraceId()).isEqualTo("trace-detail");
        assertThat(detail.getModelName()).isEqualTo("group-name");
        assertThat(detail.getChannelModelName()).isEqualTo("deepseek-v4-flash");
        assertThat(detail.getHttpStatus()).isEqualTo(503);
        assertThat(detail.getChannelError()).contains("Upstream API error: 503", "busy", "[REDACTED]")
                .doesNotContain("sk-secret");
        verify(repository, times(2)).save(any());
    }

    @Test
    void searchUsesBoundedPaginationAndNewestFirst() {
        Page<FailureLog> result = new PageImpl<>(List.of());
        when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
                .thenReturn(result);

        assertThat(service.search(2, 500, "trace", 100L, 200L,
                "requested", "upstream", 429)).isSameAs(result);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(org.springframework.data.jpa.domain.Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(200);
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").isDescending()).isTrue();
    }

    @Test
    void cleanupDeletesOnlyRowsOlderThanSevenDays() {
        long now = 1_800_000_000_000L;
        long expectedCutoff = now - FailureLogService.RETENTION_DAYS * 24L * 60 * 60 * 1000;
        when(repository.deleteByCreatedAtLessThan(expectedCutoff)).thenReturn(3L);

        assertThat(service.cleanExpired(now)).isEqualTo(3L);
        verify(repository).deleteByCreatedAtLessThan(expectedCutoff);
    }
}
