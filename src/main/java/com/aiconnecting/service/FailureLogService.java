package com.aiconnecting.service;

import com.aiconnecting.common.RedisDistributedLock;
import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.SseUtils;
import com.aiconnecting.entity.FailureLog;
import com.aiconnecting.repository.FailureLogRepository;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class FailureLogService {
    public static final int RETENTION_DAYS = 7;
    static final int MAX_ERROR_LENGTH = 2000;
    private static final String CLEAN_LOCK_KEY = "job:failure-logs-clean";
    private static final int CLEAN_LOCK_TTL_SECONDS = 300;
    private static final Pattern HEADER_SECRET = Pattern.compile(
            "(?i)(authorization|proxy-authorization|x-api-key|api-key)\\s*[:=]\\s*(?:bearer\\s+)?[^\\s,;\\\"}]+"
    );
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)(\\\"(?:api[_-]?key|authorization|access[_-]?token|secret)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")"
    );
    private static final Pattern QUERY_SECRET = Pattern.compile(
            "(?i)([?&](?:key|api[_-]?key|access[_-]?token)=)[^&\\s]+"
    );

    private final FailureLogRepository repository;
    private final RedisDistributedLock distributedLock;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "failure-log-writer");
        thread.setDaemon(true);
        return thread;
    });

    /** Copies all request data before dispatching, so servlet/request state is never read asynchronously. */
    public void record(HttpServletRequest request, int httpStatus, String userError, String channelError) {
        try {
            if (request == null || request.getRequestURI() == null || !request.getRequestURI().startsWith("/v1/")
                    || ("GET".equals(request.getMethod()) && request.getRequestURI().startsWith("/v1/models"))) return;
            synchronized (request) {
                if (Boolean.TRUE.equals(request.getAttribute(FailureLogContext.RECORDED))) return;
                request.setAttribute(FailureLogContext.RECORDED, Boolean.TRUE);
            }
            String traceId = stringAttr(request, FailureLogContext.TRACE_ID);
            if (traceId == null) traceId = SseUtils.currentTraceId();
            String storedChannelError = channelError != null
                    ? channelError : stringAttr(request, FailureLogContext.CHANNEL_ERROR);
            FailureLog entry = FailureLog.builder()
                    .traceId(limit(traceId, 64))
                    .userError(sanitize(userError))
                    .channelError(blankToNull(sanitize(storedChannelError)))
                    .httpStatus(httpStatus)
                    .modelName(limit(stringAttr(request, FailureLogContext.MODEL_NAME), 100))
                    .channelModelName(limit(stringAttr(request, FailureLogContext.CHANNEL_MODEL_NAME), 100))
                    .protocol(resolveProtocol(request))
                    .createdAt(System.currentTimeMillis())
                    .build();
            writer.execute(() -> persistSafely(entry));
        } catch (Exception e) {
            log.error("提交失败日志写入任务失败（不影响请求）", e);
        }
    }

    /**
     * Records the first real upstream failure for a channel/member pair in this request. Router misses,
     * cooldown and rate-limit skips never call this method. The snapshot and de-duplication happen on
     * the request thread; persistence remains isolated on the single failure-log writer.
     */
    public void recordChannelFailure(HttpServletRequest request, Long channelId, Object memberId,
                                     String channelModelName, String channelName, BusinessException error) {
        try {
            if (request == null || error == null || request.getRequestURI() == null
                    || !request.getRequestURI().startsWith("/v1/")) return;
            String key = String.valueOf(channelId) + ":" + String.valueOf(memberId);
            synchronized (request) {
                @SuppressWarnings("unchecked")
                Set<String> recorded = (Set<String>) request.getAttribute(FailureLogContext.RECORDED_CHANNEL_FAILURES);
                if (recorded == null) {
                    recorded = new HashSet<>();
                    request.setAttribute(FailureLogContext.RECORDED_CHANNEL_FAILURES, recorded);
                }
                if (!recorded.add(key)) return;
            }
            String traceId = stringAttr(request, FailureLogContext.TRACE_ID);
            if (traceId == null) traceId = SseUtils.currentTraceId();
            String rawBody = error.getUpstreamResponseBody();
            String detail = rawBody != null
                    ? "Upstream API error: " + error.getCode() + " - " + rawBody
                    : error.getMessage();
            String actualModel = hasText(channelModelName)
                    ? channelModelName : stringAttr(request, FailureLogContext.CHANNEL_MODEL_NAME);
            FailureLog entry = FailureLog.builder()
                    .traceId(limit(traceId, 64))
                    .userError(sanitize(SseUtils.GENERIC_UPSTREAM_ERROR_MESSAGE))
                    .channelError(blankToNull(sanitize(detail)))
                    .httpStatus(error.getCode())
                    .modelName(limit(stringAttr(request, FailureLogContext.MODEL_NAME), 100))
                    .channelModelName(limit(actualModel, 100))
                    .channelName(limit(channelName, 100))
                    .protocol(resolveProtocol(request))
                    .createdAt(System.currentTimeMillis())
                    .build();
            writer.execute(() -> persistSafely(entry));
        } catch (Exception e) {
            log.error("提交渠道失败日志写入任务失败（不影响请求）", e);
        }
    }

    void persistSafely(FailureLog entry) {
        try {
            repository.save(entry);
        } catch (Exception e) {
            log.error("记录用户请求失败日志失败（不影响请求）: traceId={}", entry.getTraceId(), e);
        }
    }

    public Page<FailureLog> search(int page, int size, String traceId, Long startTime, Long endTime,
                                   String modelName, String channelModelName, String channelName,
                                   Integer httpStatus) {
        return search(page, size, traceId, false, startTime, endTime, modelName, channelModelName,
                channelName, httpStatus);
    }

    public Page<FailureLog> search(int page, int size, String traceId, boolean exactTraceId,
                                   Long startTime, Long endTime, String modelName,
                                   String channelModelName, String channelName, Integer httpStatus) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        Specification<FailureLog> spec = Specification.where(null);
        if (hasText(traceId)) {
            String value = traceId.trim().toLowerCase(Locale.ROOT);
            spec = exactTraceId
                    ? spec.and((root, query, cb) -> cb.equal(cb.lower(root.get("traceId")), value))
                    : spec.and((root, query, cb) -> cb.like(cb.lower(root.get("traceId")), "%" + value + "%"));
        }
        if (startTime != null) spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), startTime));
        if (endTime != null) spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), endTime));
        if (hasText(modelName)) spec = spec.and((root, query, cb) -> cb.equal(root.get("modelName"), modelName.trim()));
        if (hasText(channelModelName)) spec = spec.and((root, query, cb) -> cb.equal(root.get("channelModelName"), channelModelName.trim()));
        if (hasText(channelName)) spec = spec.and((root, query, cb) -> cb.equal(root.get("channelName"), channelName.trim()));
        if (httpStatus != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("httpStatus"), httpStatus));
        return repository.findAll(spec, PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @Scheduled(cron = "0 15 4 * * ?")
    public void cleanOldData() {
        distributedLock.runIfLocked(CLEAN_LOCK_KEY, CLEAN_LOCK_TTL_SECONDS, () ->
                transactionTemplate.executeWithoutResult(status -> cleanExpired(System.currentTimeMillis())));
    }

    long cleanExpired(long now) {
        long cutoff = now - RETENTION_DAYS * 24L * 60 * 60 * 1000;
        long deleted = repository.deleteByCreatedAtLessThan(cutoff);
        log.info("清理失败日志完成: retentionDays={}, deleted={}", RETENTION_DAYS, deleted);
        return deleted;
    }

    static String sanitize(String value) {
        if (value == null) return null;
        String sanitized = HEADER_SECRET.matcher(value).replaceAll("$1: [REDACTED]");
        sanitized = JSON_SECRET.matcher(sanitized).replaceAll("$1[REDACTED]$2");
        sanitized = QUERY_SECRET.matcher(sanitized).replaceAll("$1[REDACTED]");
        return limit(sanitized, MAX_ERROR_LENGTH);
    }

    private String resolveProtocol(HttpServletRequest request) {
        String protocol = stringAttr(request, FailureLogContext.PROTOCOL);
        if (protocol != null) return protocol;
        String uri = request.getRequestURI();
        if ("/v1/messages".equals(uri)) return RelayProtocol.CLAUDE.name();
        if (uri.startsWith("/v1/models/") && uri.contains("Content")) return RelayProtocol.GEMINI.name();
        return RelayProtocol.OPENAI.name();
    }

    private static String stringAttr(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    private static String blankToNull(String value) { return hasText(value) ? value : null; }
    private static String limit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    @PreDestroy
    void shutdown() {
        writer.shutdown();
    }
}
