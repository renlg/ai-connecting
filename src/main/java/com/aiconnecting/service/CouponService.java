package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.dto.CouponRedemptionDTO;
import com.aiconnecting.entity.Coupon;
import com.aiconnecting.entity.CouponRedemptionLog;
import com.aiconnecting.entity.User;
import com.aiconnecting.repository.CouponRedemptionLogRepository;
import com.aiconnecting.repository.CouponRepository;
import com.aiconnecting.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponRepository couponRepository;
    private final UserRepository userRepository;
    private final CouponRedemptionLogRepository redemptionLogRepository;
    private final CacheInvalidationService cacheInvalidationService;

    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int COUPON_CODE_GENERATION_ATTEMPTS = 10;
    private static final String ALREADY_REDEEMED_MESSAGE = "该兑换码已使用过";
    private static final String ALREADY_REDEEMED_MESSAGE_EN =
            "This coupon code has already been redeemed by this user";

    public Coupon generateCoupon(User admin, BigDecimal credits, Integer maxUses, LocalDateTime expiryDate) {
        if (!"admin".equalsIgnoreCase(admin.getRole())) {
            throw new BusinessException("无权限创建积分券", "Not authorized to create credit coupons");
        }

        DataIntegrityViolationException lastCollision = null;
        for (int attempt = 0; attempt < COUPON_CODE_GENERATION_ATTEMPTS; attempt++) {
            String code = generateCode();
            if (couponRepository.findByCode(code).isPresent()) {
                continue;
            }

            Coupon coupon = Coupon.builder()
                    .code(code)
                    .credits(credits)
                    .maxUses(maxUses != null ? maxUses : 1)
                    .expiryDate(expiryDate)
                    .createdBy(admin.getId())
                    .build();

            try {
                return couponRepository.save(coupon);
            } catch (DataIntegrityViolationException e) {
                lastCollision = e;
            }
        }
        throw new BusinessException(400, "兑换码标识已存在，请重试",
                "Coupon code identifier already exists; retry", lastCollision);
    }

    @Transactional
    public Coupon redeemCoupon(User user, String code) {
        Coupon coupon = couponRepository.findByCode(code.trim().toUpperCase())
                .orElseThrow(() -> new BusinessException("兑换码不存在", "Redemption code not found"));

        if (coupon.getStatus() != 1) {
            throw new BusinessException("该兑换码已被禁用", "This redemption code is disabled");
        }

        // 友好路径：在变更兑换次数和积分前拒绝同一用户重复兑换。
        if (redemptionLogRepository.existsByCouponIdAndUserId(coupon.getId(), user.getId())) {
            throw alreadyRedeemedException();
        }

        if (coupon.getUsedCount() >= coupon.getMaxUses()) {
            throw new BusinessException("该兑换码已达到使用次数上限", "This redemption code has reached its usage limit");
        }

        if (coupon.getExpiryDate() != null && coupon.getExpiryDate().isBefore(LocalDateTime.now())) {
            throw new BusinessException("该兑换码已过期", "This redemption code has expired");
        }

        // 检查今日兑换次数上限（每人每天最多 100 次）
        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        LocalDateTime todayEnd = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);
        long todayCount = redemptionLogRepository.countByUserIdAndRedeemedAtBetween(user.getId(), todayStart, todayEnd);
        if (todayCount >= 100) {
            throw new BusinessException("今日兑换次数已达上限", "Today’s redemption limit has been reached");
        }

        // 先用唯一约束抢占 (coupon_id, user_id)。并发请求中只有一个能成功；flush 让约束异常
        // 在本事务内被捕获并翻译，后续 used_count/credits 变更尚未发生。
        CouponRedemptionLog log = CouponRedemptionLog.builder()
                .userId(user.getId())
                .couponId(coupon.getId())
                .build();
        try {
            redemptionLogRepository.saveAndFlush(log);
        } catch (DataIntegrityViolationException e) {
            throw alreadyRedeemedException(e);
        }

        // 原子递增使用次数（WHERE 条件保证不超限，返回 0 表示已达上限）
        int affected = couponRepository.incrementUsedCount(coupon.getId());
        if (affected == 0) {
            throw new BusinessException("该兑换码已达到使用次数上限", "This redemption code has reached its usage limit");
        }

        Coupon updated = couponRepository.findById(coupon.getId())
                .orElseThrow(() -> new BusinessException("积分券不存在", "Credit coupon not found"));

        userRepository.addCredits(user.getId(), coupon.getCredits());
        cacheInvalidationService.publish(CacheInvalidationService.USER_PREFIX + user.getId());

        return updated;
    }

    public List<Coupon> listCoupons() {
        return couponRepository.findAllOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<CouponRedemptionDTO> getRedemptionsByCouponId(Long couponId) {
        List<CouponRedemptionLog> logs = redemptionLogRepository.findByCouponIdOrderByRedeemedAtDesc(couponId);
        Coupon coupon = couponRepository.findById(couponId).orElse(null);
        BigDecimal credits = coupon != null ? coupon.getCredits() : BigDecimal.ZERO;

        // 批量查询用户，避免 N+1 查询
        var userIds = logs.stream().map(CouponRedemptionLog::getUserId).distinct().toList();
        var userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        List<CouponRedemptionDTO> result = new ArrayList<>();
        for (CouponRedemptionLog log : logs) {
            User user = userMap.get(log.getUserId());
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
                .orElseThrow(() -> new BusinessException("积分券不存在", "Credit coupon not found"));
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

    private BusinessException alreadyRedeemedException() {
        return new BusinessException(ALREADY_REDEEMED_MESSAGE, ALREADY_REDEEMED_MESSAGE_EN);
    }

    private BusinessException alreadyRedeemedException(DataIntegrityViolationException cause) {
        return new BusinessException(400, ALREADY_REDEEMED_MESSAGE, ALREADY_REDEEMED_MESSAGE_EN, cause);
    }
}
