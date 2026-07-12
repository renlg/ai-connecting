package com.aiconnecting.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型配置
 */
@Entity
@Table(name = "model_configs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModelConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 模型名称, 例如 gpt-4, gpt-3.5-turbo */
    @Column(nullable = false, length = 100)
    private String name;

    /** 显示名称 (用于 Token 管理展示) */
    @Column(length = 100)
    private String displayName;

    /** 输入积分兑换比例 (每1000 token 消耗多少积分) */
    @Column(nullable = false)
    private Integer inputCreditRate;

    /** 输出积分兑换比例 (每1000 token 消耗多少积分) */
    @Column(nullable = false)
    private Integer outputCreditRate;

    /** 模型描述 */
    @Column(length = 500)
    private String description;

    /** 状态: 1=启用, 0=禁用 */
    @Column(nullable = false)
    private Integer status;

    /** 仅管理员可选 */
    @Builder.Default
    @Column(nullable = false)
    private Boolean adminOnly = false;

    /** 缓存 token 折扣比例，缓存 token 按此比例计入积分计算，默认 0.1（即 1/10） */
    @Builder.Default
    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal cacheCreditRate = new BigDecimal("0.1");

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = 1;
        if (inputCreditRate == null) inputCreditRate = 0;
        if (outputCreditRate == null) outputCreditRate = 0;
        if (adminOnly == null) adminOnly = false;
        if (cacheCreditRate == null) cacheCreditRate = new BigDecimal("0.1");
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
