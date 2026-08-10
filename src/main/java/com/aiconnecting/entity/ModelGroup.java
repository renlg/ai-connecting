package com.aiconnecting.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型组：面向客户端的公开别名，绑定同类型的多个成员模型，按策略选择并在上游失败时自动切换成员。
 * 计费使用模型组自身的价格（而非成员模型价格），与 {@link ModelConfig} 的计费字段结构镜像。
 */
@Entity
@Table(name = "model_groups")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModelGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 模型组公开名称（客户端调用的 model 值），唯一 */
    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /** 模型类型: text/image/video/audio，成员必须与组类型一致 */
    @Column(nullable = false, length = 20)
    private String type;

    /** 成员选择策略: round_robin/priority/random */
    @Builder.Default
    @Column(nullable = false, length = 20)
    private String strategy = "round_robin";

    /** 单次请求最多尝试的成员数（含首次），硬上限 8 */
    @Builder.Default
    @Column(nullable = false)
    private Integer maxAttempts = 5;

    /** 是否启用 */
    @Builder.Default
    @Column(nullable = false)
    private Boolean enabled = true;

    /** 仅管理员可见/可用 */
    @Builder.Default
    @Column(nullable = false)
    private Boolean adminOnly = false;

    // ==================== 文本按 token 计费（每 1K token，独立于 model_configs 的每百万 token 积分比例公式） ====================

    @Column(name = "input_price", precision = 10, scale = 4)
    private BigDecimal inputPrice;

    @Column(name = "output_price", precision = 10, scale = 4)
    private BigDecimal outputPrice;

    @Column(name = "cached_price", precision = 10, scale = 4)
    private BigDecimal cachedPrice;

    // ==================== 图片按分辨率档位计费 (积分/张) ====================

    @Column(name = "price_1k", precision = 10, scale = 2)
    private BigDecimal price1k;

    @Column(name = "price_2k", precision = 10, scale = 2)
    private BigDecimal price2k;

    @Column(name = "price_4k", precision = 10, scale = 2)
    private BigDecimal price4k;

    // ==================== 视频按分辨率档位计费 (积分/秒) ====================

    @Column(name = "price_480p", precision = 10, scale = 2)
    private BigDecimal price480p;

    @Column(name = "price_720p", precision = 10, scale = 2)
    private BigDecimal price720p;

    @Column(name = "price_1080p", precision = 10, scale = 2)
    private BigDecimal price1080p;

    /** 视频 4K 档价格；与图片 4K 档（price_4k）为不同用途的独立字段 */
    @Column(name = "video_price_4k", precision = 10, scale = 2)
    private BigDecimal videoPrice4k;

    // ==================== 音频按音质档位计费 (积分/秒) ====================

    @Column(name = "price_standard", precision = 10, scale = 2)
    private BigDecimal priceStandard;

    @Column(name = "price_hd", precision = 10, scale = 2)
    private BigDecimal priceHd;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (strategy == null || strategy.isBlank()) strategy = "round_robin";
        if (maxAttempts == null) maxAttempts = 5;
        if (enabled == null) enabled = true;
        if (adminOnly == null) adminOnly = false;
    }
}
