package com.aiconnecting.service;

import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.ChannelHealth;
import com.aiconnecting.repository.ChannelHealthRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 渠道健康看板服务 - 聚合熔断器状态与 SWRR 权重，供管理端展示
 */
@Service
@RequiredArgsConstructor
public class ChannelHealthService {

    private final ChannelService channelService;
    private final ChannelHealthTracker healthTracker;
    private final ChannelRouter channelRouter;
    private final ChannelHealthRepository channelHealthRepository;

    public List<Map<String, Object>> getAllChannelHealth() {
        List<Channel> channels = channelService.listAll();
        Map<Long, Integer> currentWeights = channelRouter.getCurrentWeightSnapshot();
        Map<Long, ChannelHealth> persisted = channelHealthRepository.findAll().stream()
                .collect(Collectors.toMap(ChannelHealth::getChannelId, Function.identity(), (a, b) -> a));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Channel channel : channels) {
            Long id = channel.getId();
            ChannelHealth dbHealth = persisted.get(id);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("channelId", id);
            item.put("name", channel.getName());
            item.put("circuitBreakerState", healthTracker.getEffectiveState(id).name());
            item.put("blockedUntil", toIso(healthTracker.getBlockedUntil(id)));
            item.put("currentWeight", currentWeights.getOrDefault(id, 0));
            item.put("effectiveWeight", (channel.getPriority() != null ? channel.getPriority() : 0) + 1);
            item.put("errorRate", healthTracker.getErrorRate1m(id));
            item.put("totalRequests1m", healthTracker.getTotalRequests1m(id));

            int probeFailures = healthTracker.getProbeFailures(id);
            item.put("probeFailures", probeFailures != 0 || dbHealth == null ? probeFailures : dbHealth.getProbeFailures());

            Long lastSuccessAt = healthTracker.getLastSuccessAt(id);
            item.put("lastSuccessAt", toIso(lastSuccessAt != null ? lastSuccessAt : (dbHealth != null ? dbHealth.getLastSuccessAt() : null)));

            Long lastFailureAt = healthTracker.getLastFailureAt(id);
            item.put("lastFailureAt", toIso(lastFailureAt != null ? lastFailureAt : (dbHealth != null ? dbHealth.getLastFailureAt() : null)));

            String lastFailureReason = healthTracker.getLastFailureReason(id);
            item.put("lastFailureReason", lastFailureReason != null ? lastFailureReason : (dbHealth != null ? dbHealth.getLastFailureReason() : null));

            result.add(item);
        }
        return result;
    }

    private LocalDateTime toIso(Long epochMs) {
        return epochMs == null ? null : LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
    }
}
