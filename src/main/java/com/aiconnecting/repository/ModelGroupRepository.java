package com.aiconnecting.repository;

import com.aiconnecting.entity.ModelGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ModelGroupRepository extends JpaRepository<ModelGroup, Long> {

    Optional<ModelGroup> findByName(String name);

    @Query("SELECT CASE WHEN COUNT(g) > 0 THEN true ELSE false END FROM ModelGroup g " +
           "WHERE g.name = :name AND (:excludeId IS NULL OR g.id <> :excludeId)")
    boolean existsByNameExcludingId(@Param("name") String name, @Param("excludeId") Long excludeId);

    List<ModelGroup> findByEnabledTrue();

    List<ModelGroup> findAllByOrderByCreatedAtDesc();
}
