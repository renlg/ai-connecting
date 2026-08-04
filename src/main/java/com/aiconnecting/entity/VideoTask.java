package com.aiconnecting.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 视频生成任务 - 记录上游任务 id 与渠道的映射，供中转轮询接口回查原始渠道
 */
@Entity
@Table(name = "video_tasks", indexes = @Index(name = "idx_video_tasks_upstream_id", columnList = "upstreamId"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VideoTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 上游返回的任务 id */
    @Column(nullable = false, length = 200)
    private String upstreamId;

    /** 处理该任务的渠道 id */
    @Column(nullable = false)
    private Long channelId;

    /** 发起任务的用户 id */
    @Column(nullable = false)
    private Long userId;

    /** 模型名称 */
    @Column(length = 100)
    private String model;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
