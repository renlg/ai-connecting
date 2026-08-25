package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.dto.RegisterRequest;
import com.aiconnecting.entity.User;
import com.aiconnecting.repository.UserRepository;
import com.aiconnecting.security.JwtAuthenticationFilter;
import com.aiconnecting.security.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
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
    @Mock private InviteCodeService inviteCodeService;

    @InjectMocks private UserService userService;

    @BeforeEach
    void setUp() {
        lenient().when(passwordEncoder.encode(any())).thenReturn("encoded");
        lenient().when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(userRepository.existsByUsername(any())).thenReturn(false);
    }

    @Test
    void registerConsumesAdminManagedInviteCode() {
        userService.register(request("first", "ADMIN123"));

        InOrder registration = inOrder(inviteCodeService, userRepository);
        registration.verify(inviteCodeService).consume("ADMIN123");
        registration.verify(userRepository).save(any(User.class));
    }

    @Test
    void registerSavesNewUserWithoutPersonalInviteCode() {
        userService.register(request("newuser", "CODE123"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertNull(captor.getValue().getInviteCode());
    }

    @Test
    void registerPropagatesConsumeFailure() {
        doThrow(new BusinessException("邀请码使用次数已耗尽", "Invitation code usage limit has been reached"))
                .when(inviteCodeService).consume("CODE123");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userService.register(request("newuser", "CODE123")));

        assertEquals("邀请码使用次数已耗尽", exception.getMessage());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(cacheInvalidationService);
    }

    @Test
    void registerPassesBlankInviteCodeToConsume() {
        userService.register(request("newuser", ""));

        verify(inviteCodeService).consume("");
    }

    @Test
    void registerRejectsDuplicateUsernameBeforeConsumingCode() {
        when(userRepository.existsByUsername("taken")).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userService.register(request("taken", "CODE123")));

        assertEquals("用户名已存在", exception.getMessage());
        verify(inviteCodeService, never()).consume(any());
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
