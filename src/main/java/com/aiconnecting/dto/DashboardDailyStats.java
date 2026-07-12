package com.aiconnecting.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class DashboardDailyStats {
    private List<DailyCreditStat> dailyCredits;
    private List<DailyTokenByModelStat> dailyTokensByModel;

    @Data
    @Builder
    public static class DailyCreditStat {
        private String date;
        private BigDecimal credits;
    }

    @Data
    @Builder
    public static class DailyTokenByModelStat {
        private String date;
        private String model;
        private long inputTokens;
        private long cachedTokens;
        private long cacheMissTokens;
        private long totalTokens;
    }
}
