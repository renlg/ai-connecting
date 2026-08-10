package com.aiconnecting.service;

import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.common.RedisDistributedLock;
import com.aiconnecting.entity.UsageStats;
import com.aiconnecting.repository.UsageLogRepository;
import com.aiconnecting.repository.UsageStatsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final RedisDistributedLock distributedLock;
    private final CacheInvalidationService cacheInvalidationService;

    public StatsAggregationService(UsageLogRepository usageLogRepository,
                                    UsageStatsRepository usageStatsRepository,
                                    PlatformTransactionManager transactionManager,
                                    RedisDistributedLock distributedLock,
                                    CacheInvalidationService cacheInvalidationService) {
        this.usageLogRepository = usageLogRepository;
        this.usageStatsRepository = usageStatsRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.distributedLock = distributedLock;
        this.cacheInvalidationService = cacheInvalidationService;
    }

    /** 窗口大小（分钟） */
    private static final long WINDOW_MINUTES = 15;

    /** 保留汇总数据的天数上限（超过此天数的旧数据可清理） */
    private static final long MAX_RETENTION_DAYS = 180;

    /** 聚合任务分布式锁 key */
    private static final String AGGREGATION_LOCK_KEY = "job:statsAggregation";
    /** 聚合任务锁 TTL：10 分钟，远大于单窗口聚合的预期耗时，覆盖 usage_logs 表较大时的极端情况 */
    private static final long AGGREGATION_LOCK_TTL_SECONDS = 10 * 60L;
    /**
     * 历史补齐与定时聚合使用同一把锁，但大库全历史扫描需要更长的租约。
     * 60 分钟避免原 10 分钟 TTL 过期后另一实例重复执行；超大数据库仍应通过监控评估此上限。
     */
    private static final long HISTORICAL_INIT_LOCK_TTL_SECONDS = 60 * 60L;
    private static final int MAX_HISTORICAL_INIT_ATTEMPTS = 10;

    private final AtomicBoolean historicalInitializationPending = new AtomicBoolean();
    private final AtomicBoolean historicalInitializationRunning = new AtomicBoolean();
    private final AtomicInteger historicalInitializationAttempts = new AtomicInteger();
    /**
     * 仅统计"锁内执行真正抛出异常"（聚合逻辑本身失败）的连续次数，与"未获取到锁"（可能是被其他
     * 实例持有，也可能是 Redis 暂时不可用）区分开：后者不应该导致永久放弃重试，见
     * {@link #attemptHistoricalInitialization()}。
     */
    private final AtomicInteger historicalInitializationExecutionFailures = new AtomicInteger();

    /** 清理任务分布式锁 key */
    private static final String CLEAN_LOCK_KEY = "job:cleanOldData";
    /** 清理任务锁 TTL：10 分钟，覆盖 usage_stats 大批量删除的预期耗时 */
    private static final long CLEAN_LOCK_TTL_SECONDS = 10 * 60L;

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
        distributedLock.runIfLocked(AGGREGATION_LOCK_KEY, AGGREGATION_LOCK_TTL_SECONDS, () -> {
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
        });
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

                try {
                    usageStatsRepository.save(stats);
                } catch (DataIntegrityViolationException e) {
                    // aggregationLock 租约过期或跨实例竞态导致两边都写同一窗口时，后完成的一方在这里
                    // 收到唯一约束（uk_usage_stats_window）冲突：重新查一次该窗口是否确实已存在，
                    // 存在则视为对方已写入成功，属于良性竞态；不存在则说明是真实的写入失败，继续抛出。
                    if (usageStatsRepository.existsByTimeRange(windowStart, windowEnd)) {
                        log.info("窗口 {} ~ {} 另一实例已写入，跳过: {}", windowStart, windowEnd, e.getMessage());
                        return;
                    }
                    throw e;
                }
                cacheInvalidationService.publish(CacheInvalidationService.USAGE_STATS);
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
        historicalInitializationAttempts.set(0);
        historicalInitializationPending.set(true);
        attemptHistoricalInitialization();
    }

    /**
     * 启动/手动初始化抢锁失败时持续重试，避免与 :00/:15/:30/:45 定时任务碰撞、或 Redis 启动时
     * 短暂不可用后就静默放弃全历史补齐。"未获取到锁"（含 Redis 暂不可用导致 tryLock fail-closed
     * 返回 null 的情况）不设上限，会一直每分钟重试到成功为止——一旦 Redis 恢复即可自动补齐历史数据；
     * 只有锁内执行本身连续真正抛异常（聚合逻辑 bug 等，见 {@link #historicalInitializationExecutionFailures}）
     * 才会在达到上限后放弃，避免无限重复触发同一个真实错误。
     */
    @Scheduled(fixedDelay = 60 * 1000L, initialDelay = 60 * 1000L)
    public void retryHistoricalInitialization() {
        if (historicalInitializationPending.get()) {
            attemptHistoricalInitialization();
        }
    }

    private void attemptHistoricalInitialization() {
        if (!historicalInitializationPending.get()
                || !historicalInitializationRunning.compareAndSet(false, true)) {
            return;
        }
        int attempt = historicalInitializationAttempts.incrementAndGet();
        try {
            boolean acquired = distributedLock.runIfLocked(
                    AGGREGATION_LOCK_KEY, HISTORICAL_INIT_LOCK_TTL_SECONDS, this::doInitializeHistoricalData);
            if (acquired) {
                historicalInitializationPending.set(false);
                historicalInitializationExecutionFailures.set(0);
                return;
            }
            // 未获取到锁：可能是被其他实例正持有（良性竞争），也可能是 Redis 暂不可用
            // （RedisDistributedLock#tryLock 内部 fail-closed 返回 null，两者从这里无法区分）。
            // 两种情况都继续保持 pending=true，下一轮调度自动重试，不做次数上限。
            if (attempt <= MAX_HISTORICAL_INIT_ATTEMPTS || attempt % MAX_HISTORICAL_INIT_ATTEMPTS == 0) {
                log.info("历史用量汇总初始化未获取锁（第 {} 次尝试，可能是其他实例正在处理，或 Redis 暂不可用），"
                        + "1 分钟后重试", attempt);
            }
        } catch (RuntimeException e) {
            int failures = historicalInitializationExecutionFailures.incrementAndGet();
            if (failures >= MAX_HISTORICAL_INIT_ATTEMPTS) {
                historicalInitializationPending.set(false);
                log.warn("历史用量汇总初始化连续 {} 次执行失败，停止自动重试；可稍后通过管理接口重新触发",
                        MAX_HISTORICAL_INIT_ATTEMPTS);
            }
            throw e;
        } finally {
            historicalInitializationRunning.set(false);
        }
    }

    private void doInitializeHistoricalData() {
        log.info("开始初始化用量汇总历史数据...");

        // 从最早的使用日志开始
        List<Object[]> earliest = usageLogRepository.findEarliestCreatedAt();
        if (earliest == null || earliest.isEmpty() || earliest.get(0) == null
                || earliest.get(0).length == 0 || earliest.get(0)[0] == null) {
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
     *
     * 本方法本身不加 {@code @Transactional}：锁必须在事务提交之后才释放，否则另一实例可能在
     * 本实例提交前就抢到锁并发起清理。用 {@link #transactionTemplate} 在 runIfLocked 的
     * job 内显式开启并同步提交事务，确保 finally 释放锁时事务已经提交。
     */
    @Scheduled(cron = "0 30 3 * * ?")
    public void cleanOldData() {
        distributedLock.runIfLocked(CLEAN_LOCK_KEY, CLEAN_LOCK_TTL_SECONDS, () ->
                transactionTemplate.executeWithoutResult(status -> {
                    LocalDateTime cutoff = LocalDateTime.now().minusDays(MAX_RETENTION_DAYS);
                    usageStatsRepository.deleteByEndTimeBefore(cutoff);
                    cacheInvalidationService.publish(CacheInvalidationService.USAGE_STATS);
                    log.info("清理了 {} 天前的旧汇总数据", MAX_RETENTION_DAYS);
                }));
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
