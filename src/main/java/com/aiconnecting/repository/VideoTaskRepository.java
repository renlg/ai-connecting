package com.aiconnecting.repository;

import com.aiconnecting.entity.VideoTask;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface VideoTaskRepository extends JpaRepository<VideoTask, Long> {

    Optional<VideoTask> findFirstByUpstreamIdAndUserIdOrderByCreatedAtDesc(String upstreamId, Long userId);

    /**
     * 原子标记任务已结算：仅当尚未结算时才置位，返回受影响行数（0 或 1）；
     * 与调用方的退款/补扣、使用日志回写在同一个事务内执行，任一环节失败整体回滚，
     * 该置位也会随之撤销，任务保持未结算，供轮询/后台对账任务安全重试（不会重复扣退款）
     */
    @Modifying
    @Transactional
    @Query("update VideoTask t set t.settled = true where t.id = :id and t.settled = false")
    int markSettled(@Param("id") Long id);

    /**
     * 查找尚未结算、且创建时间早于阈值的任务（供后台对账任务扫描处理客户端未轮询到终态的任务），
     * 排除刚创建不久、大概率仍在客户端正常轮询中的任务，减少对上游的重复查询
     */
    List<VideoTask> findBySettledFalseAndCreatedAtBefore(LocalDateTime cutoff, Pageable pageable);

    /**
     * 关联创建任务时写入的预扣使用日志 id，供退款/差额结算时回写该日志的最终计费金额
     */
    @Modifying
    @Transactional
    @Query("update VideoTask t set t.usageLogId = :usageLogId where t.id = :id")
    int linkUsageLog(@Param("id") Long id, @Param("usageLogId") Long usageLogId);
}
