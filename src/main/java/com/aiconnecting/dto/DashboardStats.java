package com.aiconnecting.dto;

import lombok.*;
import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
public class DashboardStats {
    private Long totalChannels;
    private Long activeChannels;
    private Long blockedChannels;
    private Long totalTokens;
    private Long totalUsers;
    private Long totalRequests;
    private Long totalTokensUsed;
    private Long requestsToday;
    private Long tokensUsedToday;
    private Long totalInputTokens;
    private Long totalOutputTokens;
    private Long inputTokensToday;
    private Long outputTokensToday;
    private BigDecimal totalCreditsConsumed;
    private BigDecimal creditsConsumedToday;
    /** 今日消耗积分按模型类型（text/image/video/audio）拆分 */
    private Map<String, BigDecimal> creditsByTypeToday;
    private Long totalCachedPromptTokens;
    private Long cachedPromptTokensToday;
    private Long totalCacheCreationTokens;
    private Long totalCacheReadTokens;
    private Long todayCacheCreationTokens;
    private Long todayCacheReadTokens;
    private BigDecimal myCredits;
}
