package com.aiconnecting.repository;

import com.aiconnecting.entity.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long> {
    Optional<Coupon> findByCode(String code);

    /**
     * 原子递增使用次数，返回更新后的 usedCount
     * 如果超过 maxUses 则不会更新（SQLite 不支持 WHERE 条件更新，需在业务层校验）
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Coupon c SET c.usedCount = c.usedCount + 1 WHERE c.id = :couponId AND c.usedCount < c.maxUses")
    int incrementUsedCount(@org.springframework.data.repository.query.Param("couponId") Long couponId);
}
