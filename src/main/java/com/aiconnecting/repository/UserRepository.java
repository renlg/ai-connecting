package com.aiconnecting.repository;

import com.aiconnecting.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
    Optional<User> findByInviteCode(String inviteCode);
    boolean existsByInviteCode(String inviteCode);

    /** 原子消耗普通用户的邀请码，返回 0 表示已被其他注册请求消耗。 */
    @Modifying
    @Query("UPDATE User u SET u.inviteCodeUsed = true " +
            "WHERE u.id = :userId AND u.level <> 5 AND (u.inviteCodeUsed = false OR u.inviteCodeUsed IS NULL)")
    int consumeInviteCode(@Param("userId") Long userId);

    @Modifying
    @Query("UPDATE User u SET u.credits = CASE WHEN u.credits - :amount < 0 THEN 0 ELSE u.credits - :amount END WHERE u.id = :userId")
    void deductCredits(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    @Modifying
    @Query("UPDATE User u SET u.credits = COALESCE(u.credits, 0) + :amount WHERE u.id = :userId")
    void addCredits(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /** 结算补扣：无下限原子扣减，允许余额为负，保证实际扣减金额与使用日志记录一致 */
    @Modifying
    @Query("UPDATE User u SET u.credits = COALESCE(u.credits, 0) - :amount WHERE u.id = :userId")
    void deductCreditsAllowNegative(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /** 余额充足时原子扣减积分，返回受影响行数（0=余额不足，未扣减） */
    @Modifying
    @Query("UPDATE User u SET u.credits = u.credits - :amount WHERE u.id = :userId AND u.credits >= :amount")
    int tryDeductCredits(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    @Query("SELECT u FROM User u WHERE " +
           "LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.nickname) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "ORDER BY u.createdAt DESC")
    List<User> searchByKeyword(@Param("keyword") String keyword);

    @Query("SELECT u FROM User u ORDER BY u.createdAt DESC")
    List<User> findAllOrderByCreatedAtDesc();
}
