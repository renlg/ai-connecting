package com.aiconnecting.service;

import com.aiconnecting.common.DuplicateSubmitGuard;
import com.aiconnecting.entity.InviteCode;
import com.aiconnecting.entity.User;
import com.aiconnecting.repository.InviteCodeRepository;
import com.aiconnecting.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InviteCodeServiceTest {

    private InviteCodeRepository inviteCodeRepository;
    private UserRepository userRepository;
    private InviteCodeService service;

    @BeforeEach
    void setUp() {
        inviteCodeRepository = mock(InviteCodeRepository.class);
        userRepository = mock(UserRepository.class);
        service = new InviteCodeService(
                inviteCodeRepository, userRepository, mock(DuplicateSubmitGuard.class));
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
    }

    @Test
    void adminWithMigrationMarkerIsSkipped() {
        User admin = adminWithLegacyCode(null);
        when(userRepository.findByRoleIgnoreCase("admin")).thenReturn(List.of(admin));

        service.migrateLegacyAdminCodes();

        verifyNoInteractions(inviteCodeRepository);
        verify(userRepository, never()).save(any());
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
