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

    /** 删除某个时间点之前的聚合数据（用于清理旧数据） */
    void deleteByEndTimeBefore(LocalDateTime endTime);

    // ========== 仪表盘聚合查询 ==========

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
}
