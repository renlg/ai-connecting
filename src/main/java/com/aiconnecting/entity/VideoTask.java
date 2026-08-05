package com.aiconnecting.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 视频生成任务 - 记录上游任务 id 与渠道的映射，供中转轮询接口回查原始渠道；
 * 同时记录预扣计费信息，供任务终态（failed/completed）时退款/结算使用
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

    /** 预扣积分金额 */
    private BigDecimal prepaidCost;

    /** 预扣时是否实际扣减了积分（admin 余额不足时放行但不扣减） */
    private boolean deducted;

    /** 请求时的分辨率/档位参数，供结算时重新计算实际费用 */
    @Column(length = 50)
    private String size;

    /** 请求时声明的时长（秒），预扣计费所用 */
    private Integer durationSeconds;

    /** 预扣时所用的档位单价（积分/秒），供结算时按提交时价格计算实际费用，避免管理员事后改价影响已提交任务 */
    @Column(precision = 10, scale = 4)
    private BigDecimal unitPrice;

    /** 创建任务时写入的预扣使用日志 id，供结算时回写该日志的最终计费金额 */
    private Long usageLogId;

    /** 任务是否已结算（退款或按实际用量结算），防止轮询接口重复退款/结算 */
    @Column(nullable = false)
    @Builder.Default
    private boolean settled = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
