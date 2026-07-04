package com.aiconnecting.repository;

import com.aiconnecting.entity.ModelConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ModelConfigRepository extends JpaRepository<ModelConfig, Long> {

    List<ModelConfig> findAllByOrderByStatusDescNameAsc();

    List<ModelConfig> findByStatusOrderByStatusDescNameAsc(Integer status);

    List<ModelConfig> findByName(String name);

    List<ModelConfig> findByDisplayName(String displayName);

    List<ModelConfig> findByAdminOnlyFalseOrderByStatusDescNameAsc();

    List<ModelConfig> findByStatusAndAdminOnlyFalseOrderByStatusDescNameAsc(Integer status);
}
