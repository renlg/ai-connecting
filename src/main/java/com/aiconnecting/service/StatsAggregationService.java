package com.aiconnecting.service;

import com.aiconnecting.entity.UsageStats;
import com.aiconnecting.repository.UsageLogRepository;
import com.aiconnecting.repository.UsageStatsRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 用量汇总聚合服务
 *
 * 每 15 分钟执行一次，从 usage_logs 聚合上一时间窗口的数据写入 usage_stats 表。
 * 仪表盘查询从 usage_stats 读取，避免对 usage_logs 的大表全表扫描。
 *
 * 窗口对齐：自然时钟的 00/15/30/45 分。
 * 例如在 10:00 执行时，聚合 09:45:00 ~ 09:59:59.999 的数据。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StatsAggregationService {

    private final UsageLogRepository usageLogRepository;
    private final UsageStatsRepository usageStatsRepository;

    /** 窗口大小（分钟） */
    private static final long WINDOW_MINUTES = 15;

    /** 保留汇总数据的天数上限（超过此天数的旧数据可清理） */
    private static final long MAX_RETENTION_DAYS = 180;

    /**
     * 定时任务：每 15 分钟执行一次，聚合上一完整的 15 分钟窗口。
     *
     * cron = "0 0/15 * * * ?" 表示在 :00, :15, :30, :45 整分钟执行。
     * 每次处理上一个 15 分钟窗口（如 10:00 执行时处理 09:45:00 ~ 09:59:59）。
     */
    @Scheduled(cron = "0 0/15 * * * ?")
    @Transactional
    public void aggregateLastWindow() {
        LocalDateTime now = LocalDateTime.now();
        // 计算上一个完整窗口的起止时间
        LocalDateTime windowEnd = alignWindowEnd(now);
        LocalDateTime windowStart = windowEnd.minusMinutes(WINDOW_MINUTES);

        // 避免重复聚合（防止任务因延迟/重启被重复执行）
        if (usageStatsRepository.existsByTimeRange(windowStart, windowEnd)) {
            log.debug("窗口 {} ~ {} 已聚合，跳过", windowStart, windowEnd);
            return;
        }

        aggregateWindow(windowStart, windowEnd);
    }

    /**
     * 对齐到窗口结束时间：向下取整到最近的 00/15/30/45
     */
    public static LocalDateTime alignWindowEnd(LocalDateTime time) {
        int minute = time.getMinute();
        int alignedMinute = (minute / (int) WINDOW_MINUTES) * (int) WINDOW_MINUTES;
        return time.withMinute(alignedMinute).withSecond(0).withNano(0);
    }

    /**
     * 聚合指定时间窗口的 usage_logs 数据并写入 usage_stats
     */
    @Transactional
    public void aggregateWindow(LocalDateTime windowStart, LocalDateTime windowEnd) {
        // 查询该窗口内的聚合结果（nativeQuery 兼容 SQLite）
        List<Object[]> result = usageLogRepository.aggregateWindow(windowStart, windowEnd);
        if (result.isEmpty()) {
            log.debug("窗口 {} ~ {} 无数据，跳过", windowStart, windowEnd);
            return;
        }

        Object[] row = result.get(0);
        long totalRequests = ((Number) row[0]).longValue();
        if (totalRequests == 0) {
            return; // 无数据
        }

        long totalTokens = ((Number) row[1]).longValue();
        long totalPromptTokens = ((Number) row[2]).longValue();
        long totalCompletionTokens = ((Number) row[3]).longValue();
        double totalCreditCost = ((Number) row[4]).doubleValue();
        long totalCachedPromptTokens = ((Number) row[5]).longValue();
        long totalCacheCreationTokens = ((Number) row[6]).longValue();
        long totalCacheReadTokens = ((Number) row[7]).longValue();

        String dateStr = windowStart.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);

        UsageStats stats = UsageStats.builder()
                .startTime(windowStart)
                .endTime(windowEnd)
                .date(dateStr)
                .totalRequests(totalRequests)
                .totalTokens(totalTokens)
                .totalPromptTokens(totalPromptTokens)
                .totalCompletionTokens(totalCompletionTokens)
                .totalCreditCost(BigDecimal.valueOf(totalCreditCost))
                .totalCachedPromptTokens(totalCachedPromptTokens)
                .totalCacheCreationTokens(totalCacheCreationTokens)
                .totalCacheReadTokens(totalCacheReadTokens)
                .build();

        usageStatsRepository.save(stats);
        log.info("聚合窗口 {} ~ {} 完成：requests={}, tokens={}", windowStart, windowEnd, totalRequests, totalTokens);
    }

    /**
     * 初始化历史数据（将 usage_logs 中所有历史数据按 15 分钟窗口聚合到 usage_stats）
     * 通常在首次部署时调用，或者通过 API 手动触发
     */
    @Transactional
    public void initializeHistoricalData() {
        log.info("开始初始化用量汇总历史数据...");

        // 从最早的使用日志开始
        List<Object[]> earliest = usageLogRepository.findEarliestCreatedAt();
        if (earliest.isEmpty() || earliest.get(0)[0] == null) {
            log.info("usage_logs 表无数据，跳过初始化");
            return;
        }

        LocalDateTime earliestTime = (LocalDateTime) earliest.get(0)[0];
        LocalDateTime now = LocalDateTime.now();

        // 对齐到上一个窗口边界
        LocalDateTime currentStart = alignWindowEnd(earliestTime);
        if (currentStart.isBefore(earliestTime)) {
            currentStart = currentStart.plusMinutes(WINDOW_MINUTES);
        }

        int aggregatedCount = 0;
        while (currentStart.isBefore(now)) {
            LocalDateTime windowEndTime = currentStart.plusMinutes(WINDOW_MINUTES);
            if (windowEndTime.isAfter(now)) {
                break; // 当前不完整的窗口留给定时任务
            }
            if (!usageStatsRepository.existsByTimeRange(currentStart, windowEndTime)) {
                aggregateWindow(currentStart, windowEndTime);
                aggregatedCount++;
            }
            currentStart = windowEndTime;
        }

        log.info("用量汇总历史数据初始化完成，共聚合 {} 个时间窗口", aggregatedCount);
    }

    /**
     * 清理超过保留期限的旧汇总数据
     */
    @Transactional
    public void cleanOldData() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(MAX_RETENTION_DAYS);
        usageStatsRepository.deleteByEndTimeBefore(cutoff);
        log.info("清理了 {} 天前的旧汇总数据", MAX_RETENTION_DAYS);
    }

    /**
     * 应用启动时自动初始化历史汇总数据
     */
    @PostConstruct
    public void init() {
        log.info("检测是否需要在启动时初始化用量汇总历史数据...");
        try {
            initializeHistoricalData();
        } catch (Exception e) {
            log.warn("启动时初始化用量汇总数据时出现异常（可能数据库尚未就绪，将依赖定时任务补充）：{}", e.getMessage());
        }
    }
}
