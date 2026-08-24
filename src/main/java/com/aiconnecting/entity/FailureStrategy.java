package com.aiconnecting.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "failure_strategies")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FailureStrategy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String scope;

    @Column
    private Long channelId;

    @Column
    private Long modelConfigId;

    @Column(nullable = false, length = 200)
    private String httpCodes;

    @Column(length = 200)
    private String excludedHttpCodes;

    @Column(nullable = false, length = 20)
    private String windowType;

    @Column(nullable = false, length = 20)
    private String windowDimension;

    @Column(nullable = false)
    private Integer failureThreshold;

    @Column(nullable = false)
    private Integer fuseDurationSeconds;

    @Column(nullable = false)
    private Integer priority;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (enabled == null) enabled = true;
        if (windowType == null || windowType.isBlank()) windowType = "SLIDING";
        if (priority == null) priority = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
