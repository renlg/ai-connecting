package com.aiconnecting.service;

import com.aiconnecting.entity.UsageLog;
import com.aiconnecting.repository.UsageLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UsageLogService {

    private final UsageLogRepository usageLogRepository;

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
}
