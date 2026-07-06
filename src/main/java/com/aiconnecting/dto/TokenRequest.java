package com.aiconnecting.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class TokenRequest {
    private String name;
    private Long quota;
    private BigDecimal credits;
    private LocalDateTime expiredAt;
    private String allowedModels;
    private Integer rateLimit;
}
