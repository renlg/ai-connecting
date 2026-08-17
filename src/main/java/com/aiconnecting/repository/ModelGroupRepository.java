package com.aiconnecting.repository;

import com.aiconnecting.entity.ModelGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelGroupRepository extends JpaRepository<ModelGroup, Long> {

    Optional<ModelGroup> findByName(String name);

    List<ModelGroup> findByEnabledTrue();

    List<ModelGroup> findAllByOrderByCreatedAtDesc();
}
