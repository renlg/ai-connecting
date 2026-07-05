package com.aiconnecting.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreditsRequest {
    @NotNull(message = "积分不能为空")
    private Double credits;
}
