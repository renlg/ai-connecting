package com.aiconnecting.repository;

import com.aiconnecting.entity.UsageLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface UsageLogRepository extends JpaRepository<UsageLog, Long> {

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

    @Query(value = "SELECT DATE(datetime(created_at / 1000, 'unixepoch', '+8 hours')) as date, " +
            "COALESCE(SUM(credit_cost), 0) as credits " +
            "FROM usage_logs WHERE token_id = ?1 AND created_at >= ?2 " +
            "GROUP BY DATE(datetime(created_at / 1000, 'unixepoch', '+8 hours')) ORDER BY date DESC", nativeQuery = true)
    List<Object[]> findDailyCreditCostByTokenIdSince(Long tokenId, LocalDateTime since);

    // Dashboard 聚合查询：一次查询获取所有指标
    @Query("SELECT COALESCE(COUNT(u), 0), COALESCE(SUM(u.totalTokens), 0), " +
           "COALESCE(SUM(u.promptTokens), 0), COALESCE(SUM(u.completionTokens), 0), COALESCE(SUM(u.creditCost), 0.0), " +
           "COALESCE(SUM(u.promptTokensCacheHit), 0) " +
           "FROM UsageLog u WHERE u.tokenId IN :tokenIds")
    List<Object[]> sumAllMetricsByTokenIds(@Param("tokenIds") List<Long> tokenIds);

    @Query("SELECT COALESCE(COUNT(u), 0), COALESCE(SUM(u.totalTokens), 0), " +
           "COALESCE(SUM(u.promptTokens), 0), COALESCE(SUM(u.completionTokens), 0), COALESCE(SUM(u.creditCost), 0.0), " +
           "COALESCE(SUM(u.promptTokensCacheHit), 0) " +
           "FROM UsageLog u WHERE u.tokenId IN :tokenIds AND u.createdAt >= :since")
    List<Object[]> sumAllMetricsByTokenIdsSince(@Param("tokenIds") List<Long> tokenIds, @Param("since") LocalDateTime since);

    @Query("SELECT COALESCE(SUM(u.cachedTokensCacheCreation), 0), COALESCE(SUM(u.cachedTokensCacheRead), 0) FROM UsageLog u WHERE u.tokenId IN :tokenIds")
    List<Object[]> sumCacheTokensByTokenIds(@Param("tokenIds") List<Long> tokenIds);

    @Query("SELECT COALESCE(SUM(u.cachedTokensCacheCreation), 0), COALESCE(SUM(u.cachedTokensCacheRead), 0) FROM UsageLog u WHERE u.tokenId IN :tokenIds AND u.createdAt >= :since")
    List<Object[]> sumCacheTokensByTokenIdsSince(@Param("tokenIds") List<Long> tokenIds, @Param("since") LocalDateTime since);

    @Query("SELECT COALESCE(SUM(u.cachedTokensCacheCreation), 0), COALESCE(SUM(u.cachedTokensCacheRead), 0) FROM UsageLog u")
    List<Object[]> sumCacheTokensGlobal();

    @Query("SELECT COALESCE(SUM(u.cachedTokensCacheCreation), 0), COALESCE(SUM(u.cachedTokensCacheRead), 0) FROM UsageLog u WHERE u.createdAt >= :since")
    List<Object[]> sumCacheTokensGlobalSince(@Param("since") LocalDateTime since);

    // 全局每日消耗积分（created_at 以 epoch 毫秒存储，需转换为北京时间日期）
    @Query(value = "SELECT DATE(datetime(created_at / 1000, 'unixepoch', '+8 hours')) as date, " +
            "COALESCE(SUM(credit_cost), 0) as credits " +
            "FROM usage_logs WHERE created_at >= :since GROUP BY date ORDER BY date ASC", nativeQuery = true)
    List<Object[]> findDailyCreditCostSince(@Param("since") LocalDateTime since);

    // 按 Token ID 列表统计每日消耗积分
    @Query(value = "SELECT DATE(datetime(created_at / 1000, 'unixepoch', '+8 hours')) as date, " +
            "COALESCE(SUM(credit_cost), 0) as credits " +
            "FROM usage_logs WHERE token_id IN :tokenIds AND created_at >= :since GROUP BY date ORDER BY date ASC", nativeQuery = true)
    List<Object[]> findDailyCreditCostByTokenIdsSince(@Param("tokenIds") List<Long> tokenIds, @Param("since") LocalDateTime since);

    // 全局每日按模型统计 token 数
    @Query(value = "SELECT DATE(datetime(created_at / 1000, 'unixepoch', '+8 hours')) as date, model, " +
            "COALESCE(SUM(prompt_tokens), 0), COALESCE(SUM(prompt_tokens_cache_hit), 0), COALESCE(SUM(total_tokens), 0) " +
            "FROM usage_logs WHERE created_at >= :since GROUP BY date, model ORDER BY date ASC, model ASC", nativeQuery = true)
    List<Object[]> findDailyTokenByModelSince(@Param("since") LocalDateTime since);

    // 按 Token ID 列表统计每日按模型 token 数
    @Query(value = "SELECT DATE(datetime(created_at / 1000, 'unixepoch', '+8 hours')) as date, model, " +
            "COALESCE(SUM(prompt_tokens), 0), COALESCE(SUM(prompt_tokens_cache_hit), 0), COALESCE(SUM(total_tokens), 0) " +
            "FROM usage_logs WHERE token_id IN :tokenIds AND created_at >= :since GROUP BY date, model ORDER BY date ASC, model ASC", nativeQuery = true)
    List<Object[]> findDailyTokenByModelByTokenIdsSince(@Param("tokenIds") List<Long> tokenIds, @Param("since") LocalDateTime since);

    // ========== 汇总表聚合查询 ==========

    /**
     * 聚合指定时间窗口内的所有指标（供 StatsAggregationService 使用）
     */
    @Query(value = "SELECT " +
            "COALESCE(COUNT(*), 0), " +
            "COALESCE(SUM(total_tokens), 0), " +
            "COALESCE(SUM(prompt_tokens), 0), " +
            "COALESCE(SUM(completion_tokens), 0), " +
            "COALESCE(SUM(credit_cost), 0.0), " +
            "COALESCE(SUM(prompt_tokens_cache_hit), 0), " +
            "COALESCE(SUM(cached_tokens_cache_creation), 0), " +
            "COALESCE(SUM(cached_tokens_cache_read), 0) " +
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
            "COALESCE(SUM(total_tokens), 0), " +
            "COALESCE(SUM(prompt_tokens), 0), " +
            "COALESCE(SUM(completion_tokens), 0), " +
            "COALESCE(SUM(credit_cost), 0.0), " +
            "COALESCE(SUM(prompt_tokens_cache_hit), 0), " +
            "COALESCE(SUM(cached_tokens_cache_creation), 0), " +
            "COALESCE(SUM(cached_tokens_cache_read), 0) " +
            "FROM usage_logs WHERE created_at >= :since", nativeQuery = true)
    List<Object[]> aggregateSince(@Param("since") LocalDateTime since);
}
