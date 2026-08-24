package com.aiconnecting.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class InviteCodeGenerateRequest {

    @Min(value = 1, message = "生成数量不能小于1")
    @Max(value = 100, message = "单次最多生成100个邀请码")
    private Integer count = 1;

    @NotNull(message = "使用次数不能为空")
    @Min(value = 1, message = "使用次数不能小于1")
    @Max(value = 1000000, message = "使用次数不能超过1000000")
    private Integer maxUses;

    private LocalDateTime expiryDate;
}
