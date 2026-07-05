package com.aiconnecting.repository;

import com.aiconnecting.entity.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    /**
     * 获取最新 N 条启用的公告（按创建时间倒序）
     */
    @Query("SELECT a FROM Announcement a WHERE a.status = 1 ORDER BY a.createdAt DESC LIMIT :limit")
    List<Announcement> findLatestActive(int limit);

    /**
     * 获取所有公告（按创建时间倒序，用于管理后台）
     */
    List<Announcement> findAllByOrderByCreatedAtDesc();
}
