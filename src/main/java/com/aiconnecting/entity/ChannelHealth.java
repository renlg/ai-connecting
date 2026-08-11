package com.aiconnecting.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 渠道健康看板持久化数据（每渠道一行），用于重启后恢复最近成功/失败信息展示。
 * 实时字段（当前权重、1 分钟错误率、熔断器状态等）不落库，仍由 ChannelHealthTracker 纯内存维护。
 */
@Entity
@Table(name = "channel_health", uniqueConstraints = @UniqueConstraint(name = "uk_channel_health_channel_id", columnNames = "channel_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChannelHealth {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "last_success_at")
    private Long lastSuccessAt;

    @Column(name = "last_failure_at")
    private Long lastFailureAt;

    @Column(name = "last_failure_reason", length = 1000)
    private String lastFailureReason;

    @Builder.Default
    @Column(name = "probe_failures", nullable = false)
    private Integer probeFailures = 0;

    @Column(name = "updated_at")
    private Long updatedAt;

    @Column(name = "created_at")
    private Long createdAt;
}
