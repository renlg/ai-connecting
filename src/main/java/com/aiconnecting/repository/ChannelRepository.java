package com.aiconnecting.repository;

import com.aiconnecting.entity.Channel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ChannelRepository extends JpaRepository<Channel, Long> {

    List<Channel> findByStatusOrderByPriorityDesc(Integer status);

    @Query("SELECT c FROM Channel c WHERE c.status = 1 AND (',' || c.models || ',') LIKE :modelPattern ORDER BY c.priority DESC")
    List<Channel> findActiveChannelsByModel(@Param("modelPattern") String modelPattern);

    @Modifying
    @Query("UPDATE Channel c SET c.usedQuota = c.usedQuota + :delta WHERE c.id = :channelId")
    void addUsedQuota(@Param("channelId") Long channelId, @Param("delta") long delta);
}
