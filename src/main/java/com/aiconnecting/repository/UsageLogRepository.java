package com.aiconnecting.repository;

import com.aiconnecting.entity.UsageLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface UsageLogRepository extends JpaRepository<UsageLog, Long> {

    List<UsageLog> findByTokenIdOrderByCreatedAtDesc(Long tokenId);

    List<UsageLog> findByChannelIdOrderByCreatedAtDesc(Long channelId);

    @Query("SELECT SUM(u.totalTokens) FROM UsageLog u WHERE u.createdAt >= :since")
    Long sumTotalTokensSince(LocalDateTime since);

    @Query("SELECT COUNT(u) FROM UsageLog u WHERE u.createdAt >= :since")
    Long countRequestsSince(LocalDateTime since);

    @Query("SELECT COUNT(u) FROM UsageLog u WHERE u.tokenId IN :tokenIds")
    long countByTokenIdIn(List<Long> tokenIds);

    @Query("SELECT COUNT(u) FROM UsageLog u WHERE u.tokenId IN :tokenIds AND u.createdAt >= :since")
    long countByTokenIdInSince(List<Long> tokenIds, LocalDateTime since);

    @Query("SELECT COALESCE(SUM(u.totalTokens), 0) FROM UsageLog u WHERE u.tokenId IN :tokenIds AND u.createdAt >= :since")
    long sumTokensByTokenIdInSince(List<Long> tokenIds, LocalDateTime since);

    @Query("SELECT COALESCE(SUM(u.promptTokens), 0) FROM UsageLog u")
    long sumPromptTokens();

    @Query("SELECT COALESCE(SUM(u.completionTokens), 0) FROM UsageLog u")
    long sumCompletionTokens();

    @Query("SELECT COALESCE(SUM(u.promptTokens), 0) FROM UsageLog u WHERE u.createdAt >= :since")
    long sumPromptTokensSince(LocalDateTime since);

    @Query("SELECT COALESCE(SUM(u.completionTokens), 0) FROM UsageLog u WHERE u.createdAt >= :since")
    long sumCompletionTokensSince(LocalDateTime since);

    @Query("SELECT COALESCE(SUM(u.promptTokens), 0) FROM UsageLog u WHERE u.tokenId IN :tokenIds")
    long sumPromptTokensByTokenIdIn(List<Long> tokenIds);

    @Query("SELECT COALESCE(SUM(u.completionTokens), 0) FROM UsageLog u WHERE u.tokenId IN :tokenIds")
    long sumCompletionTokensByTokenIdIn(List<Long> tokenIds);

    @Query("SELECT COALESCE(SUM(u.promptTokens), 0) FROM UsageLog u WHERE u.tokenId IN :tokenIds AND u.createdAt >= :since")
    long sumPromptTokensByTokenIdInSince(List<Long> tokenIds, LocalDateTime since);

    @Query("SELECT COALESCE(SUM(u.completionTokens), 0) FROM UsageLog u WHERE u.tokenId IN :tokenIds AND u.createdAt >= :since")
    long sumCompletionTokensByTokenIdInSince(List<Long> tokenIds, LocalDateTime since);

    @Query("SELECT COALESCE(SUM(u.creditCost), 0.0) FROM UsageLog u")
    double sumCreditCost();

    @Query("SELECT COALESCE(SUM(u.creditCost), 0.0) FROM UsageLog u WHERE u.createdAt >= :since")
    double sumCreditCostSince(LocalDateTime since);

    @Query("SELECT COALESCE(SUM(u.creditCost), 0.0) FROM UsageLog u WHERE u.tokenId IN :tokenIds")
    double sumCreditCostByTokenIdIn(List<Long> tokenIds);

    @Query("SELECT COALESCE(SUM(u.creditCost), 0.0) FROM UsageLog u WHERE u.tokenId IN :tokenIds AND u.createdAt >= :since")
    double sumCreditCostByTokenIdInSince(List<Long> tokenIds, LocalDateTime since);

    @Query(value = "SELECT DATE(created_at / 1000, 'unixepoch') as date, COALESCE(SUM(credit_cost), 0) as credits " +
            "FROM usage_logs WHERE token_id = ?1 AND created_at >= ?2 " +
            "GROUP BY DATE(created_at / 1000, 'unixepoch') ORDER BY date ASC", nativeQuery = true)
    List<Object[]> findDailyCreditCostByTokenIdSince(Long tokenId, LocalDateTime since);

    // Dashboard 聚合查询：一次查询获取所有指标
    @Query("SELECT COALESCE(COUNT(u), 0), COALESCE(SUM(u.totalTokens), 0), " +
           "COALESCE(SUM(u.promptTokens), 0), COALESCE(SUM(u.completionTokens), 0), COALESCE(SUM(u.creditCost), 0.0) " +
           "FROM UsageLog u WHERE u.tokenId IN :tokenIds")
    List<Object[]> sumAllMetricsByTokenIds(@Param("tokenIds") List<Long> tokenIds);

    @Query("SELECT COALESCE(COUNT(u), 0), COALESCE(SUM(u.totalTokens), 0), " +
           "COALESCE(SUM(u.promptTokens), 0), COALESCE(SUM(u.completionTokens), 0), COALESCE(SUM(u.creditCost), 0.0) " +
           "FROM UsageLog u WHERE u.tokenId IN :tokenIds AND u.createdAt >= :since")
    List<Object[]> sumAllMetricsByTokenIdsSince(@Param("tokenIds") List<Long> tokenIds, @Param("since") LocalDateTime since);
}
