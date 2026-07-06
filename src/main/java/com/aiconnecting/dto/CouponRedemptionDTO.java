package com.aiconnecting.dto;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponRedemptionDTO {
    private Long userId;
    private String username;
    private String nickname;
    private LocalDateTime redeemedAt;
    private BigDecimal credits;
}
