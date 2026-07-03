package com.aiconnecting.repository;

import com.aiconnecting.entity.CouponRedemptionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface CouponRedemptionLogRepository extends JpaRepository<CouponRedemptionLog, Long> {

    long countByUserIdAndRedeemedAtBetween(Long userId, LocalDateTime start, LocalDateTime end);

    List<CouponRedemptionLog> findByCouponIdOrderByRedeemedAtDesc(Long couponId);
}
