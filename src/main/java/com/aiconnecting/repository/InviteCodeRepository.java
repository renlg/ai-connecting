package com.aiconnecting.repository;

import com.aiconnecting.entity.InviteCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface InviteCodeRepository extends JpaRepository<InviteCode, Long> {
    Optional<InviteCode> findByCode(String code);

    boolean existsByCode(String code);

    @Query("SELECT i FROM InviteCode i ORDER BY i.createdAt DESC")
    List<InviteCode> findAllOrderByCreatedAtDesc();

    /** 原子消耗一次邀请码，状态、有效期和次数限制均在同一条更新语句中校验。 */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE InviteCode i SET i.usedCount = i.usedCount + 1, i.updatedAt = :now " +
            "WHERE i.code = :code AND i.status = 1 AND i.usedCount < i.maxUses " +
            "AND (i.expiryDate IS NULL OR i.expiryDate > :now)")
    int consume(@Param("code") String code, @Param("now") LocalDateTime now);
}
