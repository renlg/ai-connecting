package com.aiconnecting.entity;

import com.aiconnecting.config.ApiKeyCryptoConverter;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 渠道 - 对应一个 AI 提供商的 API Key
 */
@Entity
@Table(name = "channels")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Channel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 渠道名称 */
    @Column(nullable = false, length = 100)
    private String name;

    /** 渠道类型: openai, azure, claude 等 */
    @Column(nullable = false, length = 50)
    private String type;

    /** API Base URL, 例如 https://api.openai.com */
    @Column(nullable = false, length = 500)
    private String baseUrl;

    /** API Key */
    @Convert(converter = ApiKeyCryptoConverter.class)
    @Column(nullable = false, length = 1000)
    private String apiKey;

    /** 支持的模型ID列表, 逗号分隔 */
    @Column(length = 2000)
    private String modelIds;

    /** 支持的用户等级列表, 逗号分隔, 例如 "1,2,3" */
    @Column(length = 50)
    private String supportedLevels;

    /** 状态: 1=启用, 0=禁用 */
    @Column(nullable = false)
    private Integer status;

    /** 优先级, 数值越低优先级越高, 同优先级组内轮询分发, 低优先级仅在高优先级组不可用时启用 */
    @Column(nullable = false)
    private Integer priority;

    /** 已用 token 数 */
    @Column(nullable = false)
    private Long usedQuota;

    /** 速率限制 (每分钟请求数, 0=不限) */
    @Column(nullable = false)
    private Integer rateLimit;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = 1;
        if (priority == null) priority = 0;
        if (usedQuota == null) usedQuota = 0L;
        if (rateLimit == null) rateLimit = 0;
        if (supportedLevels == null || supportedLevels.isBlank()) supportedLevels = "1,2,3,4,5";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 重写 toString，隐藏 apiKey 防止日志泄露
     */
    @Override
    public String toString() {
        return "Channel(id=" + id + ", name=" + name + ", type=" + type
                + ", baseUrl=" + baseUrl + ", apiKey=***" + ")";
    }
}
