package com.aiconnecting.repository;

import com.aiconnecting.entity.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    /**
     * 获取最新 N 条启用的公告（按更新时间倒序，未更新过的按创建时间倒序）
     */
    @Query("SELECT a FROM Announcement a WHERE a.status = 1 ORDER BY COALESCE(a.updatedAt, a.createdAt) DESC LIMIT :limit")
    List<Announcement> findLatestActive(int limit);

    /**
     * 获取所有公告（按更新时间倒序，未更新过的按创建时间倒序，用于管理后台）
     */
    @Query("SELECT a FROM Announcement a ORDER BY COALESCE(a.updatedAt, a.createdAt) DESC")
    List<Announcement> findAllByOrderByUpdatedAtDesc();
}
