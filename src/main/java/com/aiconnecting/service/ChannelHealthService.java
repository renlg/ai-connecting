package com.aiconnecting.service;

import com.aiconnecting.entity.Channel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 渠道健康看板服务 - 聚合熔断器状态与 SWRR 权重，供管理端展示
 */
@Service
@RequiredArgsConstructor
public class ChannelHealthService {

    private final ChannelService channelService;
    private final ChannelHealthTracker healthTracker;
    private final ChannelRouter channelRouter;

    public List<Map<String, Object>> getAllChannelHealth() {
        List<Channel> channels = channelService.listAll();
        Map<Long, Integer> currentWeights = channelRouter.getCurrentWeightSnapshot();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Channel channel : channels) {
            Long id = channel.getId();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("channelId", id);
            item.put("name", channel.getName());
            item.put("circuitBreakerState", healthTracker.getEffectiveState(id).name());
            item.put("blockedUntil", toIso(healthTracker.getBlockedUntil(id)));
            item.put("currentWeight", currentWeights.getOrDefault(id, 0));
            item.put("effectiveWeight", (channel.getPriority() != null ? channel.getPriority() : 0) + 1);
            item.put("errorRate", healthTracker.getErrorRate1m(id));
            item.put("totalRequests1m", healthTracker.getTotalRequests1m(id));
            item.put("probeFailures", healthTracker.getProbeFailures(id));
            item.put("lastSuccessAt", toIso(healthTracker.getLastSuccessAt(id)));
            item.put("lastFailureAt", toIso(healthTracker.getLastFailureAt(id)));
            item.put("lastFailureReason", healthTracker.getLastFailureReason(id));
            result.add(item);
        }
        return result;
    }

    private LocalDateTime toIso(Long epochMs) {
        return epochMs == null ? null : LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
    }
}
