package com.aiconnecting.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 管理员操作审计日志
 */
@Entity
@Table(name = "operation_logs", indexes = {
        @Index(name = "idx_operation_logs_admin_id", columnList = "adminId"),
        @Index(name = "idx_operation_logs_created_at", columnList = "createdAt")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 执行操作的管理员用户 ID */
    @Column(nullable = false)
    private Long adminId;

    /** 操作类型，如 UPDATE_USER_STATUS / RESET_PASSWORD / UPDATE_CREDITS */
    @Column(nullable = false, length = 100)
    private String action;

    /** 操作目标，如 user:123 / coupon:5 / announcement:2 */
    @Column(length = 200)
    private String target;

    /** 操作详情（变更内容等） */
    @Column(length = 2000)
    private String detail;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
