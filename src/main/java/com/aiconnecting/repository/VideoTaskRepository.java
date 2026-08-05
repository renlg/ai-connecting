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
     * 查找尚未结算、创建时间早于阈值（排除刚创建不久、大概率仍在客户端正常轮询中的任务）、
     * 且到达可重新扫描时间（nextReconcileAt 为空或已过期）的任务，按 nextReconcileAt 升序排列
     * （新任务/最久未被检查过的任务优先），供后台对账任务扫描处理客户端未轮询到终态的任务。
     * 按 nextReconcileAt 排序 + 每次扫描后推迟该字段，保证长期卡住的任务不会永久占据每轮固定的批次名额，
     * 使其后创建的任务也能被轮到（而不是无排序地反复读到同一批最早创建的任务）
     */
    @Query("select t from VideoTask t where t.settled = false and t.createdAt < :cutoff "
            + "and (t.nextReconcileAt is null or t.nextReconcileAt <= :now) "
            + "order by t.nextReconcileAt asc nulls first")
    List<VideoTask> findReconcileCandidates(@Param("cutoff") LocalDateTime cutoff,
                                             @Param("now") LocalDateTime now,
                                             Pageable pageable);

    /**
     * 原子抢占未结算任务：只有一个调用者能将已到期的处理租约推迟。
     * 后台对账与客户端轮询共用此方法，避免并发请求同一上游任务。
     */
    @Modifying
    @Transactional
    @Query("update VideoTask t set t.processingLeaseUntil = :leaseUntil where t.id = :id "
            + "and t.settled = false and (t.processingLeaseUntil is null or t.processingLeaseUntil <= :now)")
    int claimForProcessing(@Param("id") Long id, @Param("now") LocalDateTime now,
                           @Param("leaseUntil") LocalDateTime leaseUntil);

    /**
     * 客户端轮询的原子抢占。已结算任务仍需允许客户端查询上游结果，
     * 但同样通过处理租约避免并发重复查询。
     */
    @Modifying
    @Transactional
    @Query("update VideoTask t set t.processingLeaseUntil = :leaseUntil where t.id = :id "
            + "and (t.processingLeaseUntil is null or t.processingLeaseUntil <= :now)")
    int claimForPolling(@Param("id") Long id, @Param("now") LocalDateTime now,
                        @Param("leaseUntil") LocalDateTime leaseUntil);

    /**
     * 仅当任务仍持有本次租约时释放，避免超时的旧请求释放新持有者的租约。
     */
    @Modifying
    @Transactional
    @Query("update VideoTask t set t.processingLeaseUntil = null where t.id = :id "
            + "and t.processingLeaseUntil = :leaseUntil")
    int releaseClaim(@Param("id") Long id, @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 对账尝试后推迟下次扫描时间，保持批次间的公平调度。 */
    @Modifying
    @Transactional
    @Query("update VideoTask t set t.nextReconcileAt = :next where t.id = :id and t.settled = false")
    int touchNextReconcileAt(@Param("id") Long id, @Param("next") LocalDateTime next);

    /**
     * 关联创建任务时写入的预扣使用日志 id，供退款/差额结算时回写该日志的最终计费金额
     */
    @Modifying
    @Transactional
    @Query("update VideoTask t set t.usageLogId = :usageLogId where t.id = :id and t.usageLogId is null")
    int linkUsageLog(@Param("id") Long id, @Param("usageLogId") Long usageLogId);

    /**
     * 记录任务视频已转存的 OSS 直链，供后续轮询直接复用、跳过重复下载/上传同一个已完成视频。
     */
    @Modifying
    @Transactional
    @Query("update VideoTask t set t.ossUrl = :ossUrl where t.id = :id and t.ossUrl is null")
    int updateOssUrl(@Param("id") Long id, @Param("ossUrl") String ossUrl);
}
