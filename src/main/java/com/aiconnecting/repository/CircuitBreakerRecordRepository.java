package com.aiconnecting.repository;

import com.aiconnecting.entity.CircuitBreakerRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface CircuitBreakerRecordRepository extends JpaRepository<CircuitBreakerRecord, Long> {

    @Query("SELECT r FROM CircuitBreakerRecord r WHERE r.status = 'ACTIVE' AND r.expiresAt > :now")
    List<CircuitBreakerRecord> findActiveNotExpired(@Param("now") LocalDateTime now);

    @Query("SELECT r FROM CircuitBreakerRecord r " +
            "WHERE (:channelId IS NULL OR r.channelId = :channelId) " +
            "AND (:modelName IS NULL OR LOWER(r.modelConfigName) LIKE LOWER(CONCAT('%', :modelName, '%'))) " +
            "AND (:status IS NULL OR r.status = :status)")
    Page<CircuitBreakerRecord> searchRecords(@Param("channelId") Long channelId,
                                              @Param("modelName") String modelName,
                                              @Param("status") String status,
                                              Pageable pageable);

    @Query("SELECT r FROM CircuitBreakerRecord r WHERE r.channelId = :channelId AND r.status = 'ACTIVE' AND r.expiresAt > :now")
    List<CircuitBreakerRecord> findActiveByChannelId(@Param("channelId") Long channelId, @Param("now") LocalDateTime now);

    @Query("SELECT r FROM CircuitBreakerRecord r WHERE r.channelId = :channelId AND (r.modelConfigName IS NULL OR r.modelConfigName = '') AND r.status = 'ACTIVE' AND r.expiresAt > :now")
    List<CircuitBreakerRecord> findActiveByChannelIdAndModelIsNull(@Param("channelId") Long channelId, @Param("now") LocalDateTime now);

    @Query("SELECT r FROM CircuitBreakerRecord r WHERE r.channelId = :channelId AND r.modelConfigName = :modelConfigName AND r.status = 'ACTIVE' AND r.expiresAt > :now")
    List<CircuitBreakerRecord> findActiveByChannelAndModel(@Param("channelId") Long channelId,
                                                            @Param("modelConfigName") String modelConfigName,
                                                            @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE CircuitBreakerRecord r SET r.status = 'EXPIRED' WHERE r.status = 'ACTIVE' AND r.expiresAt <= :now")
    int expireOldRecords(@Param("now") LocalDateTime now);
}
