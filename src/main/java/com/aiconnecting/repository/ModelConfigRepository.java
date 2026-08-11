package com.aiconnecting.repository;

import com.aiconnecting.entity.ModelConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ModelConfigRepository extends JpaRepository<ModelConfig, Long> {

    List<ModelConfig> findAllByOrderByStatusDescNameAsc();

    List<ModelConfig> findByStatusOrderByStatusDescNameAsc(Integer status);

    List<ModelConfig> findByName(String name);

    List<ModelConfig> findByDisplayName(String displayName);

    @Query("SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END FROM ModelConfig m " +
           "WHERE m.displayName = :displayName AND (:excludeId IS NULL OR m.id <> :excludeId)")
    boolean existsByDisplayNameExcludingId(@Param("displayName") String displayName, @Param("excludeId") Long excludeId);

    List<ModelConfig> findByAdminOnlyFalseOrderByStatusDescNameAsc();

    List<ModelConfig> findByStatusAndAdminOnlyFalseOrderByStatusDescNameAsc(Integer status);

    @Query("SELECT m FROM ModelConfig m ORDER BY m.createdAt DESC")
    List<ModelConfig> findAllOrderByCreatedAtDesc();

    @Query("SELECT m FROM ModelConfig m WHERE m.adminOnly = false ORDER BY m.createdAt DESC")
    List<ModelConfig> findByAdminOnlyFalseOrderByCreatedAtDesc();

    @Query("SELECT m FROM ModelConfig m " +
           "WHERE (:name IS NULL OR LOWER(m.name) LIKE LOWER(CONCAT('%', :name, '%'))) " +
           "AND (:type IS NULL OR LOWER(m.type) LIKE LOWER(CONCAT('%', :type, '%'))) " +
           "ORDER BY m.createdAt DESC")
    List<ModelConfig> searchAll(@Param("name") String name, @Param("type") String type);

    @Query("SELECT m FROM ModelConfig m " +
           "WHERE m.adminOnly = false " +
           "AND (:name IS NULL OR LOWER(m.name) LIKE LOWER(CONCAT('%', :name, '%'))) " +
           "AND (:type IS NULL OR LOWER(m.type) LIKE LOWER(CONCAT('%', :type, '%'))) " +
           "ORDER BY m.createdAt DESC")
    List<ModelConfig> searchNonAdmin(@Param("name") String name, @Param("type") String type);
}
