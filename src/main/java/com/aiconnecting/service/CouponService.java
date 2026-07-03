package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.dto.CouponRedemptionDTO;
import com.aiconnecting.entity.Coupon;
import com.aiconnecting.entity.CouponRedemptionLog;
import com.aiconnecting.entity.User;
import com.aiconnecting.repository.CouponRedemptionLogRepository;
import com.aiconnecting.repository.CouponRepository;
import com.aiconnecting.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponRepository couponRepository;
    private final UserRepository userRepository;
    private final CouponRedemptionLogRepository redemptionLogRepository;

    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public Coupon generateCoupon(User admin, Double credits, Integer maxUses, LocalDateTime expiryDate) {
        if (!"admin".equalsIgnoreCase(admin.getRole())) {
            throw new BusinessException("无权限创建积分券");
        }

        String code = generateCode();
        while (couponRepository.findByCode(code).isPresent()) {
            code = generateCode();
        }

        Coupon coupon = Coupon.builder()
                .code(code)
                .credits(credits)
                .maxUses(maxUses != null ? maxUses : 1)
                .expiryDate(expiryDate)
                .createdBy(admin.getId())
                .build();

        return couponRepository.save(coupon);
    }

    @Transactional
    public Coupon redeemCoupon(User user, String code) {
        Coupon coupon = couponRepository.findByCode(code.trim().toUpperCase())
                .orElseThrow(() -> new BusinessException("兑换码不存在"));

        if (coupon.getStatus() != 1) {
            throw new BusinessException("该兑换码已被禁用");
        }

        if (coupon.getUsedCount() >= coupon.getMaxUses()) {
            throw new BusinessException("该兑换码已达到使用次数上限");
        }

        if (coupon.getExpiryDate() != null && coupon.getExpiryDate().isBefore(LocalDateTime.now())) {
            throw new BusinessException("该兑换码已过期");
        }

        // 检查今日兑换次数上限（每人每天最多 100 次）
        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        LocalDateTime todayEnd = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);
        long todayCount = redemptionLogRepository.countByUserIdAndRedeemedAtBetween(user.getId(), todayStart, todayEnd);
        if (todayCount >= 100) {
            throw new BusinessException("今日兑换次数已达上限");
        }

        coupon.setUsedCount(coupon.getUsedCount() + 1);
        couponRepository.save(coupon);

        User freshUser = userRepository.findById(user.getId())
                .orElseThrow(() -> new BusinessException("用户不存在"));
        freshUser.setCredits((freshUser.getCredits() != null ? freshUser.getCredits() : 0.0) + coupon.getCredits());
        userRepository.save(freshUser);

        // 记录兑换日志
        CouponRedemptionLog log = CouponRedemptionLog.builder()
                .userId(user.getId())
                .couponId(coupon.getId())
                .build();
        redemptionLogRepository.save(log);

        return coupon;
    }

    public List<Coupon> listCoupons() {
        return couponRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<CouponRedemptionDTO> getRedemptionsByCouponId(Long couponId) {
        List<CouponRedemptionLog> logs = redemptionLogRepository.findByCouponIdOrderByRedeemedAtDesc(couponId);
        Coupon coupon = couponRepository.findById(couponId).orElse(null);
        Double credits = coupon != null ? coupon.getCredits() : 0.0;

        List<CouponRedemptionDTO> result = new ArrayList<>();
        for (CouponRedemptionLog log : logs) {
            User user = userRepository.findById(log.getUserId()).orElse(null);
            result.add(CouponRedemptionDTO.builder()
                    .userId(log.getUserId())
                    .username(user != null ? user.getUsername() : "未知用户")
                    .nickname(user != null ? user.getNickname() : null)
                    .redeemedAt(log.getRedeemedAt())
                    .credits(credits)
                    .build());
        }
        return result;
    }

    @Transactional
    public Coupon toggleStatus(Long id, Integer status) {
        Coupon coupon = couponRepository.findById(id)
                .orElseThrow(() -> new BusinessException("积分券不存在"));
        coupon.setStatus(status);
        return couponRepository.save(coupon);
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}
