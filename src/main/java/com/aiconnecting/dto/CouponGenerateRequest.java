package com.aiconnecting.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 积分券生成请求 DTO
 */
@Data
public class CouponGenerateRequest {

    @NotNull(message = "积分不能为空")
    private BigDecimal credits;

    private Integer maxUses;

    private LocalDateTime expiryDate;
}
