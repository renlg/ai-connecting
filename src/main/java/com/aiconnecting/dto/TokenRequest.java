package com.aiconnecting.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class TokenRequest {
    private String name;
    private Long quota;
    private Double credits;
    private LocalDateTime expiredAt;
    private String allowedModels;
    private Integer rateLimit;
}
