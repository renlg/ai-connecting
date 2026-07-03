package com.aiconnecting.dto;

import lombok.*;

@Data
@Builder
public class DashboardStats {
    private Long totalChannels;
    private Long activeChannels;
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
    private Double totalCreditsConsumed;
    private Double creditsConsumedToday;
    private Double myCredits;
}
