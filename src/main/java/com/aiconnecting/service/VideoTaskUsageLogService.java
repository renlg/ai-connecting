package com.aiconnecting.service;

import com.aiconnecting.entity.UsageLog;
import com.aiconnecting.entity.VideoTask;
import com.aiconnecting.repository.VideoTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 视频任务预扣使用日志的原子落库、回链与缺失恢复。 */
@Service
@RequiredArgsConstructor
public class VideoTaskUsageLogService {

    private final VideoTaskRepository videoTaskRepository;
    private final UsageLogService usageLogService;

    /** 日志创建与任务回链位于同一事务；回链失败时回滚日志，不留孤立记录。 */
    @Transactional
    public Long recordAndLink(Long taskId, UsageLog usageLog) {
        Long usageLogId = usageLogService.recordPrepaidUsage(usageLog).getId();
        if (videoTaskRepository.linkUsageLog(taskId, usageLogId) != 1) {
            throw new IllegalStateException("视频任务使用日志关联失败: taskId=" + taskId);
        }
        return usageLogId;
    }

    /**
     * 恢复创建阶段未能写入的日志。恢复日志保留 Token、渠道、模型和计费字段；
     * IP 和创建请求耗时未持久化在任务表中，因此恢复时留空，不影响余额结算与账单归属。
     */
    @Transactional
    public Long ensureLinked(Long taskId) {
        VideoTask task = videoTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("视频任务不存在: " + taskId));
        if (task.getUsageLogId() != null) {
            return task.getUsageLogId();
        }
        UsageLog usageLog = UsageLog.builder()
                .tokenId(task.getTokenId())
                .channelId(task.getChannelId())
                .model(task.getModel())
                .actualModel(task.getModel())
                .promptTokens(0)
                .completionTokens(0)
                .totalTokens(0)
                .creditCost(task.getPrepaidCost())
                .requestPath("/v1/videos")
                .build();
        return recordAndLink(taskId, usageLog);
    }
}
