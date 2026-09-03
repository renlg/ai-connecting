package com.aiconnecting.service;

import com.aiconnecting.entity.RefundCompensation;
import com.aiconnecting.repository.RefundCompensationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 退款失败补偿记录：写库失败绝不影响主流程（退款失败已向上返回 false），仅尽力留下对账痕迹
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundCompensationService {

    private final RefundCompensationRepository refundCompensationRepository;

    @Transactional
    public void record(Long userId, BigDecimal amount, String scene, String cause) {
        try {
            refundCompensationRepository.save(RefundCompensation.builder()
                    .userId(userId)
                    .amount(amount)
                    .reason((scene != null ? scene : "unknown") + ": "
                            + (cause != null && cause.length() > 400 ? cause.substring(0, 400) : cause))
                    .resolved(false)
                    .build());
        } catch (Exception e) {
            log.error("退款补偿记录落库失败（MANUAL_COMPENSATION_REQUIRED，需人工核对）: userId={}, amount={}",
                    userId, amount, e);
        }
    }

    public List<RefundCompensation> listRecent() {
        return refundCompensationRepository.findTop200ByOrderByCreatedAtDesc();
    }
}
