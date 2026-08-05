package com.aiconnecting.repository;

import com.aiconnecting.entity.VideoTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface VideoTaskRepository extends JpaRepository<VideoTask, Long> {

    Optional<VideoTask> findFirstByUpstreamIdAndUserIdOrderByCreatedAtDesc(String upstreamId, Long userId);

    /**
     * 原子标记任务已结算：仅当尚未结算时才置位，返回受影响行数（0 或 1）；
     * 用于防止轮询接口并发/重复调用导致重复退款或重复结算
     */
    @Modifying
    @Transactional
    @Query("update VideoTask t set t.settled = true where t.id = :id and t.settled = false")
    int markSettled(@Param("id") Long id);
}
