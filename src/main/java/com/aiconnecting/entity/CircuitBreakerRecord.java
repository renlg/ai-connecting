package com.aiconnecting.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "circuit_breaker_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CircuitBreakerRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column
    private Long policyId;

    @Column(nullable = false)
    private Long channelId;

    @Column(length = 100)
    private String modelConfigName;

    @Column(nullable = false, length = 20)
    private String source;

    @Column(length = 500)
    private String reason;

    @Column(nullable = false)
    private LocalDateTime triggeredAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
