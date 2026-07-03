package com.aiconnecting.service;

import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.entity.UsageLog;
import com.aiconnecting.repository.ChannelRepository;
import com.aiconnecting.repository.ModelConfigRepository;
import com.aiconnecting.repository.UsageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class UsageLogService {

    private final UsageLogRepository usageLogRepository;
    private final ChannelRepository channelRepository;
    private final ModelConfigRepository modelConfigRepository;

    public UsageLog save(UsageLog log) {
        return usageLogRepository.save(log);
    }

    public List<UsageLog> getByToken(Long tokenId) {
        return usageLogRepository.findByTokenIdOrderByCreatedAtDesc(tokenId);
    }

    public List<UsageLog> getByChannel(Long channelId) {
        return usageLogRepository.findByChannelIdOrderByCreatedAtDesc(channelId);
    }

    public Long getTotalTokensSince(LocalDateTime since) {
        Long result = usageLogRepository.sumTotalTokensSince(since);
        return result != null ? result : 0L;
    }

    public Long getRequestsSince(LocalDateTime since) {
        return usageLogRepository.countRequestsSince(since);
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

    public double getTotalCreditsConsumed() {
        return usageLogRepository.sumCreditCost();
    }

    public double getCreditsConsumedToday() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        return usageLogRepository.sumCreditCostSince(startOfDay);
    }

    /**
     * 事务性记录使用日志并更新额度（由 RelayService 调用，确保 @Transactional 生效）
     */
    @Transactional
    public void recordUsageAndQuotas(UsageLog usageLog, Long tokenId, Long channelId, int totalTokens) {
        usageLogRepository.save(usageLog);
        // 原子更新 token 额度
        if (totalTokens > 0) {
            channelRepository.addUsedQuota(channelId, totalTokens);
        }
    }

    /**
     * 计算积分消耗
     */
    public double calculateCreditCost(String model, int promptTokens, int completionTokens) {
        if (promptTokens == 0 && completionTokens == 0) return 0.0;

        ModelConfig modelConfig = modelConfigRepository.findByName(model).orElse(null);
        if (modelConfig == null) return 0.0;

        double inputCost = (promptTokens / 1000.0) * modelConfig.getInputCreditRate();
        double outputCost = (completionTokens / 1000.0) * modelConfig.getOutputCreditRate();
        return inputCost + outputCost;
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
        return usageLogRepository.sumAllMetricsByTokenIds(tokenIds);
    }

    /**
     * 按 Token ID 列表聚合查询今日指标
     */
    public Object[] sumAllMetricsByTokenIdsSince(List<Long> tokenIds, LocalDateTime since) {
        return usageLogRepository.sumAllMetricsByTokenIdsSince(tokenIds, since);
    }
}
