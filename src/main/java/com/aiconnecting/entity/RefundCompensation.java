package com.aiconnecting.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预扣积分退款失败的补偿记录：上游请求失败后退款本身又失败（如数据库瞬断）时落库，
 * 供管理员对账并人工补回积分，避免"用户被扣积分 + 收到错误响应 + 账面无痕迹"
 */
@Entity
@Table(name = "refund_compensations", indexes = {
        @Index(name = "idx_refund_compensations_user_id", columnList = "userId"),
        @Index(name = "idx_refund_compensations_created_at", columnList = "createdAt")})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefundCompensation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 需补回积分的用户 ID */
    @Column(nullable = false)
    private Long userId;

    /** 应退未退的积分数额 */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** 触发退款的业务场景（如 media/tts/transcription）与失败原因摘要 */
    @Column(nullable = false, length = 500)
    private String reason;

    /** 是否已人工补偿完成 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean resolved = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
