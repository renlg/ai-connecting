package com.aiconnecting.service;

import com.aiconnecting.common.SseUtils;
import com.aiconnecting.entity.ChannelFailureRecord;
import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.repository.ChannelFailureRecordRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class ChannelFailureRecorder {

    private final ChannelFailureRecordRepository repository;
    private final ObjectProvider<ModelConfigService> modelConfigServiceProvider;

    private static final int MAX_ERROR_LENGTH = 500;
    private static final int MAX_MODEL_LENGTH = 200;
    private static final long DEDUP_TTL_MS = 600_000L;

    private static final AtomicLong THREAD_COUNTER = new AtomicLong();
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(
            1, 2, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(512),
            r -> {
                Thread t = new Thread(r, "failure-recorder-" + THREAD_COUNTER.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
    );

    private final ConcurrentHashMap<String, Long> dedupMap = new ConcurrentHashMap<>();

    public ChannelFailureRecorder(ChannelFailureRecordRepository repository,
                                  ObjectProvider<ModelConfigService> modelConfigServiceProvider) {
        this.repository = repository;
        this.modelConfigServiceProvider = modelConfigServiceProvider;
    }

    public void record(Long channelId, String modelName, int errorCode, String errorMessage) {
        if (channelId == null) return;
        String traceId = SseUtils.currentTraceId();
        String normalizedName = modelName != null ? modelName : "";
        String dedupKey = traceId + ":" + channelId + ":" + normalizedName;
        Long existing = dedupMap.putIfAbsent(dedupKey, System.currentTimeMillis());
        if (existing != null) return;

        try {
            writer.submit(() -> {
                try {
                    String resolvedName = resolveModelName(normalizedName);
                    ChannelFailureRecord record = ChannelFailureRecord.builder()
                            .channelId(channelId)
                            .modelName(truncate(resolvedName, MAX_MODEL_LENGTH))
                            .errorCode(String.valueOf(errorCode))
                            .errorMessage(truncate(errorMessage, MAX_ERROR_LENGTH))
                            .analyzed(false)
                            .createdAt(System.currentTimeMillis())
                            .build();
                    repository.save(record);
                } catch (Exception e) {
                    log.warn("渠道失败记录写入异常(不影响请求): channelId={}, error={}", channelId, e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("渠道失败记录队列已满，丢弃: channelId={}", channelId);
        }

        if (dedupMap.size() > 2000) {
            long now = System.currentTimeMillis();
            dedupMap.entrySet().removeIf(entry -> now - entry.getValue() > DEDUP_TTL_MS);
        }
    }

    private String resolveModelName(String nameOrId) {
        if (nameOrId == null || nameOrId.isEmpty()) return nameOrId;
        try {
            Long id = Long.parseLong(nameOrId);
        } catch (NumberFormatException e) {
            return nameOrId;
        }
        ModelConfigService svc = modelConfigServiceProvider.getIfAvailable();
        if (svc != null) {
            try {
                ModelConfig config = svc.getById(Long.parseLong(nameOrId));
                if (config != null && config.getName() != null) return config.getName();
            } catch (Exception ignored) {}
        }
        return nameOrId;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    @PreDestroy
    void shutdown() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
