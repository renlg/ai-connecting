package com.aiconnecting.repository;

import com.aiconnecting.entity.ModelConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface ModelConfigRepository extends JpaRepository<ModelConfig, Long> {

    List<ModelConfig> findAllByOrderByStatusDescNameAsc();

    List<ModelConfig> findByStatusOrderByStatusDescNameAsc(Integer status);

    List<ModelConfig> findByName(String name);

    List<ModelConfig> findByDisplayName(String displayName);

    List<ModelConfig> findByAdminOnlyFalseOrderByStatusDescNameAsc();

    List<ModelConfig> findByStatusAndAdminOnlyFalseOrderByStatusDescNameAsc(Integer status);

    @Query("SELECT m FROM ModelConfig m ORDER BY COALESCE(m.updatedAt, m.createdAt) DESC")
    List<ModelConfig> findAllOrderByUpdatedAtDesc();

    @Query("SELECT m FROM ModelConfig m WHERE m.adminOnly = false ORDER BY COALESCE(m.updatedAt, m.createdAt) DESC")
    List<ModelConfig> findByAdminOnlyFalseOrderByUpdatedAtDesc();
}
