package com.aiconnecting.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "invite_codes")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InviteCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 16)
    private String code;

    @Column(nullable = false)
    private Integer maxUses;

    @Builder.Default
    @Column(nullable = false)
    private Integer usedCount = 0;

    private LocalDateTime expiryDate;

    @Column(nullable = false)
    private Long createdBy;

    /** 状态: 1=启用, 0=禁用 */
    @Builder.Default
    @Column(nullable = false)
    private Integer status = 1;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (usedCount == null) usedCount = 0;
        if (status == null) status = 1;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
