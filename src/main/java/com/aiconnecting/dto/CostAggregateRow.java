package com.aiconnecting.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** 只读的渠道 × 客户端模型成本聚合行。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CostAggregateRow {
    private Long channelId;
    private String channelName;
    private String model;
    /** 同一模型组可能路由到多个实际上游模型，此时以逗号分隔。 */
    private String actualModel;
    private long totalPromptTokens;
    private long totalCompletionTokens;
    private long totalCacheCreation;
    private long totalCacheRead;
    private long requestCount;
    private long imageCount;
    private long videoSeconds;
    /** text/image/video；媒体类型以 request_path 为准。 */
    private String modelType;
    private BigDecimal totalCreditCost;
}
