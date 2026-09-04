package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.common.DuplicateSubmitGuard;
import com.aiconnecting.dto.TokenRequest;
import com.aiconnecting.entity.Token;
import com.aiconnecting.repository.TokenRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
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

    @Test
    void createStoresHashAndMaskAndReturnsPlainKeyOnce() {
        TokenRepository repository = mock(TokenRepository.class);
        DuplicateSubmitGuard duplicateSubmitGuard = mock(DuplicateSubmitGuard.class);
        CacheInvalidationService cacheInvalidationService = mock(CacheInvalidationService.class);
        AtomicReference<String> storedHash = new AtomicReference<>();
        AtomicReference<String> storedMask = new AtomicReference<>();
        when(duplicateSubmitGuard.tryAcquire(eq("token"), anyString())).thenReturn(true);
        when(repository.save(any(Token.class))).thenAnswer(invocation -> {
            Token token = invocation.getArgument(0);
            storedHash.set(token.getTokenKey());
            storedMask.set(token.getKeyMask());
            token.setId(42L);
            return token;
        });
        TokenService service = new TokenService(repository, cacheInvalidationService, duplicateSubmitGuard);

        Token created = service.create(9L, new TokenRequest());

        String plainKey = created.getPlainTokenKey();
        assertNotNull(plainKey);
        assertTrue(plainKey.startsWith("sk-"));
        assertEquals(TokenService.hashTokenKey(plainKey), storedHash.get());
        assertEquals(plainKey.substring(0, 7) + "****"
                + plainKey.substring(plainKey.length() - 4), storedMask.get());
        assertNotEquals(plainKey, created.getTokenKey());
        verify(cacheInvalidationService).publish(CacheInvalidationService.TOKEN_ID_PREFIX + 42L);
    }

    @Test
    void validateTokenKeyFallsBackToLegacyPlaintextRow() {
        TokenRepository repository = mock(TokenRepository.class);
        CacheInvalidationService cacheInvalidationService = mock(CacheInvalidationService.class);
        TokenService service = new TokenService(repository, cacheInvalidationService,
                mock(DuplicateSubmitGuard.class));
        String plainKey = "sk-legacy-token-key";
        Token legacy = Token.builder().id(7L).tokenKey(plainKey).status(1).build();
        when(repository.findByTokenKey(TokenService.hashTokenKey(plainKey))).thenReturn(Optional.empty());
        when(repository.findByTokenKey(plainKey)).thenReturn(Optional.of(legacy));

        assertSame(legacy, service.validateTokenKey(plainKey));

        verify(repository).findByTokenKey(TokenService.hashTokenKey(plainKey));
        verify(repository).findByTokenKey(plainKey);
    }

    @Test
    void storedHashCannotAuthenticateAsTokenKey() {
        TokenRepository repository = mock(TokenRepository.class);
        TokenService service = new TokenService(repository, mock(CacheInvalidationService.class),
                mock(DuplicateSubmitGuard.class));
        String storedHash = TokenService.hashTokenKey("sk-real-token-key");

        assertThrows(BusinessException.class, () -> service.validateTokenKey(storedHash));

        verifyNoInteractions(repository);
    }
}
