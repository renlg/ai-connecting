package com.aiconnecting.repository;

import com.aiconnecting.entity.UsageStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface UsageStatsRepository extends JpaRepository<UsageStats, Long> {

    /** 查指定时间区间内是否存在已聚合数据 */
    @Query("SELECT COUNT(u) > 0 FROM UsageStats u WHERE u.startTime >= :start AND u.endTime <= :end")
    boolean existsByTimeRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** 查询某个开始时间之后的聚合数据（按 startTime 升序） */
    List<UsageStats> findByStartTimeGreaterThanEqualOrderByStartTimeAsc(LocalDateTime startTime);

    /** 查某日的所有聚合行 */
    List<UsageStats> findByDateOrderByStartTimeAsc(String date);

    /** 查某个时间范围之后的所有聚合行 */
    List<UsageStats> findByEndTimeGreaterThanEqualOrderByStartTimeAsc(LocalDateTime endTime);

    /** 删除某个时间点之前的聚合数据（用于清理旧数据） */
    void deleteByEndTimeBefore(LocalDateTime endTime);

    // ========== 仪表盘聚合查询 ==========

    /** 所有汇总行的 SUM 聚合 —— 用于全局统计 */
    @Query("SELECT COALESCE(SUM(u.totalRequests), 0), " +
           "COALESCE(SUM(u.totalTokens), 0), " +
           "COALESCE(SUM(u.totalPromptTokens), 0), " +
           "COALESCE(SUM(u.totalCompletionTokens), 0), " +
           "COALESCE(SUM(u.totalCreditCost), 0), " +
           "COALESCE(SUM(u.totalCachedPromptTokens), 0), " +
           "COALESCE(SUM(u.totalCacheCreationTokens), 0), " +
           "COALESCE(SUM(u.totalCacheReadTokens), 0) " +
           "FROM UsageStats u")
    List<Object[]> sumAll();

    /** 指定日期之后（含）的汇总行 SUM —— 用于今日统计 */
    @Query("SELECT COALESCE(SUM(u.totalRequests), 0), " +
           "COALESCE(SUM(u.totalTokens), 0), " +
           "COALESCE(SUM(u.totalPromptTokens), 0), " +
           "COALESCE(SUM(u.totalCompletionTokens), 0), " +
           "COALESCE(SUM(u.totalCreditCost), 0), " +
           "COALESCE(SUM(u.totalCachedPromptTokens), 0), " +
           "COALESCE(SUM(u.totalCacheCreationTokens), 0), " +
           "COALESCE(SUM(u.totalCacheReadTokens), 0) " +
           "FROM UsageStats u WHERE u.date >= :date")
    List<Object[]> sumSinceDate(@Param("date") String date);

    /** 指定时间之后的所有汇总行 SUM（用于当日 + 历史） */
    @Query("SELECT COALESCE(SUM(u.totalRequests), 0), " +
           "COALESCE(SUM(u.totalTokens), 0), " +
           "COALESCE(SUM(u.totalPromptTokens), 0), " +
           "COALESCE(SUM(u.totalCompletionTokens), 0), " +
           "COALESCE(SUM(u.totalCreditCost), 0), " +
           "COALESCE(SUM(u.totalCachedPromptTokens), 0), " +
           "COALESCE(SUM(u.totalCacheCreationTokens), 0), " +
           "COALESCE(SUM(u.totalCacheReadTokens), 0) " +
           "FROM UsageStats u WHERE u.startTime >= :since")
    List<Object[]> sumSinceTime(@Param("since") LocalDateTime since);

    /** 按日期分组聚合 —— 用于每日统计（含 cache creation/read） */
    @Query("SELECT u.date, " +
           "COALESCE(SUM(u.totalRequests), 0), " +
           "COALESCE(SUM(u.totalTokens), 0), " +
           "COALESCE(SUM(u.totalPromptTokens), 0), " +
           "COALESCE(SUM(u.totalCompletionTokens), 0), " +
           "COALESCE(SUM(u.totalCreditCost), 0), " +
           "COALESCE(SUM(u.totalCachedPromptTokens), 0), " +
           "COALESCE(SUM(u.totalCacheCreationTokens), 0), " +
           "COALESCE(SUM(u.totalCacheReadTokens), 0) " +
           "FROM UsageStats u WHERE u.date >= :startDate GROUP BY u.date ORDER BY u.date ASC")
    List<Object[]> sumGroupByDateSince(@Param("startDate") String startDate);

    /** 查某日（含）之后的所有汇总行（按日期分组 + 累计总量），用于仪表盘今日累计 */
    @Query("SELECT COALESCE(SUM(u.totalRequests), 0), " +
           "COALESCE(SUM(u.totalTokens), 0), " +
           "COALESCE(SUM(u.totalPromptTokens), 0), " +
           "COALESCE(SUM(u.totalCompletionTokens), 0), " +
           "COALESCE(SUM(u.totalCreditCost), 0), " +
           "COALESCE(SUM(u.totalCachedPromptTokens), 0), " +
           "COALESCE(SUM(u.totalCacheCreationTokens), 0), " +
           "COALESCE(SUM(u.totalCacheReadTokens), 0) " +
           "FROM UsageStats u WHERE u.date = :date")
    List<Object[]> sumByDate(@Param("date") String date);
}
