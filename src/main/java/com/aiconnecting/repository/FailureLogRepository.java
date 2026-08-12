package com.aiconnecting.repository;

import com.aiconnecting.entity.FailureLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface FailureLogRepository extends JpaRepository<FailureLog, Long>, JpaSpecificationExecutor<FailureLog> {
    long deleteByCreatedAtLessThan(long cutoff);
}
