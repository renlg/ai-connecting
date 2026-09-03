package com.aiconnecting.repository;

import com.aiconnecting.entity.RefundCompensation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RefundCompensationRepository extends JpaRepository<RefundCompensation, Long> {

    List<RefundCompensation> findTop200ByOrderByCreatedAtDesc();
}
