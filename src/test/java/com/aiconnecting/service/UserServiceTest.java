package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.common.RedisDistributedLock;
import com.aiconnecting.dto.RegisterRequest;
import com.aiconnecting.entity.User;
import com.aiconnecting.repository.UserRepository;
import com.aiconnecting.security.JwtAuthenticationFilter;
import com.aiconnecting.security.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtUtils jwtUtils;
    @Mock private JwtAuthenticationFilter jwtAuthenticationFilter;
    @Mock private CacheInvalidationService cacheInvalidationService;
    @Mock private RedisDistributedLock distributedLock;

    @InjectMocks private UserService userService;

    @BeforeEach
    void setUp() {
        lenient().when(passwordEncoder.encode(any())).thenReturn("encoded");
        lenient().when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void adminInviteCodeCanBeUsedUnlimitedTimesRegardlessOfLevel() {
        User admin = inviter(1L, 2, "admin", "ADMIN123");
        when(userRepository.findByInviteCode("ADMIN123")).thenReturn(Optional.of(admin));

        userService.register(request("first", "ADMIN123"));
        userService.register(request("second", "ADMIN123"));

        assertFalse(Boolean.TRUE.equals(admin.getInviteCodeUsed()));
        verify(userRepository, times(2)).findByInviteCode("ADMIN123");
        verify(userRepository, never()).consumeInviteCode(any());
    }

    @Test
    void regularInviteCodeIsConsumedAfterFirstSuccessfulRegistration() {
        User regular = inviter(2L, 1, "USER1234");
        when(userRepository.findByInviteCode("USER1234")).thenReturn(Optional.of(regular));
        when(userRepository.consumeInviteCode(2L)).thenReturn(1, 0);

        userService.register(request("first", "USER1234"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userService.register(request("second", "USER1234")));
        assertEquals("邀请码已被使用", exception.getMessage());
        verify(userRepository, times(2)).consumeInviteCode(2L);
    }

    @Test
    void consumedFlagDoesNotRestrictAdminInviteCode() {
        User admin = inviter(1L, 2, "admin", "ADMIN123");
        admin.setInviteCodeUsed(true);
        when(userRepository.findByInviteCode("ADMIN123")).thenReturn(Optional.of(admin));

        assertDoesNotThrow(() -> userService.register(request("newuser", "ADMIN123")));
    }

    @Test
    void levelFiveRegularUserInviteIsStillSingleUse() {
        User regular = inviter(3L, 5, "user", "LEVEL5");
        when(userRepository.findByInviteCode("LEVEL5")).thenReturn(Optional.of(regular));
        when(userRepository.consumeInviteCode(3L)).thenReturn(1);

        userService.register(request("first", "LEVEL5"));

        verify(userRepository).consumeInviteCode(3L);
    }

    @Test
    void disabledInviterIsRejectedBeforeConsumedFlag() {
        User disabled = inviter(4L, 1, "user", "DISABLED");
        disabled.setStatus(0);
        disabled.setInviteCodeUsed(true);
        when(userRepository.findByInviteCode("DISABLED")).thenReturn(Optional.of(disabled));

        BusinessException error = assertThrows(BusinessException.class,
                () -> userService.register(request("newuser", "DISABLED")));

        assertEquals("邀请码无效", error.getMessage());
        verify(userRepository, never()).consumeInviteCode(any());
    }

    private User inviter(Long id, int level, String code) {
        return inviter(id, level, level == 5 ? "admin" : "user", code);
    }

    private User inviter(Long id, int level, String role, String code) {
        return User.builder()
                .id(id)
                .username("inviter" + id)
                .password("encoded")
                .role(role)
                .status(1)
                .level(level)
                .inviteCode(code)
                .inviteCodeUsed(false)
                .build();
    }

    private RegisterRequest request(String username, String code) {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setPassword("password123");
        request.setEmail(username + "@example.com");
        request.setInviteCode(code);
        return request;
    }
}
