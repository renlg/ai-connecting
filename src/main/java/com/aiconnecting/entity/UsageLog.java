package com.aiconnecting.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 使用日志
 */
@Entity
@Table(name = "usage_logs", indexes = {
        @Index(name = "idx_usage_logs_token_id", columnList = "tokenId"),
        @Index(name = "idx_usage_logs_channel_id", columnList = "channelId"),
        @Index(name = "idx_usage_logs_created_at", columnList = "createdAt"),
        @Index(name = "idx_usage_logs_token_created", columnList = "tokenId, createdAt")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UsageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 使用的 Token ID */
    private Long tokenId;

    /** 使用的渠道 ID */
    private Long channelId;

    /** 使用的模型 */
    @Column(length = 100)
    private String model;

    /** prompt tokens */
    private Integer promptTokens;

    /** completion tokens */
    private Integer completionTokens;

    /** 总 tokens */
    private Integer totalTokens;

    /** 请求 IP */
    @Column(length = 50)
    private String ip;

    /** 耗时 (毫秒) */
    private Long duration;

    /** 本次请求消耗的积分 (支持小数) */
    @Builder.Default
    @Column(precision = 19, scale = 6)
    private BigDecimal creditCost = BigDecimal.ZERO;

    /** 请求路径 */
    @Column(length = 500)
    private String requestPath;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (creditCost == null) creditCost = BigDecimal.ZERO;
    }
}
