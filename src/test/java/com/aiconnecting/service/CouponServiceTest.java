package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.entity.Coupon;
import com.aiconnecting.entity.CouponRedemptionLog;
import com.aiconnecting.entity.User;
import com.aiconnecting.repository.CouponRedemptionLogRepository;
import com.aiconnecting.repository.CouponRepository;
import com.aiconnecting.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CouponServiceTest {

    @Mock private CouponRepository couponRepository;
    @Mock private UserRepository userRepository;
    @Mock private CouponRedemptionLogRepository redemptionLogRepository;
    @Mock private CacheInvalidationService cacheInvalidationService;

    @InjectMocks private CouponService couponService;

    private Coupon coupon;

    @BeforeEach
    void setUp() {
        coupon = Coupon.builder()
                .id(10L)
                .code("SAMECODE")
                .credits(new BigDecimal("25"))
                .maxUses(10)
                .usedCount(0)
                .status(1)
                .createdBy(99L)
                .expiryDate(LocalDateTime.now().plusDays(1))
                .build();
        lenient().when(couponRepository.findByCode("SAMECODE")).thenReturn(Optional.of(coupon));
        lenient().when(couponRepository.incrementUsedCount(10L)).thenReturn(1);
        lenient().when(couponRepository.findById(10L)).thenReturn(Optional.of(coupon));
        lenient().when(redemptionLogRepository.countByUserIdAndRedeemedAtBetween(
                anyLong(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(0L);
        lenient().when(redemptionLogRepository.saveAndFlush(any(CouponRedemptionLog.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void sameUserRedeemingSameCodeTwiceGetsAlreadyRedeemedError() {
        User user = User.builder().id(1L).build();
        when(redemptionLogRepository.existsByCouponIdAndUserId(10L, 1L))
                .thenReturn(false, true);

        couponService.redeemCoupon(user, "samecode");
        BusinessException error = assertThrows(BusinessException.class,
                () -> couponService.redeemCoupon(user, "samecode"));

        assertEquals("该兑换码已使用过", error.getMessage());
        assertEquals("This coupon code has already been redeemed by this user", error.getEnglishMessage());
        verify(couponRepository, times(1)).incrementUsedCount(10L);
        verify(userRepository, times(1)).addCredits(1L, new BigDecimal("25"));
    }

    @Test
    void differentUsersCanRedeemSameCodeWithinMaxUses() {
        User firstUser = User.builder().id(1L).build();
        User secondUser = User.builder().id(2L).build();
        when(redemptionLogRepository.existsByCouponIdAndUserId(eq(10L), anyLong()))
                .thenReturn(false);

        couponService.redeemCoupon(firstUser, "SAMECODE");
        couponService.redeemCoupon(secondUser, "SAMECODE");

        verify(redemptionLogRepository, times(2)).saveAndFlush(any(CouponRedemptionLog.class));
        verify(couponRepository, times(2)).incrementUsedCount(10L);
        verify(userRepository).addCredits(1L, new BigDecimal("25"));
        verify(userRepository).addCredits(2L, new BigDecimal("25"));
    }

    @Test
    void uniqueConstraintRaceIsTranslatedBeforeCountOrCreditsChange() {
        User user = User.builder().id(1L).build();
        when(redemptionLogRepository.existsByCouponIdAndUserId(10L, 1L)).thenReturn(false);
        when(redemptionLogRepository.saveAndFlush(any(CouponRedemptionLog.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> couponService.redeemCoupon(user, "SAMECODE"));

        assertEquals("该兑换码已使用过", error.getMessage());
        assertEquals("This coupon code has already been redeemed by this user", error.getEnglishMessage());
        verify(couponRepository, never()).incrementUsedCount(anyLong());
        verify(userRepository, never()).addCredits(anyLong(), any(BigDecimal.class));
        verifyNoInteractions(cacheInvalidationService);
    }
}
