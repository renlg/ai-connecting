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
        executor.submit(() -> safeRun(() -> upsertSuccess(channelId, now)));
    }

    public void recordFailureAsync(Long channelId, long now, String reason) {
        executor.submit(() -> safeRun(() -> upsertFailure(channelId, now, reason)));
    }

    public void incrementProbeFailuresAsync(Long channelId, long now) {
        executor.submit(() -> safeRun(() -> upsertProbeFailureIncrement(channelId, now)));
    }

    public void resetProbeFailuresAsync(Long channelId, long now) {
        executor.submit(() -> safeRun(() -> upsertProbeFailureReset(channelId, now)));
    }

    public void deleteByChannelIdAsync(Long channelId) {
        executor.submit(() -> safeRun(() -> jdbcTemplate.update(
                "DELETE FROM channel_health WHERE channel_id = ?", channelId)));
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

    private void upsertSuccess(Long channelId, long now) {
        if (isMysql()) {
            jdbcTemplate.update(
                    "INSERT INTO channel_health (channel_id, last_success_at, probe_failures, updated_at, created_at) " +
                            "VALUES (?, ?, 0, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE last_success_at = VALUES(last_success_at), updated_at = VALUES(updated_at)",
                    channelId, now, now, now);
        } else {
            jdbcTemplate.update(
                    "INSERT INTO channel_health (channel_id, last_success_at, probe_failures, updated_at, created_at) " +
                            "VALUES (?, ?, 0, ?, ?) " +
                            "ON CONFLICT(channel_id) DO UPDATE SET last_success_at = excluded.last_success_at, updated_at = excluded.updated_at",
                    channelId, now, now, now);
        }
    }

    private void upsertFailure(Long channelId, long now, String reason) {
        if (isMysql()) {
            jdbcTemplate.update(
                    "INSERT INTO channel_health (channel_id, last_failure_at, last_failure_reason, probe_failures, updated_at, created_at) " +
                            "VALUES (?, ?, ?, 0, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE last_failure_at = VALUES(last_failure_at), last_failure_reason = VALUES(last_failure_reason), updated_at = VALUES(updated_at)",
                    channelId, now, reason, now, now);
        } else {
            jdbcTemplate.update(
                    "INSERT INTO channel_health (channel_id, last_failure_at, last_failure_reason, probe_failures, updated_at, created_at) " +
                            "VALUES (?, ?, ?, 0, ?, ?) " +
                            "ON CONFLICT(channel_id) DO UPDATE SET last_failure_at = excluded.last_failure_at, last_failure_reason = excluded.last_failure_reason, updated_at = excluded.updated_at",
                    channelId, now, reason, now, now);
        }
    }

    private void upsertProbeFailureIncrement(Long channelId, long now) {
        if (isMysql()) {
            jdbcTemplate.update(
                    "INSERT INTO channel_health (channel_id, probe_failures, updated_at, created_at) " +
                            "VALUES (?, 1, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE probe_failures = probe_failures + 1, updated_at = VALUES(updated_at)",
                    channelId, now, now);
        } else {
            jdbcTemplate.update(
                    "INSERT INTO channel_health (channel_id, probe_failures, updated_at, created_at) " +
                            "VALUES (?, 1, ?, ?) " +
                            "ON CONFLICT(channel_id) DO UPDATE SET probe_failures = channel_health.probe_failures + 1, updated_at = excluded.updated_at",
                    channelId, now, now);
        }
    }

    private void upsertProbeFailureReset(Long channelId, long now) {
        if (isMysql()) {
            jdbcTemplate.update(
                    "INSERT INTO channel_health (channel_id, probe_failures, updated_at, created_at) " +
                            "VALUES (?, 0, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE probe_failures = 0, updated_at = VALUES(updated_at)",
                    channelId, now, now);
        } else {
            jdbcTemplate.update(
                    "INSERT INTO channel_health (channel_id, probe_failures, updated_at, created_at) " +
                            "VALUES (?, 0, ?, ?) " +
                            "ON CONFLICT(channel_id) DO UPDATE SET probe_failures = 0, updated_at = excluded.updated_at",
                    channelId, now, now);
        }
    }
}
