package com.aiconnecting.service;

import com.aiconnecting.config.DbDialectUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 渠道健康看板数据落库（每渠道一行），供重启后恢复展示。异步写入，写失败只记日志，
 * 绝不影响转发主流程；单线程执行器即可，写入量小且无需保证顺序性以外的强一致。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChannelHealthPersistenceService {

    private final JdbcTemplate jdbcTemplate;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "channel-health-persist");
        t.setDaemon(true);
        return t;
    });

    @jakarta.annotation.PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void recordSuccessAsync(Long channelId, long now) {
        safeEnqueue(() -> safeRun(() -> upsertSuccess(channelId, now)));
    }

    public void recordFailureAsync(Long channelId, long now, String reason) {
        safeEnqueue(() -> safeRun(() -> upsertFailure(channelId, now, reason)));
    }

    public void incrementProbeFailuresAsync(Long channelId, long now) {
        safeEnqueue(() -> safeRun(() -> upsertProbeFailureIncrement(channelId, now)));
    }

    public void resetProbeFailuresAsync(Long channelId, long now) {
        safeEnqueue(() -> safeRun(() -> upsertProbeFailureReset(channelId, now)));
    }

    public void deleteByChannelIdAsync(Long channelId) {
        safeEnqueue(() -> safeRun(() -> jdbcTemplate.update(
                "DELETE FROM channel_health WHERE channel_id = ?", channelId)));
    }

    /**
     * executor.submit() can throw RejectedExecutionException once shutdown has begun (PreDestroy
     * races with in-flight requests); callers in ChannelHealthTracker run further in-memory circuit
     * breaker logic right after invoking us, so an uncaught exception here must never propagate.
     */
    private void safeEnqueue(Runnable task) {
        try {
            executor.submit(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.warn("渠道健康数据落库任务提交失败（执行器已关闭），忽略: {}", e.getMessage());
        }
    }

    private void safeRun(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("渠道健康数据落库失败，忽略（不影响转发流程）: {}", e.getMessage());
        }
    }

    private boolean isMysql() {
        return DbDialectUtil.isMysql(jdbcTemplate);
    }

    /**
     * SELECT ... WHERE EXISTS(channels) instead of a plain VALUES(...) insert so a health event that
     * lands after the channel row was deleted produces zero rows rather than resurrecting the
     * channel_health row (channel_health has no FK and deletion of it is itself async).
     */
    private void upsertSuccess(Long channelId, long now) {
        if (isMysql()) {
            jdbcTemplate.update(
                    "INSERT INTO channel_health (channel_id, last_success_at, probe_failures, updated_at, created_at) " +
                            "SELECT ?, ?, 0, ?, ? FROM DUAL WHERE EXISTS (SELECT 1 FROM channels WHERE id = ?) " +
                            "ON DUPLICATE KEY UPDATE last_success_at = VALUES(last_success_at), updated_at = VALUES(updated_at)",
                    channelId, now, now, now, channelId);
        } else {
            jdbcTemplate.update(
                    "INSERT INTO channel_health (channel_id, last_success_at, probe_failures, updated_at, created_at) " +
                            "SELECT ?, ?, 0, ?, ? WHERE EXISTS (SELECT 1 FROM channels WHERE id = ?) " +
                            "ON CONFLICT(channel_id) DO UPDATE SET last_success_at = excluded.last_success_at, updated_at = excluded.updated_at",
                    channelId, now, now, now, channelId);
        }
    }

    private void upsertFailure(Long channelId, long now, String reason) {
        if (isMysql()) {
            jdbcTemplate.update(
                    "INSERT INTO channel_health (channel_id, last_failure_at, last_failure_reason, probe_failures, updated_at, created_at) " +
                            "SELECT ?, ?, ?, 0, ?, ? FROM DUAL WHERE EXISTS (SELECT 1 FROM channels WHERE id = ?) " +
                            "ON DUPLICATE KEY UPDATE last_failure_at = VALUES(last_failure_at), last_failure_reason = VALUES(last_failure_reason), updated_at = VALUES(updated_at)",
                    channelId, now, reason, now, now, channelId);
        } else {
            jdbcTemplate.update(
                    "INSERT INTO channel_health (channel_id, last_failure_at, last_failure_reason, probe_failures, updated_at, created_at) " +
                            "SELECT ?, ?, ?, 0, ?, ? WHERE EXISTS (SELECT 1 FROM channels WHERE id = ?) " +
                            "ON CONFLICT(channel_id) DO UPDATE SET last_failure_at = excluded.last_failure_at, last_failure_reason = excluded.last_failure_reason, updated_at = excluded.updated_at",
                    channelId, now, reason, now, now, channelId);
        }
    }

    private void upsertProbeFailureIncrement(Long channelId, long now) {
        if (isMysql()) {
            jdbcTemplate.update(
                    "INSERT INTO channel_health (channel_id, probe_failures, updated_at, created_at) " +
                            "SELECT ?, 1, ?, ? FROM DUAL WHERE EXISTS (SELECT 1 FROM channels WHERE id = ?) " +
                            "ON DUPLICATE KEY UPDATE probe_failures = probe_failures + 1, updated_at = VALUES(updated_at)",
                    channelId, now, now, channelId);
        } else {
            jdbcTemplate.update(
                    "INSERT INTO channel_health (channel_id, probe_failures, updated_at, created_at) " +
                            "SELECT ?, 1, ?, ? WHERE EXISTS (SELECT 1 FROM channels WHERE id = ?) " +
                            "ON CONFLICT(channel_id) DO UPDATE SET probe_failures = channel_health.probe_failures + 1, updated_at = excluded.updated_at",
                    channelId, now, now, channelId);
        }
    }

    private void upsertProbeFailureReset(Long channelId, long now) {
        if (isMysql()) {
            jdbcTemplate.update(
                    "INSERT INTO channel_health (channel_id, probe_failures, updated_at, created_at) " +
                            "SELECT ?, 0, ?, ? FROM DUAL WHERE EXISTS (SELECT 1 FROM channels WHERE id = ?) " +
                            "ON DUPLICATE KEY UPDATE probe_failures = 0, updated_at = VALUES(updated_at)",
                    channelId, now, now, channelId);
        } else {
            jdbcTemplate.update(
                    "INSERT INTO channel_health (channel_id, probe_failures, updated_at, created_at) " +
                            "SELECT ?, 0, ?, ? WHERE EXISTS (SELECT 1 FROM channels WHERE id = ?) " +
                            "ON CONFLICT(channel_id) DO UPDATE SET probe_failures = 0, updated_at = excluded.updated_at",
                    channelId, now, now, channelId);
        }
    }
}
