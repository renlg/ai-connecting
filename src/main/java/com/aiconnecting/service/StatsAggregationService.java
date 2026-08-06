package com.aiconnecting.service;

import com.aiconnecting.entity.UsageStats;
import com.aiconnecting.repository.UsageLogRepository;
import com.aiconnecting.repository.UsageStatsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
@Slf4j
public class StatsAggregationService {

    private final UsageLogRepository usageLogRepository;
    private final UsageStatsRepository usageStatsRepository;
    private final TransactionTemplate transactionTemplate;

    public StatsAggregationService(UsageLogRepository usageLogRepository,
                                    UsageStatsRepository usageStatsRepository,
                                    PlatformTransactionManager transactionManager) {
        this.usageLogRepository = usageLogRepository;
        this.usageStatsRepository = usageStatsRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 窗口大小（分钟） */
    private static final long WINDOW_MINUTES = 15;

    /** 保留汇总数据的天数上限（超过此天数的旧数据可清理） */
    private static final long MAX_RETENTION_DAYS = 180;

    /**
     * 序列化窗口的 exists-check -> aggregate -> insert -> commit 临界区，防止定时任务与启动时的
     * 历史补齐异步任务在同一时间窗口上产生重复写入（数据库层未加唯一约束）。
     * 加锁顺序固定为：先获取该 Java 锁，再在锁内通过 TransactionTemplate 开启/提交数据库事务，
     * 确保提交发生在锁释放之前，避免其他线程在提交前的空隙读到脏状态。
     */
    private final Object aggregationLock = new Object();

    /**
     * 定时任务：每 15 分钟执行一次，聚合上一完整的 15 分钟窗口。
     *
     * cron = "0 0/15 * * * ?" 表示在 :00, :15, :30, :45 整分钟执行。
     * 每次处理上一个 15 分钟窗口（如 10:00 执行时处理 09:45:00 ~ 09:59:59）。
     */
    @Scheduled(cron = "0 0/15 * * * ?")
    public void aggregateLastWindow() {
        LocalDateTime now = LocalDateTime.now();
        // 计算上一个完整窗口的起止时间
        LocalDateTime windowEnd = alignWindowEnd(now);
        LocalDateTime windowStart = windowEnd.minusMinutes(WINDOW_MINUTES);

        // 快速路径的非权威检查（无锁），避免大多数情况下无谓地抢锁；权威检查在 aggregateWindow 的锁内重做
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
     * 聚合指定时间窗口的 usage_logs 数据并写入 usage_stats。
     *
     * 加锁顺序：先获取 Java 锁 aggregationLock，再在锁内通过 TransactionTemplate 开启数据库事务，
     * exists 重检 -> 聚合查询 -> 保存 -> 提交 全部在同一事务内完成，且提交发生在锁释放之前，
     * 因此调度任务与启动时的历史补齐任务不会在同一窗口上产生重复写入。
     */
    public void aggregateWindow(LocalDateTime windowStart, LocalDateTime windowEnd) {
        synchronized (aggregationLock) {
            transactionTemplate.executeWithoutResult(status -> {
                // 锁内权威重检：调度任务与启动时的历史补齐可能并发跑到同一窗口。
                if (usageStatsRepository.existsByTimeRange(windowStart, windowEnd)) {
                    log.debug("窗口 {} ~ {} 已被并发聚合，跳过写入", windowStart, windowEnd);
                    return;
                }

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
            });
        }
    }

    /**
     * 初始化历史数据（将 usage_logs 中所有历史数据按 15 分钟窗口聚合到 usage_stats）
     * 通常在首次部署时调用，或者通过 API 手动触发。
     * 每个窗口的 exists-check -> aggregate -> save -> commit 由 aggregateWindow 内的
     * aggregationLock + TransactionTemplate 独立完成一个短事务，因此本方法无需（也不能通过
     * 同类自调用生效地）包一层大事务。
     */
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
     * 清理超过保留期限的旧汇总数据。每天凌晨 03:30 执行，保留 180 天数据。
     */
    @Scheduled(cron = "0 30 3 * * ?")
    @Transactional
    public void cleanOldData() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(MAX_RETENTION_DAYS);
        usageStatsRepository.deleteByEndTimeBefore(cutoff);
        log.info("清理了 {} 天前的旧汇总数据", MAX_RETENTION_DAYS);
    }

    /**
     * 应用启动时自动初始化历史汇总数据（异步执行，避免阻塞启动并与仪表盘查询抢资源）。
     * 监听 ApplicationReadyEvent 而非 @PostConstruct，因为 @Async 代理在 @PostConstruct
     * 阶段尚未包装到目标 bean 上，此时用 @Async 不会生效。
     */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        log.info("检测是否需要在启动时初始化用量汇总历史数据...");
        try {
            initializeHistoricalData();
        } catch (Exception e) {
            log.warn("启动时初始化用量汇总数据时出现异常（可能数据库尚未就绪，将依赖定时任务补充）：{}", e.getMessage());
        }
    }
}
