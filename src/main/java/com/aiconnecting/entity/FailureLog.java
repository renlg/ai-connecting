package com.aiconnecting.entity;

import jakarta.persistence.*;
import lombok.*;

/** 终端用户中转请求失败日志。 */
@Entity
@Table(name = "failure_logs", indexes = {
        @Index(name = "idx_failure_logs_trace_id", columnList = "traceId"),
        @Index(name = "idx_failure_logs_created_at", columnList = "createdAt")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FailureLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String traceId;

    @Column(nullable = false, length = 2000)
    private String userError;

    @Column(length = 2000)
    private String channelError;

    @Column(nullable = false)
    private Integer httpStatus;

    @Column(length = 100)
    private String modelName;

    @Column(length = 100)
    private String channelModelName;

    @Column(length = 20)
    private String protocol;

    /** Epoch milliseconds. */
    @Column(nullable = false, updatable = false)
    private Long createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = System.currentTimeMillis();
    }
}
