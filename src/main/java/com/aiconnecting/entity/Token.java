package com.aiconnecting.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Token - 用户用来访问中转站的 API Key
 */
@Entity
@Table(name = "tokens")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Token {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 显示名称 */
    @Column(nullable = false, length = 100)
    private String name;

    /** Token Key (sk-xxx) */
    @Column(unique = true, nullable = false, length = 100)
    private String tokenKey;

    /** 所属用户 ID */
    @Column(nullable = false)
    private Long userId;

    /** 可用额度 (-1=无限) */
    @Column(nullable = false)
    private Long quota;

    /** 已用额度 */
    @Column(nullable = false)
    private Long usedQuota;

    /** Token 积分 (-1=无限，与用户积分独立) */
    @Column(nullable = false)
    private Double credits;

    /** 过期时间 (null=永不过期) */
    private LocalDateTime expiredAt;

    /** 状态: 1=启用, 0=禁用 */
    @Column(nullable = false)
    private Integer status;

    /** 允许使用的模型, 逗号分隔 (空=全部) */
    @Column(length = 2000)
    private String allowedModels;

    /** 速率限制 (每分钟请求数, 0=不限) */
    @Column(columnDefinition = "integer default 0")
    private Integer rateLimit;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 所属用户名 (非数据库字段，用于前端展示) */
    @Transient
    private String ownerName;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (quota == null) quota = -1L;
        if (usedQuota == null) usedQuota = 0L;
        if (credits == null) credits = -1.0;
        if (status == null) status = 1;
        if (rateLimit == null) rateLimit = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
