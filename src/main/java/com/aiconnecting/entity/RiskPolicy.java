package com.aiconnecting.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "risk_policies")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RiskPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long channelId;

    @Column(length = 100)
    private String modelConfigName;

    @Column(nullable = false)
    private Integer rateLimit;

    @Column(nullable = false, length = 20)
    private String timeWindow;

    @Column(nullable = false, length = 20)
    private String windowType;

    @Column(nullable = false)
    private Integer circuitBreakerDuration;

    @Column(nullable = false)
    private Integer status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = 1;
        if (circuitBreakerDuration == null) circuitBreakerDuration = 300;
        if (windowType == null || windowType.isBlank()) windowType = "SLIDING";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
