package com.aiconnecting.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DuplicateSubmitGuardTest {

    @Test
    void usesNamespacedSetNxWithThirtySecondTtl() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                "ai-connecting:prod:dedup:channel:test", "1", DuplicateSubmitGuard.DEDUP_TTL))
                .thenReturn(true, false);
        DuplicateSubmitGuard guard = new DuplicateSubmitGuard(provider, "prod");

        assertTrue(guard.tryAcquire("channel", "test"));
        assertFalse(guard.tryAcquire("channel", "test"));

        verify(valueOperations, org.mockito.Mockito.times(2)).setIfAbsent(
                "ai-connecting:prod:dedup:channel:test", "1", DuplicateSubmitGuard.DEDUP_TTL);
    }

    @Test
    void failsOpenWhenRedisIsDisabledOrUnavailable() {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> disabledProvider = mock(ObjectProvider.class);
        assertTrue(new DuplicateSubmitGuard(disabledProvider, "local")
                .tryAcquire("announcement", "notice"));

        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> unavailableProvider = mock(ObjectProvider.class);
        when(unavailableProvider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis unavailable"));

        assertTrue(new DuplicateSubmitGuard(unavailableProvider, "local")
                .tryAcquire("announcement", "notice"));
    }
}
