package com.aiconnecting.repository;

import com.aiconnecting.entity.ModelConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ModelConfigRepository extends JpaRepository<ModelConfig, Long> {

    List<ModelConfig> findAllByOrderByStatusDescNameAsc();

    List<ModelConfig> findByStatusOrderByStatusDescNameAsc(Integer status);

    Optional<ModelConfig> findByName(String name);

    List<ModelConfig> findByAdminOnlyFalseOrderByStatusDescNameAsc();

    List<ModelConfig> findByStatusAndAdminOnlyFalseOrderByStatusDescNameAsc(Integer status);
}
