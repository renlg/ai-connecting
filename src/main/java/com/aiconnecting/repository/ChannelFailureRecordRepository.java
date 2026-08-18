package com.aiconnecting.repository;

import com.aiconnecting.entity.ChannelFailureRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChannelFailureRecordRepository extends JpaRepository<ChannelFailureRecord, Long>, JpaSpecificationExecutor<ChannelFailureRecord> {

    @Query("SELECT r FROM ChannelFailureRecord r WHERE r.analyzed = false AND r.createdAt >= :since ORDER BY r.createdAt ASC")
    List<ChannelFailureRecord> findUnanalyzedSince(@Param("since") long since, org.springframework.data.domain.Pageable pageable);

    @Modifying
    @Query("UPDATE ChannelFailureRecord r SET r.analyzed = true WHERE r.id IN :ids")
    int markAnalyzed(@Param("ids") List<Long> ids);

    long deleteByCreatedAtLessThan(long cutoff);
}
