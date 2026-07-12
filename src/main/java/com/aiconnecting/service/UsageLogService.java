package com.aiconnecting.service;

import com.aiconnecting.dto.DashboardDailyStats;
import com.aiconnecting.entity.UsageLog;
import com.aiconnecting.entity.User;
import com.aiconnecting.repository.UsageLogRepository;
import com.aiconnecting.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class UsageLogService {

    private final UsageLogRepository usageLogRepository;
    private final ChannelService channelService;
    private final ModelConfigService modelConfigService;
    private final UserRepository userRepository;
    private final TokenService tokenService;

    /** 积分计算除数：每百万 token */
    private static final BigDecimal CREDIT_RATE_DIVISOR = new BigDecimal("1000000");

    /** 模型积分比例缓存，避免每次请求查库，缓存 2 分钟 */
    private final ConcurrentHashMap<String, CachedCreditRate> creditRateCache = new ConcurrentHashMap<>();
    private static final long CREDIT_RATE_CACHE_TTL_MS = 2 * 60 * 1000L;

    private record CachedCreditRate(int inputRate, int outputRate, BigDecimal cacheCreditRate, long cachedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > CREDIT_RATE_CACHE_TTL_MS;
        }
    }

    public Long getTotalTokensSince(LocalDateTime since) {
        Long result = usageLogRepository.sumTotalTokensSince(since);
        return result != null ? result : 0L;
    }

    public Long getTotalRequests() {
        return usageLogRepository.count();
    }

    public Long getRequestsToday() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        return usageLogRepository.countRequestsSince(startOfDay);
    }

    public Long getTokensUsedToday() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        return getTotalTokensSince(startOfDay);
    }

    public long getTotalInputTokens() {
        return usageLogRepository.sumPromptTokens();
    }

    public long getTotalOutputTokens() {
        return usageLogRepository.sumCompletionTokens();
    }

    public long getInputTokensToday() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        return usageLogRepository.sumPromptTokensSince(startOfDay);
    }

    public long getOutputTokensToday() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        return usageLogRepository.sumCompletionTokensSince(startOfDay);
    }

    public long getTotalCachedPromptTokens() {
        return usageLogRepository.sumCachedPromptTokens();
    }

    public long getCachedPromptTokensToday() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        return usageLogRepository.sumCachedPromptTokensSince(startOfDay);
    }

    public BigDecimal getTotalCreditsConsumed() {
        return BigDecimal.valueOf(usageLogRepository.sumCreditCost());
    }

    public BigDecimal getCreditsConsumedToday() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        return BigDecimal.valueOf(usageLogRepository.sumCreditCostSince(startOfDay));
    }

    /**
     * 事务性记录使用日志并更新额度（由 RelayService 调用，确保 @Transactional 生效）
     */
    @Transactional
    public void recordUsageAndQuotas(UsageLog usageLog, Long tokenId, Long channelId, int totalTokens, Long userId) {
        usageLogRepository.save(usageLog);
        // 原子更新 token 额度
        if (totalTokens > 0) {
            channelService.addUsedQuota(channelId, totalTokens);
        }
        // 扣减用户积分
        if (usageLog.getCreditCost() != null && usageLog.getCreditCost().compareTo(BigDecimal.ZERO) > 0 && userId != null) {
            userRepository.deductCredits(userId, usageLog.getCreditCost());
        }
        // 更新 token 已用额度
        tokenService.addUsedQuota(tokenId, totalTokens);
    }

    /**
     * 计算积分消耗（含缓存折扣）
     */
    public BigDecimal calculateCreditCost(String model, int promptTokens, int completionTokens, int cachedTokens) {
        if (promptTokens == 0 && completionTokens == 0) return BigDecimal.ZERO;

        CachedCreditRate cached = creditRateCache.get(model);
        int inputRate, outputRate;
        BigDecimal cacheCreditRate;
        if (cached != null && !cached.isExpired()) {
            inputRate = cached.inputRate();
            outputRate = cached.outputRate();
            cacheCreditRate = cached.cacheCreditRate();
        } else {
            List<com.aiconnecting.entity.ModelConfig> models = modelConfigService.findByName(model);
            com.aiconnecting.entity.ModelConfig modelConfig = models.isEmpty() ? null : models.get(0);
            if (modelConfig == null) return BigDecimal.ZERO;
            inputRate = modelConfig.getInputCreditRate();
            outputRate = modelConfig.getOutputCreditRate();
            cacheCreditRate = modelConfig.getCacheCreditRate();
            creditRateCache.put(model, new CachedCreditRate(inputRate, outputRate, cacheCreditRate, System.currentTimeMillis()));
        }

        int clampedCachedTokens = Math.min(cachedTokens, promptTokens);
        int effectivePromptTokens = promptTokens - clampedCachedTokens;
        BigDecimal inputCost = BigDecimal.valueOf(effectivePromptTokens).divide(CREDIT_RATE_DIVISOR, 10, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(inputRate));
        BigDecimal cacheCost = BigDecimal.valueOf(clampedCachedTokens).divide(CREDIT_RATE_DIVISOR, 10, RoundingMode.HALF_UP).multiply(cacheCreditRate);
        BigDecimal outputCost = BigDecimal.valueOf(completionTokens).divide(CREDIT_RATE_DIVISOR, 10, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(outputRate));
        return inputCost.add(cacheCost).add(outputCost);
    }

    /**
     * 分页查询使用日志
     */
    public Page<UsageLog> getLogs(int page, int size) {
        return usageLogRepository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    /**
     * 按 Token ID 列表聚合查询全部指标
     */
    public Object[] sumAllMetricsByTokenIds(List<Long> tokenIds) {
        List<Object[]> result = usageLogRepository.sumAllMetricsByTokenIds(tokenIds);
        return result.isEmpty() ? new Object[]{0L, 0L, 0L, 0L, 0.0, 0L} : result.get(0);
    }

    /**
     * 按 Token ID 列表聚合查询今日指标
     */
    public Object[] sumAllMetricsByTokenIdsSince(List<Long> tokenIds, LocalDateTime since) {
        List<Object[]> result = usageLogRepository.sumAllMetricsByTokenIdsSince(tokenIds, since);
        return result.isEmpty() ? new Object[]{0L, 0L, 0L, 0L, 0.0, 0L} : result.get(0);
    }

    /**
     * 查询指定 Token 的每日消耗积分
     */
    public List<Object[]> getDailyCreditCost(Long tokenId, LocalDateTime since) {
        return usageLogRepository.findDailyCreditCostByTokenIdSince(tokenId, since);
    }

    /**
     * 按 Token ID 列表查询缓存创建/读取 token 统计（全部时间）
     */
    public long[] getCacheStats(List<Long> tokenIds) {
        List<Object[]> result = usageLogRepository.sumCacheTokensByTokenIds(tokenIds);
        if (result.isEmpty()) return new long[]{0, 0};
        Object[] row = result.get(0);
        return new long[]{((Number) row[0]).longValue(), ((Number) row[1]).longValue()};
    }

    /**
     * 按 Token ID 列表查询缓存创建/读取 token 统计（指定时间起）
     */
    public long[] getCacheStatsSince(List<Long> tokenIds, LocalDateTime since) {
        List<Object[]> result = usageLogRepository.sumCacheTokensByTokenIdsSince(tokenIds, since);
        if (result.isEmpty()) return new long[]{0, 0};
        Object[] row = result.get(0);
        return new long[]{((Number) row[0]).longValue(), ((Number) row[1]).longValue()};
    }

    /**
     * 全局缓存创建/读取 token 统计（全部时间）
     */
    public long[] getGlobalCacheStats() {
        List<Object[]> result = usageLogRepository.sumCacheTokensGlobal();
        if (result.isEmpty()) return new long[]{0, 0};
        Object[] row = result.get(0);
        return new long[]{((Number) row[0]).longValue(), ((Number) row[1]).longValue()};
    }

    /**
     * 全局缓存创建/读取 token 统计（指定时间起）
     */
    public long[] getGlobalCacheStatsSince(LocalDateTime since) {
        List<Object[]> result = usageLogRepository.sumCacheTokensGlobalSince(since);
        if (result.isEmpty()) return new long[]{0, 0};
        Object[] row = result.get(0);
        return new long[]{((Number) row[0]).longValue(), ((Number) row[1]).longValue()};
    }

    /**
     * 仪表盘每日统计 - admin 看全局，普通用户看自己的数据
     */
    public DashboardDailyStats getDailyStats(User currentUser, int days) {
        LocalDateTime since = LocalDate.now().minusDays(days).atStartOfDay();
        List<Long> tokenIds = null;
        if (!"admin".equalsIgnoreCase(currentUser.getRole())) {
            tokenIds = tokenService.getUserTokenIds(currentUser.getId());
            if (tokenIds.isEmpty()) {
                return DashboardDailyStats.builder()
                        .dailyCredits(List.of())
                        .dailyTokensByModel(List.of())
                        .build();
            }
        }

        List<Object[]> creditRows = tokenIds == null
                ? usageLogRepository.findDailyCreditCostSince(since)
                : usageLogRepository.findDailyCreditCostByTokenIdsSince(tokenIds, since);
        List<Object[]> tokenRows = tokenIds == null
                ? usageLogRepository.findDailyTokenByModelSince(since)
                : usageLogRepository.findDailyTokenByModelByTokenIdsSince(tokenIds, since);

        List<DashboardDailyStats.DailyCreditStat> dailyCredits = creditRows.stream()
                .map(row -> DashboardDailyStats.DailyCreditStat.builder()
                        .date((String) row[0])
                        .credits(BigDecimal.valueOf(((Number) row[1]).doubleValue()))
                        .build())
                .toList();

        List<DashboardDailyStats.DailyTokenByModelStat> dailyTokensByModel = tokenRows.stream()
                .map(row -> {
                    long inputTokens = ((Number) row[2]).longValue();
                    long cachedTokens = ((Number) row[3]).longValue();
                    long totalTokens = ((Number) row[4]).longValue();
                    return DashboardDailyStats.DailyTokenByModelStat.builder()
                            .date((String) row[0])
                            .model((String) row[1])
                            .inputTokens(inputTokens)
                            .cachedTokens(cachedTokens)
                            .cacheMissTokens(inputTokens - cachedTokens)
                            .totalTokens(totalTokens)
                            .build();
                })
                .toList();

        return DashboardDailyStats.builder()
                .dailyCredits(dailyCredits)
                .dailyTokensByModel(dailyTokensByModel)
                .build();
    }
}
