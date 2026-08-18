package com.aiconnecting.repository;

import com.aiconnecting.entity.RiskPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RiskPolicyRepository extends JpaRepository<RiskPolicy, Long> {

    @Query("SELECT p FROM RiskPolicy p WHERE p.status = 1 AND p.channelId = :channelId")
    List<RiskPolicy> findEnabledByChannelId(@Param("channelId") Long channelId);

    @Query("SELECT p FROM RiskPolicy p WHERE p.status = 1 AND p.channelId = :channelId AND p.modelConfigName = :modelConfigName")
    List<RiskPolicy> findEnabledByChannelAndModel(@Param("channelId") Long channelId, @Param("modelConfigName") String modelConfigName);

    @Query("SELECT p FROM RiskPolicy p WHERE p.status = 1")
    List<RiskPolicy> findAllEnabled();

    @Query("SELECT p FROM RiskPolicy p ORDER BY p.createdAt DESC")
    List<RiskPolicy> findAllOrderByCreatedAtDesc();
}
