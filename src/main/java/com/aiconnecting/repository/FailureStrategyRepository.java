package com.aiconnecting.repository;

import com.aiconnecting.entity.FailureStrategy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FailureStrategyRepository extends JpaRepository<FailureStrategy, Long> {

    @Query("SELECT s FROM FailureStrategy s WHERE s.enabled = true ORDER BY s.priority ASC")
    List<FailureStrategy> findAllEnabledOrderByPriorityAsc();

    @Query("SELECT s FROM FailureStrategy s ORDER BY s.priority ASC")
    List<FailureStrategy> findAllOrderByPriorityAsc();
}
