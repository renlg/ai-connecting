package com.aiconnecting.repository;

import com.aiconnecting.entity.VideoTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VideoTaskRepository extends JpaRepository<VideoTask, Long> {

    Optional<VideoTask> findFirstByUpstreamIdAndUserIdOrderByCreatedAtDesc(String upstreamId, Long userId);
}
