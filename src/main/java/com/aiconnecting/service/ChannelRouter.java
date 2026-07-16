package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.entity.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 渠道路由器 - 加权轮询策略
 * 按模型缓存渠道列表，根据渠道优先级进行加权选择
 * 自动跳过熔断 OPEN 的渠道；渠道是否可用完全由熔断器状态决定，
 * CLOSED 渠道之间仅按优先级区分权重（不再有动态健康权重）
 *
 * 性能优化：
 * - 封禁状态批量获取（1 次 Redis 调用代替 N 次）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChannelRouter {

    private final ChannelService channelService;
    private final ChannelHealthTracker healthTracker;

    /** 按模型缓存渠道列表，避免每次请求查库 */
    private final ConcurrentHashMap<String, CachedChannelList> channelCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 2 * 60 * 1000L; // 2 分钟

    private record CachedChannelList(List<Channel> channels, long cachedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > CACHE_TTL_MS;
        }
    }

    /**
     * 加权选择一个渠道（排除已封禁和已尝试的渠道，按用户等级过滤）
     *
     * @param channelModelId 模型ID（渠道中存储的格式）
     * @param excludeIds     需要排除的渠道 ID（已尝试过的）
     * @param userLevel      用户等级 (1-5)，为 null 时不做等级过滤
     * @return 选中的渠道
     * @throws BusinessException 如果没有可用渠道
     */
    public Channel selectChannel(String channelModelId, Set<Long> excludeIds, Integer userLevel) {
        List<Channel> channels = getCachedChannels(channelModelId);

        if (userLevel != null) {
            List<Channel> levelMatched = channels.stream()
                    .filter(c -> channelSupportsLevel(c, userLevel))
                    .toList();
            if (levelMatched.isEmpty()) {
                throw new BusinessException(403, "没有可用的渠道支持该用户等级");
            }
            channels = levelMatched;
        }

        // 批量获取封禁状态（1 次 Redis 调用，代替逐渠道查询）
        Set<Long> blockedIds = healthTracker.getBlockedChannelIds();

        // 过滤：排除被封禁和已尝试的渠道
        List<Channel> available = channels.stream()
                .filter(c -> !blockedIds.contains(c.getId()))
                .filter(c -> excludeIds == null || !excludeIds.contains(c.getId()))
                .toList();

        if (available.isEmpty()) {
            // 如果所有渠道都被封禁，降级使用未尝试的渠道（即使被封禁）
            List<Channel> fallback = channels.stream()
                    .filter(c -> excludeIds == null || !excludeIds.contains(c.getId()))
                    .toList();
            if (fallback.isEmpty()) {
                throw new BusinessException(503, "没有可用的渠道支持该模型，所有渠道均不可用");
            }
            log.warn("所有渠道均被封禁，降级使用被封禁渠道: modelId={}", channelModelId);
            available = fallback;
        }

        // 加权随机选择（按优先级）
        Channel selected = weightedSelect(available);

        // 若选中的渠道处于 HALF_OPEN（熔断探测期），需要获取探测许可，
        // 确保同一时刻只有 1 个请求作为探测流量打到该渠道
        Long halfOpenCandidateId = selected.getId();
        if (healthTracker.getEffectiveState(halfOpenCandidateId) == ChannelHealthTracker.CircuitState.HALF_OPEN
                && !healthTracker.tryAcquireHalfOpenProbe(halfOpenCandidateId)) {
            List<Channel> alternatives = available.stream()
                    .filter(c -> !c.getId().equals(halfOpenCandidateId))
                    .toList();
            if (!alternatives.isEmpty()) {
                selected = weightedSelect(alternatives);
            }
            // 若没有其他可选渠道，退化为仍使用该渠道（极端并发场景下的兜底）
        }

        log.debug("加权选择渠道: modelId={}, channel={}, priority={}, available={}/{}",
                channelModelId, selected.getId(),
                selected.getPriority(),
                available.size(), channels.size());
        return selected;
    }

    /**
     * 兼容旧接口：无用户等级过滤的加权选择
     *
     * @deprecated 不做用户等级过滤，可能绕过渠道等级限制；请使用 {@link #selectChannel(String, Set, Integer)}
     */
    @Deprecated
    public Channel selectChannel(String channelModelId, Set<Long> excludeIds) {
        return selectChannel(channelModelId, excludeIds, null);
    }

    /**
     * 判断渠道是否支持指定的用户等级
     */
    private boolean channelSupportsLevel(Channel channel, int userLevel) {
        String levels = channel.getSupportedLevels();
        if (levels == null || levels.isBlank()) {
            return true; // 未配置等级限制的渠道对所有等级开放
        }
        for (String part : levels.split(",")) {
            if (part.trim().equals(String.valueOf(userLevel))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 加权随机选择算法（健康权重 × 优先级，使用本地缓存权重，避免逐渠道 Redis 调用）
     */
    private Channel weightedSelect(List<Channel> channels) {
        if (channels.size() == 1) {
            return channels.get(0);
        }

        long totalWeight = 0;
        for (Channel c : channels) {
            totalWeight += getEffectiveWeight(c);
        }

        if (totalWeight <= 0) {
            return channels.get(ThreadLocalRandom.current().nextInt(channels.size()));
        }

        long random = ThreadLocalRandom.current().nextLong(totalWeight);
        long cumulative = 0;
        for (Channel c : channels) {
            cumulative += getEffectiveWeight(c);
            if (random < cumulative) {
                return c;
            }
        }
        return channels.get(channels.size() - 1);
    }

    /**
     * 计算渠道有效权重 = 优先级系数
     * priority 为渠道配置的优先级（数字越大越优先），最低为 0
     * 有效权重 = priority + 1（保证 priority=0 时权重为 1，不被完全忽略）
     * 渠道是否可用完全由熔断器状态决定，CLOSED 渠道之间不再有动态健康权重
     */
    private long getEffectiveWeight(Channel c) {
        return (c.getPriority() != null ? c.getPriority() : 0) + 1;
    }

    /**
     * 从缓存的渠道列表中过滤出指定类型的渠道（排除封禁，按用户等级过滤）
     *
     * @param userLevel 用户等级 (1-5)，为 null 时不做等级过滤
     */
    public List<Channel> filterByType(String channelModelId, String type, Integer userLevel) {
        List<Channel> channels = getCachedChannels(channelModelId);
        Set<Long> blockedIds = healthTracker.getBlockedChannelIds();
        return channels.stream()
                .filter(c -> !blockedIds.contains(c.getId()))
                .filter(c -> type.equalsIgnoreCase(c.getType()) || "anthropic".equalsIgnoreCase(c.getType()))
                .filter(c -> userLevel == null || channelSupportsLevel(c, userLevel))
                .toList();
    }

    /**
     * 清除渠道缓存（渠道配置变更时调用）
     */
    public void clearCache() {
        channelCache.clear();
        healthTracker.clearAll();
        log.info("渠道路由缓存已清除");
    }

    private List<Channel> getCachedChannels(String channelModelId) {
        CachedChannelList cached = channelCache.get(channelModelId);
        if (cached != null && !cached.isExpired()) {
            return cached.channels();
        }
        List<Channel> channels = channelService.getActiveChannelsByModel(channelModelId);
        channelCache.put(channelModelId, new CachedChannelList(channels, System.currentTimeMillis()));
        log.debug("刷新渠道缓存: modelId={}, channelCount={}", channelModelId, channels.size());
        return channels;
    }
}
