package com.aiconnecting.service;

import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.entity.Channel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ChannelRouterPassthroughTest {

    @Test
    void passthroughOnlyCheckReusesPerModelCache() {
        ChannelService channels = mock(ChannelService.class);
        CacheInvalidationService invalidation = mock(CacheInvalidationService.class);
        when(invalidation.isCurrentGeneration(CacheInvalidationService.CHANNEL_LIST, 0L)).thenReturn(true);
        when(channels.getActiveChannelsByModel("7")).thenReturn(List.of(
                Channel.builder().id(1L).type("custom").modelIds("7").build()));
        ChannelRouter router = new ChannelRouter(channels, mock(ChannelHealthTracker.class), invalidation);

        assertTrue(router.isPassthroughOnlyModel("7"));
        assertTrue(router.isPassthroughOnlyModel("7"));

        verify(channels, times(1)).getActiveChannelsByModel("7");
    }
}
