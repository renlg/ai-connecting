package com.aiconnecting.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class LevelRequest {
    @NotNull(message = "等级不能为空")
    @Min(value = 1, message = "等级最小为 1")
    @Max(value = 5, message = "等级最大为 5")
    private Integer level;
}
