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

    /** 输入积分兑换比例 (每百万token 消耗多少积分) */
    private Integer inputCreditRate;

    /** 输出积分兑换比例 (每百万token 消耗多少积分) */
    private Integer outputCreditRate;

    private Boolean adminOnly;

    private Integer status;

    /** 缓存 token 积分兑换比例 (每百万token 消耗多少积分) */
    private BigDecimal cacheCreditRate;
}
