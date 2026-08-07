package com.aiconnecting.service;

import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.entity.VideoTask;
import com.aiconnecting.repository.UsageLogRepository;
import com.aiconnecting.repository.VideoTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 视频任务终态计费结算：将「置位已结算」与「余额退款/补扣、使用日志回写」放在同一个数据库事务内完成，
 * 任一环节抛出异常都会使整个事务（含置位）回滚，任务保持未结算，
 * 供轮询接口的下一次调用或后台对账任务（见 {@link OpenAiRelayService} 中的 @Scheduled 方法）安全重试，
 * 不会出现"进程崩溃/结算失败后任务被永久标记已结算但退款未完成"的情况，也不会重复退款/结算
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoTaskSettlementService {

    private final VideoTaskRepository videoTaskRepository;
    private final UserService userService;
    private final UsageLogRepository usageLogRepository;
    private final ModelConfigService modelConfigService;
    private final UsageLogService usageLogService;
    private final VideoTaskUsageLogService videoTaskUsageLogService;
    private final CacheInvalidationService cacheInvalidationService;

    public enum Outcome { SETTLED, SKIPPED }

    /**
     * 任务失败/取消：全额退回预扣积分，使用日志计费金额同步改为 0
     */
    @Transactional
    public Outcome settleFailed(Long taskId) {
        if (videoTaskRepository.markSettled(taskId) == 0) {
            return Outcome.SKIPPED;
        }
        VideoTask task = videoTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("视频任务不存在: " + taskId));
        task.setUsageLogId(videoTaskUsageLogService.ensureLinked(taskId));
        if (task.isDeducted()) {
            BigDecimal prepaid = task.getPrepaidCost() != null ? task.getPrepaidCost() : BigDecimal.ZERO;
            if (prepaid.compareTo(BigDecimal.ZERO) > 0) {
                userService.refundCredits(task.getUserId(), prepaid);
            }
        }
        // 使用日志回写不受 deducted 影响：admin 放行未扣减时创建阶段仍按预扣金额写入了日志，
        // 失败时同样要归零，否则账单/Dashboard 会显示一笔从未真正结算过的预扣金额
        adjustUsageLog(task, BigDecimal.ZERO);
        return Outcome.SETTLED;
    }

    /**
     * 任务完成：若上游暴露实际生成时长，按任务提交时的档位单价（而非结算时可能已变化的当前价格）
     * 重新计算实际费用并多退少补；无法获得实际时长或原始单价时保持预扣金额不变（仍置为已结算）
     */
    @Transactional
    public Outcome settleCompleted(Long taskId, Integer actualSeconds) {
        if (videoTaskRepository.markSettled(taskId) == 0) {
            return Outcome.SKIPPED;
        }
        VideoTask task = videoTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("视频任务不存在: " + taskId));
        task.setUsageLogId(videoTaskUsageLogService.ensureLinked(taskId));
        if (actualSeconds == null) {
            return Outcome.SETTLED;
        }
        BigDecimal unitPrice = resolveOriginalUnitPrice(task);
        if (unitPrice == null) {
            log.warn("视频任务结算：无法确定提交时的原始单价，按预扣金额保持计费: taskId={}, model={}", taskId, task.getModel());
            return Outcome.SETTLED;
        }
        BigDecimal actualCost = unitPrice.multiply(BigDecimal.valueOf(actualSeconds));
        // 余额调整与使用日志回写口径必须一致：admin 放行未扣减（deducted=false）的任务
        // 从未真正收费，日志也必须记 0，而不是"本应收取"的名义金额，
        // 否则会与 settleFailed 的归零口径不一致，也与 RelaySupport 创建阶段的记账口径矛盾
        if (task.isDeducted()) {
            BigDecimal prepaid = task.getPrepaidCost() != null ? task.getPrepaidCost() : BigDecimal.ZERO;
            BigDecimal diff = actualCost.subtract(prepaid);
            if (diff.compareTo(BigDecimal.ZERO) > 0) {
                userService.deductCreditsSettlement(task.getUserId(), diff);
            } else if (diff.compareTo(BigDecimal.ZERO) < 0) {
                userService.refundCredits(task.getUserId(), diff.negate());
            }
        }
        adjustUsageLog(task, task.isDeducted() ? actualCost : BigDecimal.ZERO);
        return Outcome.SETTLED;
    }

    /**
     * 原始单价优先取任务保存的 unitPrice（提交时的档位单价）；缺失时退化为预扣金额/声明时长反推；
     * 仍无法确定时（历史存量任务缺少这些字段）才回退到当前模型配置的价格
     */
    private BigDecimal resolveOriginalUnitPrice(VideoTask task) {
        if (task.getUnitPrice() != null) {
            return task.getUnitPrice();
        }
        if (task.getPrepaidCost() != null && task.getDurationSeconds() != null && task.getDurationSeconds() > 0) {
            return task.getPrepaidCost().divide(BigDecimal.valueOf(task.getDurationSeconds()), 6, RoundingMode.HALF_UP);
        }
        if (task.getModel() == null) {
            return null;
        }
        List<ModelConfig> configs = modelConfigService.findByName(task.getModel());
        if (configs.isEmpty()) {
            return null;
        }
        try {
            return usageLogService.resolveVideoUnitPrice(configs.get(0), task.getSize());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将创建任务时写入的预扣使用日志金额回写为最终计费金额，
     * 保证账单/Dashboard/积分汇总反映真实消费而非预扣金额（音频路径的一致做法见 OpenAiRelayService）
     */
    private void adjustUsageLog(VideoTask task, BigDecimal finalCost) {
        if (task.getUsageLogId() == null) {
            return;
        }
        usageLogRepository.findById(task.getUsageLogId()).ifPresent(usageLog -> {
            usageLog.setCreditCost(finalCost);
            usageLogRepository.save(usageLog);
            cacheInvalidationService.publish(CacheInvalidationService.USAGE_STATS);
        });
    }
}
