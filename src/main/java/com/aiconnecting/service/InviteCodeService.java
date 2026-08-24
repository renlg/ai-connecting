package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.DuplicateSubmitGuard;
import com.aiconnecting.entity.InviteCode;
import com.aiconnecting.entity.User;
import com.aiconnecting.repository.InviteCodeRepository;
import com.aiconnecting.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class InviteCodeService {

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 10;
    private static final int GENERATION_ATTEMPTS = 20;
    private static final int LEGACY_UNLIMITED_MAX_USES = Integer.MAX_VALUE;

    private final InviteCodeRepository inviteCodeRepository;
    private final UserRepository userRepository;
    private final DuplicateSubmitGuard duplicateSubmitGuard;
    private final SecureRandom secureRandom = new SecureRandom();

    public List<InviteCode> generate(User admin, Integer count, Integer maxUses, LocalDateTime expiryDate) {
        if (!"admin".equalsIgnoreCase(admin.getRole())) {
            throw new BusinessException("无权限创建邀请码", "Not authorized to create invitation codes");
        }
        int requestedCount = count != null ? count : 1;
        if (requestedCount < 1 || requestedCount > 100) {
            throw new BusinessException("单次生成数量必须为1到100", "Generation count must be between 1 and 100");
        }
        if (maxUses == null || maxUses < 1 || maxUses > 1_000_000) {
            throw new BusinessException("使用次数必须为1到1000000", "Maximum uses must be between 1 and 1000000");
        }
        if (expiryDate != null && !expiryDate.isAfter(LocalDateTime.now())) {
            throw new BusinessException("过期时间必须晚于当前时间", "Expiry time must be in the future");
        }

        List<InviteCode> generated = new ArrayList<>(requestedCount);
        for (int i = 0; i < requestedCount; i++) {
            generated.add(generateOne(admin.getId(), maxUses, expiryDate));
        }
        return generated;
    }

    private InviteCode generateOne(Long adminId, Integer maxUses, LocalDateTime expiryDate) {
        DataIntegrityViolationException lastCollision = null;
        for (int attempt = 0; attempt < GENERATION_ATTEMPTS; attempt++) {
            String code = randomCode();
            if (inviteCodeRepository.existsByCode(code)
                    || !duplicateSubmitGuard.tryAcquire("invite-code", code)) {
                continue;
            }
            try {
                return inviteCodeRepository.saveAndFlush(InviteCode.builder()
                        .code(code)
                        .maxUses(maxUses)
                        .expiryDate(expiryDate)
                        .createdBy(adminId)
                        .build());
            } catch (DataIntegrityViolationException e) {
                lastCollision = e;
            }
        }
        throw new BusinessException(400, "邀请码生成冲突，请重试",
                "Invitation code collision; retry", lastCollision);
    }

    public List<InviteCode> list() {
        return inviteCodeRepository.findAllOrderByCreatedAtDesc();
    }

    @Transactional
    public InviteCode updateStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException("状态值无效", "Invalid status");
        }
        InviteCode inviteCode = getById(id);
        inviteCode.setStatus(status);
        return inviteCodeRepository.save(inviteCode);
    }

    @Transactional
    public void delete(Long id) {
        InviteCode inviteCode = getById(id);
        inviteCodeRepository.delete(inviteCode);
    }

    /**
     * 在注册事务中原子消耗邀请码。用户保存失败时，本次次数递增也会随事务一起回滚。
     */
    @Transactional
    public void consume(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            throw new BusinessException("邀请码不能为空", "Invitation code cannot be empty");
        }
        String code = rawCode.trim().toUpperCase();
        LocalDateTime now = LocalDateTime.now();
        if (inviteCodeRepository.consume(code, now) > 0) {
            return;
        }

        InviteCode inviteCode = inviteCodeRepository.findByCode(code)
                .orElseThrow(() -> new BusinessException("邀请码无效", "Invalid invitation code"));
        if (!Integer.valueOf(1).equals(inviteCode.getStatus())) {
            throw new BusinessException("邀请码已被禁用", "Invitation code is disabled");
        }
        if (inviteCode.getExpiryDate() != null && !inviteCode.getExpiryDate().isAfter(now)) {
            throw new BusinessException("邀请码已过期", "Invitation code has expired");
        }
        throw new BusinessException("邀请码使用次数已耗尽", "Invitation code usage limit has been reached");
    }

    private InviteCode getById(Long id) {
        return inviteCodeRepository.findById(id)
                .orElseThrow(() -> new BusinessException("邀请码不存在", "Invitation code not found"));
    }

    private String randomCode() {
        StringBuilder value = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            value.append(CODE_CHARS.charAt(secureRandom.nextInt(CODE_CHARS.length())));
        }
        return value.toString();
    }

    /** 将旧版本管理员用户的邀请码迁移为不失效的管理邀请码，避免升级后原管理员码立即失效。 */
    @EventListener(ApplicationReadyEvent.class)
    public void migrateLegacyAdminCodes() {
        userRepository.findByRoleIgnoreCase("admin").stream()
                .filter(user -> user.getInviteCode() != null && !user.getInviteCode().isBlank())
                .forEach(user -> migrateLegacyAdminCode(user, user.getInviteCode().trim().toUpperCase()));
    }

    private void migrateLegacyAdminCode(User admin, String code) {
        if (inviteCodeRepository.existsByCode(code)) {
            return;
        }
        try {
            inviteCodeRepository.save(InviteCode.builder()
                    .code(code)
                    .maxUses(LEGACY_UNLIMITED_MAX_USES)
                    .createdBy(admin.getId())
                    .build());
            log.info("已迁移管理员 {} 的旧邀请码", admin.getUsername());
        } catch (DataIntegrityViolationException e) {
            if (!inviteCodeRepository.existsByCode(code)) {
                throw e;
            }
            log.debug("旧管理员邀请码已被其他实例迁移，跳过");
        }
    }
}
