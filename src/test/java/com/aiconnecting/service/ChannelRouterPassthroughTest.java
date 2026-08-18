package com.aiconnecting.service;

import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.entity.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChannelRouterPassthroughTest {

    @Test
    void passthroughOnlyCheckReusesPerModelCache() {
        ChannelService channels = mock(ChannelService.class);
        CacheInvalidationService invalidation = mock(CacheInvalidationService.class);
        when(invalidation.isCurrentGeneration(CacheInvalidationService.CHANNEL_LIST, 0L)).thenReturn(true);
        when(channels.getActiveChannelsByModel("7")).thenReturn(List.of(
                Channel.builder().id(1L).type("custom").modelIds("7").build()));
        ChannelRouter router = new ChannelRouter(channels, mock(ChannelHealthTracker.class), invalidation, nullRiskManager());

        assertTrue(router.isPassthroughOnlyModel("7"));
        assertTrue(router.isPassthroughOnlyModel("7"));

        verify(channels, times(1)).getActiveChannelsByModel("7");
    }

    @Test
    void selectChannelSkipsOpenChannelAndUsesHealthyAlternative() {
        ChannelService channels = mock(ChannelService.class);
        ChannelHealthTracker health = mock(ChannelHealthTracker.class);
        CacheInvalidationService invalidation = mock(CacheInvalidationService.class);
        when(invalidation.isCurrentGeneration(CacheInvalidationService.CHANNEL_LIST, 0L)).thenReturn(true);
        Channel blocked = Channel.builder().id(1L).priority(0).build();
        Channel healthy = Channel.builder().id(2L).priority(0).build();
        when(channels.getActiveChannelsByModel("7")).thenReturn(List.of(blocked, healthy));
        when(health.getBlockedChannelIds()).thenReturn(Set.of(1L));
        when(health.getEffectiveState(2L)).thenReturn(ChannelHealthTracker.CircuitState.CLOSED);
        ChannelRouter router = new ChannelRouter(channels, health, invalidation, nullRiskManager());

        assertEquals(2L, router.selectChannel("7", Set.of(), 1).getId());
    }

    @Test
    void selectChannelFastFailsWhenOnlyChannelIsOpen() {
        ChannelService channels = mock(ChannelService.class);
        ChannelHealthTracker health = mock(ChannelHealthTracker.class);
        CacheInvalidationService invalidation = mock(CacheInvalidationService.class);
        when(invalidation.isCurrentGeneration(CacheInvalidationService.CHANNEL_LIST, 0L)).thenReturn(true);
        when(channels.getActiveChannelsByModel("7")).thenReturn(List.of(
                Channel.builder().id(1L).priority(0).build()));
        when(health.getBlockedChannelIds()).thenReturn(Set.of(1L));
        ChannelRouter router = new ChannelRouter(channels, health, invalidation, nullRiskManager());

        com.aiconnecting.common.BusinessException error = assertThrows(
                com.aiconnecting.common.BusinessException.class,
                () -> router.selectChannel("7", Set.of(), 1));

        assertEquals(503, error.getCode());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RiskManagerService> nullRiskManager() {
        ObjectProvider<RiskManagerService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
