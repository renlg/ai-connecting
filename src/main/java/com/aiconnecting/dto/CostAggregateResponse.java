package com.aiconnecting.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 成本聚合分页响应。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CostAggregateResponse {
    private List<CostAggregateRow> content;
    private long totalElements;
    private int page;
    private int size;
    private CostSummary summary;
}
