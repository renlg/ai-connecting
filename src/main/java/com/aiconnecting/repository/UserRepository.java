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

    @Modifying
    @Query("UPDATE User u SET u.credits = CASE WHEN u.credits - :amount < 0 THEN 0 ELSE u.credits - :amount END WHERE u.id = :userId")
    void deductCredits(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    @Modifying
    @Query("UPDATE User u SET u.credits = COALESCE(u.credits, 0) + :amount WHERE u.id = :userId")
    void addCredits(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /** 余额充足时原子扣减积分，返回受影响行数（0=余额不足，未扣减） */
    @Modifying
    @Query("UPDATE User u SET u.credits = u.credits - :amount WHERE u.id = :userId AND u.credits >= :amount")
    int tryDeductCredits(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    @Query("SELECT u FROM User u WHERE " +
           "LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.nickname) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<User> searchByKeyword(@Param("keyword") String keyword);
}
