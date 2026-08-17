package com.aiconnecting.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** 只读的渠道 × 实际请求模型成本聚合行。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CostAggregateRow {
    private Long channelId;
    private String channelName;
    /** 上游实际模型；actual_model 为空时回退为客户端请求模型。 */
    private String model;
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
