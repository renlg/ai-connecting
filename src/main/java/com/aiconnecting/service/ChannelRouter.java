package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.entity.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 渠道路由器 - 两阶段优先级分组 + 组内平滑加权轮询（SWRR，nginx upstream 算法）
 * 按模型缓存渠道列表：
 *  阶段一：按 priority 升序分组（数值越低优先级越高），仅在最高优先级且未耗尽的分组内选择
 *  阶段二：分组内使用 SWRR 选择渠道，effectiveWeight = priority + 1，在分组存活期间保持不变
 * 自动跳过熔断 OPEN 的渠道；当前分组全部不可用时（全部封禁/已尝试）降级到下一优先级分组（tier failover）
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

    /** 按模型缓存渠道列表及其 SWRR 状态，避免每次请求查库 */
    private final ConcurrentHashMap<String, CachedChannelList> channelCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 2 * 60 * 1000L; // 2 分钟

    private record CachedChannelList(List<Channel> channels, long cachedAt, SwrrState swrrState) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > CACHE_TTL_MS;
        }
    }

    /**
     * 平滑加权轮询（SWRR）状态：per-model，非跨 JVM 共享（每个实例独立轮询，无需 Redis）
     */
    private static class SwrrState {
        private final Map<Long, Integer> currentWeights = new HashMap<>();

        synchronized Map<Long, Integer> snapshot() {
            return new HashMap<>(currentWeights);
        }

        synchronized Channel select(List<Channel> channels) {
            int total = 0;
            Channel best = null;
            int bestWeight = Integer.MIN_VALUE;
            for (Channel c : channels) {
                int effectiveWeight = (c.getPriority() != null ? c.getPriority() : 0) + 1;
                int cw = currentWeights.merge(c.getId(), effectiveWeight, Integer::sum);
                total += effectiveWeight;
                if (cw > bestWeight) {
                    bestWeight = cw;
                    best = c;
                }
            }
            currentWeights.merge(best.getId(), -total, Integer::sum);
            return best;
        }
    }

    /**
     * 两阶段优先级分组 + 组内 SWRR 选择一个渠道（排除已封禁和已尝试的渠道，按用户等级过滤）
     *
     * @param channelModelId 模型ID（渠道中存储的格式）
     * @param excludeIds     需要排除的渠道 ID（已尝试过的）
     * @param userLevel      用户等级 (1-5)，为 null 时不做等级过滤
     * @return 选中的渠道
     * @throws BusinessException 如果没有可用渠道
     */
    public Channel selectChannel(String channelModelId, Set<Long> excludeIds, Integer userLevel) {
        CachedChannelList cached = getCachedChannelList(channelModelId);
        List<Channel> channels = cached.channels();

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

        // 阶段一：按 priority 升序分组
        TreeMap<Integer, List<Channel>> allTiers = channels.stream()
                .collect(Collectors.groupingBy(this::tierOf, TreeMap::new, Collectors.toList()));
        TreeMap<Integer, List<Channel>> availableTiers = available.stream()
                .collect(Collectors.groupingBy(this::tierOf, TreeMap::new, Collectors.toList()));

        Channel selected = null;
        List<Channel> selectedTierChannels = null;
        for (Integer tier : allTiers.keySet()) {
            List<Channel> tierChannels = availableTiers.get(tier);
            if (tierChannels == null || tierChannels.isEmpty()) {
                Integer nextTier = allTiers.tailMap(tier, false).keySet().stream().findFirst().orElse(null);
                log.warn("Tier {} exhausted for model {}, falling back to tier {}",
                        tier, channelModelId, nextTier != null ? nextTier : "none");
                continue;
            }
            // 阶段二：组内 SWRR 选择
            selected = cached.swrrState().select(tierChannels);
            selectedTierChannels = tierChannels;
            break;
        }

        if (selected == null) {
            throw new BusinessException(503, "没有可用的渠道支持该模型，所有渠道均不可用");
        }

        // 若选中的渠道处于 HALF_OPEN（熔断探测期），需要获取探测许可，
        // 确保同一时刻只有 1 个请求作为探测流量打到该渠道
        Long halfOpenCandidateId = selected.getId();
        if (healthTracker.getEffectiveState(halfOpenCandidateId) == ChannelHealthTracker.CircuitState.HALF_OPEN
                && !healthTracker.tryAcquireHalfOpenProbe(halfOpenCandidateId)) {
            List<Channel> alternatives = selectedTierChannels.stream()
                    .filter(c -> !c.getId().equals(halfOpenCandidateId))
                    .toList();
            if (!alternatives.isEmpty()) {
                selected = cached.swrrState().select(alternatives);
            }
            // 若没有其他可选渠道，退化为仍使用该渠道（极端并发场景下的兜底）
        }

        log.debug("SWRR 选择渠道: modelId={}, channel={}, priority={}, available={}/{}",
                channelModelId, selected.getId(),
                selected.getPriority(),
                available.size(), channels.size());
        return selected;
    }

    private int tierOf(Channel c) {
        return c.getPriority() != null ? c.getPriority() : 0;
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
     * 从缓存的渠道列表中过滤出指定类型的渠道（排除封禁，按用户等级过滤）
     *
     * @param userLevel 用户等级 (1-5)，为 null 时不做等级过滤
     */
    public List<Channel> filterByType(String channelModelId, String type, Integer userLevel) {
        List<Channel> channels = getCachedChannelList(channelModelId).channels();
        Set<Long> blockedIds = healthTracker.getBlockedChannelIds();
        return channels.stream()
                .filter(c -> !blockedIds.contains(c.getId()))
                .filter(c -> type.equalsIgnoreCase(c.getType()) || "anthropic".equalsIgnoreCase(c.getType()))
                .filter(c -> userLevel == null || channelSupportsLevel(c, userLevel))
                .toList();
    }

    /**
     * 获取所有已缓存渠道的当前 SWRR currentWeight 快照（供健康看板展示，非跨实例聚合）
     */
    public Map<Long, Integer> getCurrentWeightSnapshot() {
        Map<Long, Integer> result = new HashMap<>();
        for (CachedChannelList cached : channelCache.values()) {
            if (!cached.isExpired()) {
                result.putAll(cached.swrrState().snapshot());
            }
        }
        return result;
    }

    /**
     * 清除渠道缓存（渠道配置变更时调用），同时重置 SWRR 状态
     */
    public void clearCache() {
        channelCache.clear();
        healthTracker.clearAll();
        log.info("渠道路由缓存已清除");
    }

    private CachedChannelList getCachedChannelList(String channelModelId) {
        CachedChannelList cached = channelCache.get(channelModelId);
        if (cached != null && !cached.isExpired()) {
            return cached;
        }
        List<Channel> channels = channelService.getActiveChannelsByModel(channelModelId);
        CachedChannelList fresh = new CachedChannelList(channels, System.currentTimeMillis(), new SwrrState());
        channelCache.put(channelModelId, fresh);
        log.debug("刷新渠道缓存: modelId={}, channelCount={}", channelModelId, channels.size());
        return fresh;
    }
}
