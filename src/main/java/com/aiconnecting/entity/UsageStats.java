package com.aiconnecting.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 使用日志汇总表 —— 每 15 分钟聚合一次 usage_logs 数据
 * 仪表盘查询从此表读取，避免对 usage_logs 的全表扫描
 */
@Entity
@Table(name = "usage_stats", indexes = {
        @Index(name = "idx_usage_stats_start_time", columnList = "startTime"),
        @Index(name = "idx_usage_stats_date", columnList = "date")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UsageStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 聚合时间段的开始 */
    @Column(nullable = false)
    private LocalDateTime startTime;

    /** 聚合时间段的结束 */
    @Column(nullable = false)
    private LocalDateTime endTime;

    /** 日期（冗余，方便按天查询），格式 yyyy-MM-dd */
    @Column(nullable = false, length = 10)
    private String date;

    /** 请求总数 */
    @Column(nullable = false)
    @Builder.Default
    private Long totalRequests = 0L;

    /** 总 token 数 */
    @Column(nullable = false)
    @Builder.Default
    private Long totalTokens = 0L;

    /** prompt token 总数 */
    @Column(nullable = false)
    @Builder.Default
    private Long totalPromptTokens = 0L;

    /** completion token 总数 */
    @Column(nullable = false)
    @Builder.Default
    private Long totalCompletionTokens = 0L;

    /** 消耗积分总和 */
    @Column(precision = 19, scale = 6, nullable = false)
    @Builder.Default
    private BigDecimal totalCreditCost = BigDecimal.ZERO;

    /** 缓存的 prompt token 命中数 */
    @Column(nullable = false)
    @Builder.Default
    private Long totalCachedPromptTokens = 0L;

    /** cache creation token 数 */
    @Column(nullable = false)
    @Builder.Default
    private Long totalCacheCreationTokens = 0L;

    /** cache read token 数 */
    @Column(nullable = false)
    @Builder.Default
    private Long totalCacheReadTokens = 0L;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
