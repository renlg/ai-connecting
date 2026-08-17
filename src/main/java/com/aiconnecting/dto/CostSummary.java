package com.aiconnecting.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** 当前成本筛选条件下的全量合计（不受分页影响）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CostSummary {
    private long totalPromptTokens;
    private long totalCompletionTokens;
    private long totalCacheCreation;
    private long totalCacheRead;
    private long requestCount;
    private long imageCount;
    private long videoSeconds;
    private BigDecimal totalCreditCost;
}
