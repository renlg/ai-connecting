package com.aiconnecting.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 50)
    private String username;

    @JsonIgnore
    @Column(nullable = false)
    private String password;

    @Column(length = 100)
    private String nickname;

    @Column(length = 200)
    private String email;

    /** 角色: admin / user */
    @Column(nullable = false, length = 20)
    private String role;

    /** 可用额度 (以 token 数为单位, -1 表示无限) */
    @Column(nullable = false)
    private Long quota;

    /** 已用额度 */
    @Column(nullable = false)
    private Long usedQuota;

    /** 积分 (支持小数，精确计费) */
    @Column(nullable = false)
    private Double credits;

    /** 状态: 1=启用, 0=禁用 */
    @Column(nullable = false)
    private Integer status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (role == null) role = "user";
        if (quota == null) quota = -1L;
        if (usedQuota == null) usedQuota = 0L;
        if (credits == null) credits = 0.0;
        if (status == null) status = 1;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
