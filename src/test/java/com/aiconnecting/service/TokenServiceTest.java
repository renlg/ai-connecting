package com.aiconnecting.service;

import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.common.DuplicateSubmitGuard;
import com.aiconnecting.dto.TokenRequest;
import com.aiconnecting.entity.Token;
import com.aiconnecting.repository.TokenRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TokenServiceTest {

    @Test
    void generatedTokenKeyIsGuardedInRedisAndCollisionIsRetried() {
        TokenRepository repository = mock(TokenRepository.class);
        DuplicateSubmitGuard duplicateSubmitGuard = mock(DuplicateSubmitGuard.class);
        when(duplicateSubmitGuard.tryAcquire(eq("token"), anyString())).thenReturn(false, true);
        when(repository.save(any(Token.class))).thenAnswer(invocation -> invocation.getArgument(0));
        TokenService service = new TokenService(repository, mock(CacheInvalidationService.class),
                duplicateSubmitGuard);

        Token saved = service.create(9L, new TokenRequest());

        assertNotNull(saved.getTokenKey());
        verify(duplicateSubmitGuard, times(2)).tryAcquire(eq("token"), anyString());
        verify(repository, never()).findByTokenKey(anyString());
        verify(repository).save(saved);
    }
}
