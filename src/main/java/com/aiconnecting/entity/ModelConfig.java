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

    /** 模型类型: text=文本, image=图片, video=视频, audio=音频 */
    @Builder.Default
    @Column(name = "type", nullable = false, length = 20, columnDefinition = "VARCHAR(20) NOT NULL DEFAULT 'text'")
    private String type = "text";

    /** 输入积分兑换比例 (每百万token 消耗多少积分) */
    @Column(nullable = false)
    private Integer inputCreditRate;

    /** 输出积分兑换比例 (每百万token 消耗多少积分) */
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

    /** 缓存 token 积分兑换比例 (每百万token 消耗多少积分) */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal cacheCreditRate;

    // ==================== 图片模型按分辨率档位计费 (积分/张) ====================

    /** 图片 1K 档价格 (最长边 < 2048) */
    @Column(name = "image_price_1k", columnDefinition = "DECIMAL(10,2) NOT NULL DEFAULT 0")
    private BigDecimal imagePrice1k;

    /** 图片 2K 档价格 (最长边 < 4096) */
    @Column(name = "image_price_2k", columnDefinition = "DECIMAL(10,2) NOT NULL DEFAULT 0")
    private BigDecimal imagePrice2k;

    /** 图片 4K 档价格 (最长边 >= 4096) */
    @Column(name = "image_price_4k", columnDefinition = "DECIMAL(10,2) NOT NULL DEFAULT 0")
    private BigDecimal imagePrice4k;

    // ==================== 视频模型按分辨率档位计费 (积分/秒，计费 = 档位单价 × 时长秒数) ====================

    /** 视频 480P 档价格 */
    @Column(name = "video_price_480p", columnDefinition = "DECIMAL(10,2) NOT NULL DEFAULT 0")
    private BigDecimal videoPrice480p;

    /** 视频 720P 档价格 */
    @Column(name = "video_price_720p", columnDefinition = "DECIMAL(10,2) NOT NULL DEFAULT 0")
    private BigDecimal videoPrice720p;

    /** 视频 1080P 档价格 */
    @Column(name = "video_price_1080p", columnDefinition = "DECIMAL(10,2) NOT NULL DEFAULT 0")
    private BigDecimal videoPrice1080p;

    /** 视频 4K 档价格 */
    @Column(name = "video_price_4k", columnDefinition = "DECIMAL(10,2) NOT NULL DEFAULT 0")
    private BigDecimal videoPrice4k;

    // ==================== 音频模型按音质档位计费 (积分/秒，计费 = 档位单价 × 时长秒数) ====================

    /** 音频标准档价格 */
    @Column(name = "audio_price_standard", columnDefinition = "DECIMAL(10,2) NOT NULL DEFAULT 0")
    private BigDecimal audioPriceStandard;

    /** 音频高清档价格 */
    @Column(name = "audio_price_hd", columnDefinition = "DECIMAL(10,2) NOT NULL DEFAULT 0")
    private BigDecimal audioPriceHd;

    /** 可选的故障转移组：单模型请求优先使用自身模型，失败后转入该模型组的成员继续重试 */
    @Column(name = "fallback_group_id")
    private Long fallbackGroupId;

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
        if (cacheCreditRate == null) cacheCreditRate = BigDecimal.ZERO;
        if (type == null || type.isBlank()) type = "text";
        if (imagePrice1k == null) imagePrice1k = BigDecimal.ZERO;
        if (imagePrice2k == null) imagePrice2k = BigDecimal.ZERO;
        if (imagePrice4k == null) imagePrice4k = BigDecimal.ZERO;
        if (videoPrice480p == null) videoPrice480p = BigDecimal.ZERO;
        if (videoPrice720p == null) videoPrice720p = BigDecimal.ZERO;
        if (videoPrice1080p == null) videoPrice1080p = BigDecimal.ZERO;
        if (videoPrice4k == null) videoPrice4k = BigDecimal.ZERO;
        if (audioPriceStandard == null) audioPriceStandard = BigDecimal.ZERO;
        if (audioPriceHd == null) audioPriceHd = BigDecimal.ZERO;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
