package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.entity.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 渠道路由器 - 平滑加权轮询（SWRR，nginx upstream 算法）
 * 所有可用渠道共同参与选择，effectiveWeight = max(priority + 1, 1)。
 * 自动跳过熔断 OPEN、已尝试和不匹配用户等级的渠道。
 *
 * 性能优化：
 * - 封禁状态批量获取（1 次 Redis 调用代替 N 次）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChannelRouter {

    private final ChannelService channelService;
    private final CacheInvalidationService cacheInvalidationService;
    private final ObjectProvider<RiskManagerService> riskManagerProvider;

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
        private int tieBreaker = ThreadLocalRandom.current().nextInt();

        synchronized Map<Long, Integer> snapshot() {
            return new HashMap<>(currentWeights);
        }

        synchronized Channel select(List<Channel> channels) {
            int total = 0;
            Channel best = null;
            int bestWeight = Integer.MIN_VALUE;
            int size = channels.size();
            int start = Math.floorMod(tieBreaker++, size);
            for (int i = 0; i < size; i++) {
                Channel c = channels.get((start + i) % size);
                int effectiveWeight = Math.max((c.getPriority() != null ? c.getPriority() : 0) + 1, 1);
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
     * 使用 SWRR 选择一个渠道（排除已封禁和已尝试的渠道，按用户等级过滤）
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
                throw new BusinessException(403, "当前账号等级不支持该模型，请升级账号或联系管理员",
                        "Your account level does not support this model, please upgrade or contact admin");
            }
            channels = levelMatched;
        }

        // 获取风险管理熔断的渠道（2 秒本地缓存，避免热路径全表扫描）
        RiskManagerService riskManager = riskManagerProvider.getIfAvailable();
        Set<Long> riskFusedIds = riskManager != null ? riskManager.getFusedChannelIds() : Collections.emptySet();

        // 过滤：排除风险管理熔断和已尝试的渠道
        List<Channel> available = channels.stream()
                .filter(c -> !riskFusedIds.contains(c.getId())
                        || (riskManager != null && !riskManager.isChannelFusedForModelId(c.getId(), channelModelId)))
                .filter(c -> excludeIds == null || !excludeIds.contains(c.getId()))
                .toList();

        if (available.isEmpty()) {
            // OPEN 渠道必须保持 fast-fail；尤其不能让单渠道模型绕过渠道级熔断。
            throw new BusinessException(503, "该模型当前不可用，请稍后重试或更换模型",
                    "This model is currently unavailable, please try again later or use another model");
        }

        Channel selected = cached.swrrState().select(available);

        log.debug("SWRR 选择渠道: modelId={}, channel={}, priority={}, available={}/{}",
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
     * 从缓存的渠道列表中过滤出指定类型的渠道（排除封禁，按用户等级过滤）
     *
     * @param userLevel 用户等级 (1-5)，为 null 时不做等级过滤
     */
    public List<Channel> filterByType(String channelModelId, String type, Integer userLevel) {
        List<Channel> channels = getCachedChannelList(channelModelId).channels();
        RiskManagerService riskManager = riskManagerProvider.getIfAvailable();
        Set<Long> riskFusedIds = riskManager != null ? riskManager.getFusedChannelIds() : Collections.emptySet();
        return channels.stream()
                .filter(c -> !riskFusedIds.contains(c.getId())
                        || (riskManager != null && !riskManager.isChannelFusedForModelId(c.getId(), channelModelId)))
                .filter(c -> type.equalsIgnoreCase(c.getType()) || "anthropic".equalsIgnoreCase(c.getType()))
                .filter(c -> userLevel == null || channelSupportsLevel(c, userLevel))
                .toList();
    }

    /** Uses the same two-minute per-model cache as channel selection. */
    public boolean isPassthroughOnlyModel(String channelModelId) {
        List<Channel> channels = getCachedChannelList(channelModelId).channels();
        return !channels.isEmpty() && channels.stream()
                .allMatch(channel -> "custom".equalsIgnoreCase(channel.getType()));
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
        log.info("渠道路由缓存已清除");
    }

    @EventListener
    public void onCacheInvalidation(CacheInvalidationService.CacheInvalidationEvent event) {
        if (CacheInvalidationService.CHANNEL_LIST.equals(event.route())) {
            channelCache.clear();
            log.debug("收到广播，已清除渠道路由缓存");
        }
    }

    private CachedChannelList getCachedChannelList(String channelModelId) {
        while (true) {
            long generation = cacheInvalidationService.generation(CacheInvalidationService.CHANNEL_LIST);
            CachedChannelList cached = channelCache.compute(channelModelId, (ignored, existing) -> {
                if (existing != null && !existing.isExpired()) {
                    return existing;
                }
                List<Channel> channels = channelService.getActiveChannelsByModel(channelModelId);
                SwrrState state = existing != null ? existing.swrrState() : new SwrrState();
                log.debug("刷新渠道缓存: modelId={}, channelCount={}", channelModelId, channels.size());
                return new CachedChannelList(channels, System.currentTimeMillis(), state);
            });
            if (cacheInvalidationService.isCurrentGeneration(CacheInvalidationService.CHANNEL_LIST, generation)) {
                return cached;
            }
            channelCache.remove(channelModelId, cached);
        }
    }
}
