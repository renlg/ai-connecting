package com.aiconnecting.repository;

import com.aiconnecting.entity.Channel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ChannelRepository extends JpaRepository<Channel, Long> {

    List<Channel> findByStatusOrderByPriorityDesc(Integer status);

    @Query("SELECT c FROM Channel c WHERE c.status = 1 AND (',' || c.modelIds || ',') LIKE :modelIdPattern ORDER BY c.priority DESC")
    List<Channel> findActiveChannelsByModel(@Param("modelIdPattern") String modelIdPattern);

    @Query("SELECT c FROM Channel c WHERE (',' || c.modelIds || ',') LIKE :modelIdPattern")
    List<Channel> findChannelsByModel(@Param("modelIdPattern") String modelIdPattern);

    @Query("SELECT c FROM Channel c ORDER BY c.createdAt DESC")
    List<Channel> findAllOrderByCreatedAtDesc();

    @Query("SELECT c FROM Channel c WHERE LOWER(c.name) LIKE LOWER(CONCAT('%', :name, '%')) " +
           "ORDER BY c.createdAt DESC")
    List<Channel> searchByName(@Param("name") String name);

    @Modifying
    @Query("UPDATE Channel c SET c.usedQuota = c.usedQuota + :delta WHERE c.id = :channelId")
    void addUsedQuota(@Param("channelId") Long channelId, @Param("delta") long delta);
}
