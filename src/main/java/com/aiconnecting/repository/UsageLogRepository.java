package com.aiconnecting.repository;

import com.aiconnecting.entity.UsageLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface UsageLogRepository extends JpaRepository<UsageLog, Long>, UsageLogRepositoryCustom {

    @Query("SELECT COALESCE(SUM(u.totalTokens), 0) FROM UsageLog u WHERE u.createdAt >= :since")
    Long sumTotalTokensSince(LocalDateTime since);

    @Query("SELECT COUNT(u) FROM UsageLog u WHERE u.createdAt >= :since")
    Long countRequestsSince(LocalDateTime since);

    @Query("SELECT COALESCE(SUM(u.promptTokens), 0) FROM UsageLog u")
    long sumPromptTokens();

    @Query("SELECT COALESCE(SUM(u.completionTokens), 0) FROM UsageLog u")
    long sumCompletionTokens();

    @Query("SELECT COALESCE(SUM(u.promptTokens), 0) FROM UsageLog u WHERE u.createdAt >= :since")
    long sumPromptTokensSince(LocalDateTime since);

    @Query("SELECT COALESCE(SUM(u.completionTokens), 0) FROM UsageLog u WHERE u.createdAt >= :since")
    long sumCompletionTokensSince(LocalDateTime since);

    @Query("SELECT COALESCE(SUM(u.creditCost), 0.0) FROM UsageLog u")
    double sumCreditCost();

    @Query("SELECT COALESCE(SUM(u.creditCost), 0.0) FROM UsageLog u WHERE u.createdAt >= :since")
    double sumCreditCostSince(LocalDateTime since);

    @Query("SELECT COALESCE(SUM(u.promptTokensCacheHit), 0) FROM UsageLog u")
    long sumCachedPromptTokens();

    @Query("SELECT COALESCE(SUM(u.promptTokensCacheHit), 0) FROM UsageLog u WHERE u.createdAt >= :since")
    long sumCachedPromptTokensSince(LocalDateTime since);

    // findDailyCreditCostByTokenIdSince / findLogDetailsByTokenIdAndDate 使用 SQLite 专属的
    // datetime()/unixepoch 函数做按天分桶，MySQL 无此函数，已迁移到 UsageLogRepositoryImpl 按方言分支实现

    // Dashboard 聚合查询：一次查询获取所有指标（Token 相关列仅统计 text 类型模型，请求数/积分不受影响）
    @Query(value = "SELECT COALESCE(COUNT(*), 0), " +
            "COALESCE(SUM(CASE WHEN model IN (SELECT name FROM model_configs WHERE type = 'text') THEN total_tokens ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE WHEN model IN (SELECT name FROM model_configs WHERE type = 'text') THEN prompt_tokens ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE WHEN model IN (SELECT name FROM model_configs WHERE type = 'text') THEN completion_tokens ELSE 0 END), 0), " +
            "COALESCE(SUM(credit_cost), 0.0), " +
            "COALESCE(SUM(CASE WHEN model IN (SELECT name FROM model_configs WHERE type = 'text') THEN prompt_tokens_cache_hit ELSE 0 END), 0) " +
            "FROM usage_logs WHERE token_id IN :tokenIds AND created_at >= :since", nativeQuery = true)
    List<Object[]> sumAllMetricsByTokenIdsSince(@Param("tokenIds") List<Long> tokenIds, @Param("since") LocalDateTime since);

    // 仅统计 text 类型模型的缓存创建/读取 Token（用于仪表盘）
    @Query(value = "SELECT COALESCE(SUM(cached_tokens_cache_creation), 0), COALESCE(SUM(cached_tokens_cache_read), 0) " +
            "FROM usage_logs WHERE token_id IN :tokenIds AND created_at >= :since " +
            "AND model IN (SELECT name FROM model_configs WHERE type = 'text')", nativeQuery = true)
    List<Object[]> sumCacheTokensByTokenIdsSince(@Param("tokenIds") List<Long> tokenIds, @Param("since") LocalDateTime since);

    // findDailyCreditCostByTokenIdsSince / findDailyTokenByModelSince / findDailyTokenByModelByTokenIdsSince
    // 同样使用 SQLite 专属的 datetime()/unixepoch 函数做按天分桶，已迁移到 UsageLogRepositoryImpl 按方言分支实现

    // 全局按模型名统计积分消耗（用于仪表盘"今日消耗积分"卡片 tooltip，按类型合并在服务层完成，
    // 避免 JOIN model_configs：同名多行会产生笛卡尔积，见 model_configs.name='deepseek-v4-flash' 重复问题）
    @Query(value = "SELECT model, COALESCE(SUM(credit_cost), 0) " +
            "FROM usage_logs WHERE created_at >= :since GROUP BY model", nativeQuery = true)
    List<Object[]> findCreditByModelSince(@Param("since") LocalDateTime since);

    // 按 Token ID 列表统计按模型名积分消耗
    @Query(value = "SELECT model, COALESCE(SUM(credit_cost), 0) " +
            "FROM usage_logs WHERE token_id IN :tokenIds AND created_at >= :since GROUP BY model", nativeQuery = true)
    List<Object[]> findCreditByModelByTokenIdsSince(@Param("tokenIds") List<Long> tokenIds, @Param("since") LocalDateTime since);

    // findDailyCreditByModelSince / findDailyCreditByModelByTokenIdsSince 同样使用 SQLite 专属的
    // datetime()/unixepoch 函数做按天分桶，已迁移到 UsageLogRepositoryImpl 按方言分支实现

    // ========== 汇总表聚合查询 ==========

    /**
     * 聚合指定时间窗口内的所有指标（供 StatsAggregationService 使用）
     * Token 相关列仅统计 text 类型模型，请求数/积分消耗不受模型类型影响
     */
    @Query(value = "SELECT " +
            "COALESCE(COUNT(*), 0), " +
            "COALESCE(SUM(CASE WHEN model IN (SELECT name FROM model_configs WHERE type = 'text') THEN total_tokens ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE WHEN model IN (SELECT name FROM model_configs WHERE type = 'text') THEN prompt_tokens ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE WHEN model IN (SELECT name FROM model_configs WHERE type = 'text') THEN completion_tokens ELSE 0 END), 0), " +
            "COALESCE(SUM(credit_cost), 0.0), " +
            "COALESCE(SUM(CASE WHEN model IN (SELECT name FROM model_configs WHERE type = 'text') THEN prompt_tokens_cache_hit ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE WHEN model IN (SELECT name FROM model_configs WHERE type = 'text') THEN cached_tokens_cache_creation ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE WHEN model IN (SELECT name FROM model_configs WHERE type = 'text') THEN cached_tokens_cache_read ELSE 0 END), 0) " +
            "FROM usage_logs WHERE created_at >= :startTime AND created_at < :endTime", nativeQuery = true)
    List<Object[]> aggregateWindow(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    /**
     * 查找最早的使用日志创建时间（用于初始化历史汇总数据）
     */
    @Query("SELECT MIN(u.createdAt) FROM UsageLog u")
    List<Object[]> findEarliestCreatedAt();

    /**
     * 查询今天当前窗口（从最近一个对齐点到现在）的原始数据聚合
     * 用于补齐仪表盘今日统计中汇总表未覆盖的部分
     */
    @Query(value = "SELECT " +
            "COALESCE(COUNT(*), 0), " +
            "COALESCE(SUM(CASE WHEN model IN (SELECT name FROM model_configs WHERE type = 'text') THEN total_tokens ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE WHEN model IN (SELECT name FROM model_configs WHERE type = 'text') THEN prompt_tokens ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE WHEN model IN (SELECT name FROM model_configs WHERE type = 'text') THEN completion_tokens ELSE 0 END), 0), " +
            "COALESCE(SUM(credit_cost), 0.0), " +
            "COALESCE(SUM(CASE WHEN model IN (SELECT name FROM model_configs WHERE type = 'text') THEN prompt_tokens_cache_hit ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE WHEN model IN (SELECT name FROM model_configs WHERE type = 'text') THEN cached_tokens_cache_creation ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE WHEN model IN (SELECT name FROM model_configs WHERE type = 'text') THEN cached_tokens_cache_read ELSE 0 END), 0) " +
            "FROM usage_logs WHERE created_at >= :since", nativeQuery = true)
    List<Object[]> aggregateSince(@Param("since") LocalDateTime since);
}
