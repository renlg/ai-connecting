package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.common.DuplicateSubmitGuard;
import com.aiconnecting.entity.InviteCode;
import com.aiconnecting.entity.User;
import com.aiconnecting.repository.InviteCodeRepository;
import com.aiconnecting.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class InviteCodeServiceTest {

    private InviteCodeRepository inviteCodeRepository;
    private UserRepository userRepository;
    private DuplicateSubmitGuard duplicateSubmitGuard;
    private CacheInvalidationService cacheInvalidationService;
    private TransactionTemplate transactionTemplate;
    private InviteCodeService service;

    @BeforeEach
    void setUp() {
        inviteCodeRepository = mock(InviteCodeRepository.class);
        userRepository = mock(UserRepository.class);
        duplicateSubmitGuard = mock(DuplicateSubmitGuard.class);
        cacheInvalidationService = mock(CacheInvalidationService.class);
        transactionTemplate = mock(TransactionTemplate.class);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        runTransactionCallbacksImmediately();
        service = new InviteCodeService(
                inviteCodeRepository, userRepository, duplicateSubmitGuard, cacheInvalidationService,
                transactionTemplate);
    }

    @Test
    void migratedLegacyCodeDoesNotReturnAfterInviteCodeIsDeletedAndMigrationReruns() {
        User admin = adminWithLegacyCode("legacy-code");
        when(userRepository.findByRoleIgnoreCase("admin")).thenReturn(List.of(admin));
        when(inviteCodeRepository.existsByCode("LEGACY-CODE")).thenReturn(false);
        when(inviteCodeRepository.save(any(InviteCode.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.migrateLegacyAdminCodes();
        // Simulate deleting the migrated invite-code row before the next application start.
        service.migrateLegacyAdminCodes();

        assertNull(admin.getInviteCode());
        verify(inviteCodeRepository, times(1)).save(any(InviteCode.class));
        verify(userRepository, times(1)).save(admin);
        verify(cacheInvalidationService).publish(CacheInvalidationService.USER_PREFIX + admin.getId());
    }

    @Test
    void existingMigratedCodeBackfillsMarkerWithoutCreatingDuplicate() {
        User admin = adminWithLegacyCode("legacy-code");
        when(userRepository.findByRoleIgnoreCase("admin")).thenReturn(List.of(admin));
        when(inviteCodeRepository.existsByCode("LEGACY-CODE")).thenReturn(true);

        service.migrateLegacyAdminCodes();

        assertNull(admin.getInviteCode());
        verify(inviteCodeRepository, never()).save(any());
        verify(userRepository).save(admin);
        verify(cacheInvalidationService).publish(CacheInvalidationService.USER_PREFIX + admin.getId());
    }

    @Test
    void adminWithMigrationMarkerIsSkipped() {
        User admin = adminWithLegacyCode(null);
        when(userRepository.findByRoleIgnoreCase("admin")).thenReturn(List.of(admin));

        service.migrateLegacyAdminCodes();

        verifyNoInteractions(inviteCodeRepository);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(cacheInvalidationService);
    }

    @Test
    void generateRejectsCountOutsideAllowedRange() {
        User admin = adminWithLegacyCode(null);

        assertBusinessFailure(() -> service.generate(admin, 0, 1, null),
                "单次生成数量必须为1到100");
        assertBusinessFailure(() -> service.generate(admin, 101, 1, null),
                "单次生成数量必须为1到100");

        verifyNoInteractions(inviteCodeRepository, duplicateSubmitGuard);
    }

    @Test
    void generateRejectsMaximumUsesOutsideAllowedRange() {
        User admin = adminWithLegacyCode(null);

        assertBusinessFailure(() -> service.generate(admin, 1, null, null),
                "使用次数必须为1到1000000");
        assertBusinessFailure(() -> service.generate(admin, 1, 0, null),
                "使用次数必须为1到1000000");
        assertBusinessFailure(() -> service.generate(admin, 1, 1_000_001, null),
                "使用次数必须为1到1000000");

        verifyNoInteractions(inviteCodeRepository, duplicateSubmitGuard);
    }

    @Test
    void generateRejectsExpiryThatIsNotInFuture() {
        User admin = adminWithLegacyCode(null);

        assertBusinessFailure(() -> service.generate(admin, 1, 1, LocalDateTime.now().minusSeconds(1)),
                "过期时间必须晚于当前时间");

        verifyNoInteractions(inviteCodeRepository, duplicateSubmitGuard);
    }

    @Test
    void generateAcceptsBoundaryValues() {
        User admin = adminWithLegacyCode(null);
        when(duplicateSubmitGuard.tryAcquire(eq("invite-code"), any())).thenReturn(true);
        when(inviteCodeRepository.saveAndFlush(any(InviteCode.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<InviteCode> generated = service.generate(
                admin, 1, 1_000_000, LocalDateTime.now().plusDays(1));

        assertEquals(1, generated.size());
        assertEquals(1_000_000, generated.get(0).getMaxUses());
        verify(inviteCodeRepository).saveAndFlush(any(InviteCode.class));
    }

    @Test
    void consumeAcceptsValidCodeAndNormalizesInput() {
        when(inviteCodeRepository.consume(eq("VALID-CODE"), any(LocalDateTime.class))).thenReturn(1);

        assertDoesNotThrow(() -> service.consume(" valid-code "));

        verify(inviteCodeRepository).consume(eq("VALID-CODE"), any(LocalDateTime.class));
        verify(inviteCodeRepository, never()).findByCode(any());
    }

    @Test
    void consumeRejectsInvalidCode() {
        when(inviteCodeRepository.consume(eq("MISSING"), any(LocalDateTime.class))).thenReturn(0);
        when(inviteCodeRepository.findByCode("MISSING")).thenReturn(Optional.empty());

        assertBusinessFailure(() -> service.consume("missing"), "邀请码无效");
    }

    @Test
    void consumeRejectsDisabledCode() {
        arrangeRejectedConsume(InviteCode.builder()
                .code("DISABLED").status(0).maxUses(1).usedCount(0).createdBy(1L).build());

        assertBusinessFailure(() -> service.consume("disabled"), "邀请码已被禁用");
    }

    @Test
    void consumeRejectsExpiredCode() {
        arrangeRejectedConsume(InviteCode.builder()
                .code("EXPIRED").status(1).maxUses(1).usedCount(0)
                .expiryDate(LocalDateTime.now().minusDays(1)).createdBy(1L).build());

        assertBusinessFailure(() -> service.consume("expired"), "邀请码已过期");
    }

    @Test
    void consumeRejectsExhaustedCode() {
        arrangeRejectedConsume(InviteCode.builder()
                .code("EXHAUSTED").status(1).maxUses(1).usedCount(1)
                .expiryDate(LocalDateTime.now().plusDays(1)).createdBy(1L).build());

        assertBusinessFailure(() -> service.consume("exhausted"), "邀请码使用次数已耗尽");
    }

    @SuppressWarnings("unchecked")
    private void runTransactionCallbacksImmediately() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<List<Long>> callback = invocation.getArgument(0);
            long publishesBeforeTransaction = mockingDetails(cacheInvalidationService).getInvocations().stream()
                    .filter(call -> call.getMethod().getName().equals("publish"))
                    .count();
            List<Long> result = callback.doInTransaction(mock(TransactionStatus.class));
            long publishesAfterTransaction = mockingDetails(cacheInvalidationService).getInvocations().stream()
                    .filter(call -> call.getMethod().getName().equals("publish"))
                    .count();
            assertEquals(publishesBeforeTransaction, publishesAfterTransaction);
            return result;
        });
    }

    private void arrangeRejectedConsume(InviteCode inviteCode) {
        when(inviteCodeRepository.consume(eq(inviteCode.getCode()), any(LocalDateTime.class))).thenReturn(0);
        when(inviteCodeRepository.findByCode(inviteCode.getCode())).thenReturn(Optional.of(inviteCode));
    }

    private void assertBusinessFailure(Runnable action, String message) {
        BusinessException exception = assertThrows(BusinessException.class, action::run);
        assertEquals(message, exception.getMessage());
    }

    private User adminWithLegacyCode(String code) {
        return User.builder()
                .id(1L)
                .username("admin")
                .role("admin")
                .inviteCode(code)
                .build();
    }
}
