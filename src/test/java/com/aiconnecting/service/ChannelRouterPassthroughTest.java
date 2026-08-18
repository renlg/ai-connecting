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
        ChannelRouter router = new ChannelRouter(channels, invalidation, nullRiskManager());

        assertTrue(router.isPassthroughOnlyModel("7"));
        assertTrue(router.isPassthroughOnlyModel("7"));

        verify(channels, times(1)).getActiveChannelsByModel("7");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RiskManagerService> nullRiskManager() {
        ObjectProvider<RiskManagerService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
