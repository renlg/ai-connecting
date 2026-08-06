package com.aiconnecting.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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
        /** 当日消耗积分按模型类型（text/image/video/audio）拆分 */
        private Map<String, BigDecimal> creditsByType;
    }

    @Data
    @Builder
    public static class DailyTokenByModelStat {
        private String date;
        private String model;
        /** 展示名称，取自 model_configs.display_name；无匹配时回退为 model 原始名称 */
        private String displayName;
        private long inputTokens;
        private long cachedTokens;
        private long cacheMissTokens;
        private long totalTokens;
    }
}
