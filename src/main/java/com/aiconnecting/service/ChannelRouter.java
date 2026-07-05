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
 * 按模型缓存渠道列表，根据渠道权重进行加权选择
 * 自动跳过被封禁的渠道
 *
 * 性能优化：
 * - 封禁状态批量获取（1 次 Redis 调用代替 N 次）
 * - 权重本地缓存（30 秒刷新，避免每次请求访问 Redis）
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

    /** 权重本地缓存，避免每次请求都查 Redis */
    private final ConcurrentHashMap<Long, Long> weightCache = new ConcurrentHashMap<>();
    private volatile long weightCacheRefreshAt = 0;
    private static final long WEIGHT_CACHE_TTL_MS = 15 * 1000L; // 15 秒

    private record CachedChannelList(List<Channel> channels, long cachedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > CACHE_TTL_MS;
        }
    }

    /**
     * 加权选择一个渠道（排除已封禁和已尝试的渠道）
     *
     * @param channelModelId 模型ID（渠道中存储的格式）
     * @param excludeIds     需要排除的渠道 ID（已尝试过的）
     * @return 选中的渠道
     * @throws BusinessException 如果没有可用渠道
     */
    public Channel selectChannel(String channelModelId, Set<Long> excludeIds) {
        List<Channel> channels = getCachedChannels(channelModelId);

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

        // 加权随机选择（使用本地缓存的权重）
        Channel selected = weightedSelect(available);
        log.debug("加权选择渠道: modelId={}, channel={}, weight={}, available={}/{}",
                channelModelId, selected.getId(),
                getCachedWeight(selected.getId()),
                available.size(), channels.size());
        return selected;
    }

    /**
     * 兼容旧接口：无排除的加权选择
     */
    public Channel selectChannel(String channelModelId) {
        return selectChannel(channelModelId, null);
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
     * 计算渠道有效权重 = 健康权重 × 优先级系数
     * priority 为渠道配置的优先级（数字越大越优先），最低为 0
     * 优先级系数 = priority + 1（保证 priority=0 时系数为 1，不被完全忽略）
     */
    private long getEffectiveWeight(Channel c) {
        long healthWeight = getCachedWeight(c.getId());
        int priorityCoeff = (c.getPriority() != null ? c.getPriority() : 0) + 1;
        return healthWeight * priorityCoeff;
    }

    /**
     * 获取渠道权重（优先本地缓存，定期刷新）
     */
    private long getCachedWeight(Long channelId) {
        refreshWeightCacheIfNeeded();
        Long cached = weightCache.get(channelId);
        return cached != null ? cached : 100L; // 默认权重 100
    }

    /**
     * 定期刷新权重缓存（30 秒一次，从 Redis 批量拉取）
     */
    private void refreshWeightCacheIfNeeded() {
        if (System.currentTimeMillis() < weightCacheRefreshAt) {
            return;
        }
        weightCacheRefreshAt = System.currentTimeMillis() + WEIGHT_CACHE_TTL_MS;
        // 仅对已缓存的渠道刷新权重，避免拉取全量
        for (Long channelId : new ArrayList<>(weightCache.keySet())) {
            try {
                weightCache.put(channelId, healthTracker.getWeight(channelId));
            } catch (Exception e) {
                log.debug("刷新渠道 {} 权重缓存失败: {}", channelId, e.getMessage());
            }
        }
    }

    /**
     * 从缓存的渠道列表中过滤出指定类型的渠道（排除封禁）
     */
    public List<Channel> filterByType(String channelModelId, String type) {
        List<Channel> channels = getCachedChannels(channelModelId);
        Set<Long> blockedIds = healthTracker.getBlockedChannelIds();
        return channels.stream()
                .filter(c -> !blockedIds.contains(c.getId()))
                .filter(c -> type.equalsIgnoreCase(c.getType()) || "anthropic".equalsIgnoreCase(c.getType()))
                .toList();
    }

    /**
     * 清除渠道缓存（渠道配置变更时调用）
     */
    public void clearCache() {
        channelCache.clear();
        weightCache.clear();
        weightCacheRefreshAt = 0;
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
        // 初始化新渠道的权重缓存
        for (Channel c : channels) {
            weightCache.putIfAbsent(c.getId(), healthTracker.getWeight(c.getId()));
        }
        log.debug("刷新渠道缓存: modelId={}, channelCount={}", channelModelId, channels.size());
        return channels;
    }
}
