package com.aiconnecting.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 积分券兑换记录
 */
@Entity
@Table(name = "coupon_redemption_logs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CouponRedemptionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户 ID */
    @Column(nullable = false)
    private Long userId;

    /** 兑换的积分券 ID */
    @Column(nullable = false)
    private Long couponId;

    /** 兑换时间 */
    @Column(nullable = false, updatable = false)
    private LocalDateTime redeemedAt;

    @PrePersist
    protected void onCreate() {
        redeemedAt = LocalDateTime.now();
    }
}
