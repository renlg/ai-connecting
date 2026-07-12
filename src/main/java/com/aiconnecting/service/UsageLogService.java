package com.aiconnecting.service;

import com.aiconnecting.entity.UsageLog;
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

    /** 积分计算除数：每千 token */
    private static final BigDecimal CREDIT_RATE_DIVISOR = new BigDecimal("1000");

    /** 模型积分比例缓存，避免每次请求查库，缓存 2 分钟 */
    private final ConcurrentHashMap<String, CachedCreditRate> creditRateCache = new ConcurrentHashMap<>();
    private static final long CREDIT_RATE_CACHE_TTL_MS = 2 * 60 * 1000L;

    private record CachedCreditRate(int inputRate, int outputRate, java.math.BigDecimal multiplier, long cachedAt) {
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
     * 计算积分消耗（含缓存折扣和倍率）
     */
    public BigDecimal calculateCreditCost(String model, int promptTokens, int completionTokens, int cachedTokens) {
        if (promptTokens == 0 && completionTokens == 0) return BigDecimal.ZERO;

        CachedCreditRate cached = creditRateCache.get(model);
        int inputRate, outputRate;
        BigDecimal multiplier;
        if (cached != null && !cached.isExpired()) {
            inputRate = cached.inputRate();
            outputRate = cached.outputRate();
            multiplier = cached.multiplier();
        } else {
            List<com.aiconnecting.entity.ModelConfig> models = modelConfigService.findByName(model);
            com.aiconnecting.entity.ModelConfig modelConfig = models.isEmpty() ? null : models.get(0);
            if (modelConfig == null) return BigDecimal.ZERO;
            inputRate = modelConfig.getInputCreditRate();
            outputRate = modelConfig.getOutputCreditRate();
            multiplier = modelConfig.getMultiplier() != null ? modelConfig.getMultiplier() : BigDecimal.ONE;
            creditRateCache.put(model, new CachedCreditRate(inputRate, outputRate, multiplier, System.currentTimeMillis()));
        }

        int clampedCachedTokens = Math.min(cachedTokens, promptTokens);
        int effectivePromptTokens = promptTokens - clampedCachedTokens;
        BigDecimal cachedDiscount = BigDecimal.valueOf(clampedCachedTokens).divide(BigDecimal.TEN, 10, RoundingMode.HALF_UP);
        BigDecimal adjustedPromptTokens = BigDecimal.valueOf(effectivePromptTokens).add(cachedDiscount);
        BigDecimal inputCost = adjustedPromptTokens.divide(CREDIT_RATE_DIVISOR, 10, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(inputRate));
        BigDecimal outputCost = BigDecimal.valueOf(completionTokens).divide(CREDIT_RATE_DIVISOR, 10, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(outputRate));
        return inputCost.add(outputCost).multiply(multiplier);
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
}
