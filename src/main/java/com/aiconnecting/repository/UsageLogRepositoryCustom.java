package com.aiconnecting.repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 按日期分桶的原生 SQL 查询：SQLite 用 datetime()/unixepoch 函数，MySQL 无此函数，
 * 需要按方言分别拼 SQL，因此不能用声明式 @Query 一份 SQL 通吃两种方言，改为自定义实现。
 */
public interface UsageLogRepositoryCustom {

    List<Object[]> findDailyCreditCostByTokenIdSince(Long tokenId, LocalDateTime since);

    List<Object[]> findLogDetailsByTokenIdAndDate(Long tokenId, String date);

    List<Object[]> findDailyCreditCostByTokenIdsSince(List<Long> tokenIds, LocalDateTime since);

    List<Object[]> findDailyTokenByModelSince(LocalDateTime since);

    List<Object[]> findDailyTokenByModelByTokenIdsSince(List<Long> tokenIds, LocalDateTime since);

    List<Object[]> findDailyCreditByModelSince(LocalDateTime since);

    List<Object[]> findDailyCreditByModelByTokenIdsSince(List<Long> tokenIds, LocalDateTime since);
}
