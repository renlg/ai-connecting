package com.aiconnecting.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 系统公告
 */
@Entity
@Table(name = "announcements")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Announcement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 公告标题 */
    @Column(nullable = false, length = 200)
    private String title;

    /** 公告内容 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 状态: 1=启用(显示), 0=禁用(隐藏) */
    @Column(nullable = false)
    private Integer status;

    /** 创建人ID */
    @Column(nullable = false)
    private Long createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = 1;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
