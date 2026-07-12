package com.aiconnecting.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 模型配置请求 DTO
 */
@Data
public class ModelConfigRequest {

    private String name;

    private String displayName;

    private String description;

    private Integer inputCreditRate;

    private Integer outputCreditRate;

    private Boolean adminOnly;

    private Integer status;

    private BigDecimal cacheCreditRate;
}
