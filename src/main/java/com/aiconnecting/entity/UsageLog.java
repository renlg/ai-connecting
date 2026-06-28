package com.aiconnecting.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 使用日志
 */
@Entity
@Table(name = "usage_logs")
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

    /** 请求路径 */
    @Column(length = 500)
    private String requestPath;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
