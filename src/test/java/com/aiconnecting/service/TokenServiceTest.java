package com.aiconnecting.service;

import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.dto.TokenRequest;
import com.aiconnecting.entity.Token;
import com.aiconnecting.repository.TokenRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TokenServiceTest {

    @Test
    void generatedTokenKeyIsCheckedBeforeInsertAndCollisionIsRetried() {
        TokenRepository repository = mock(TokenRepository.class);
        when(repository.findByTokenKey(anyString()))
                .thenReturn(Optional.of(Token.builder().id(1L).tokenKey("collision").build()))
                .thenReturn(Optional.empty());
        when(repository.save(any(Token.class))).thenAnswer(invocation -> invocation.getArgument(0));
        TokenService service = new TokenService(repository, mock(CacheInvalidationService.class));

        Token saved = service.create(9L, new TokenRequest());

        assertNotNull(saved.getTokenKey());
        verify(repository, times(2)).findByTokenKey(anyString());
        verify(repository).save(saved);
    }
}
