package com.aiconnecting.service;

import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.dto.DashboardStats;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.User;
import com.aiconnecting.repository.UsageLogRepository;
import com.aiconnecting.repository.UsageStatsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardService {

    /** 累计数据仅保留最近 90 天 */
    static final int CUMULATIVE_DAYS = 90;

    /** 仪表盘按模型类型拆分积分消耗时展示的固定类型集合 */
    private static final List<String> MODEL_TYPES = List.of("text", "image", "video", "audio");

    private final ChannelService channelService;
    private final ChannelHealthTracker channelHealthTracker;
    private final TokenService tokenService;
    private final UserService userService;
    private final UsageLogService usageLogService;
    private final UsageStatsRepository usageStatsRepository;
    private final UsageLogRepository usageLogRepository;

    /**
     * 仪表盘统计缓存，避免每次请求都跑 5 条 SQL，缓存 45 秒。
     * 按访问顺序的有界 LRU：超过上限时只淘汰最久未使用的一条，而非清空整个 Map，
     * 避免活跃 key 数量超过上限时因整体清空导致的周期性缓存穿透抖动。
     */
    private final Map<DashboardCacheKey, CachedDashboardStats> dashboardCache =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<DashboardCacheKey, CachedDashboardStats> eldest) {
                    return size() > DASHBOARD_CACHE_MAX_ENTRIES;
                }
            });
    private static final long DASHBOARD_CACHE_TTL_MS = 45 * 1000L;
    /** 缓存条目上限，超出后淘汰最久未使用的一条 */
    private static final int DASHBOARD_CACHE_MAX_ENTRIES = 1000;

    private record DashboardCacheKey(boolean isAdmin, Long userId, List<Long> tokenIds) {}

    private record CachedDashboardStats(DashboardStats stats, long cachedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > DASHBOARD_CACHE_TTL_MS;
        }
    }

    /**
     * 构建仪表盘统计数据 - admin 看全局（汇总表），普通用户看自己的数据（tokenIds 分片）
     */
    public DashboardStats buildDashboardStats(User currentUser) {
        boolean isAdmin = "admin".equalsIgnoreCase(currentUser.getRole());
        DashboardCacheKey cacheKey;
        if (isAdmin) {
            cacheKey = new DashboardCacheKey(true, null, null);
        } else {
            // 缓存内容包含 myCredits 等用户私有数据，key 必须绑定 userId，避免跨用户串数据
            List<Long> tokenIds = tokenService.getUserTokenIds(currentUser.getId()).stream()
                    .distinct().sorted().toList();
            cacheKey = new DashboardCacheKey(false, currentUser.getId(), tokenIds);
        }

        CachedDashboardStats cached = dashboardCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.stats();
        }

        DashboardStats stats = isAdmin ? buildAdminStats() : buildUserStats(currentUser);
        dashboardCache.put(cacheKey, new CachedDashboardStats(stats, System.currentTimeMillis()));
        return stats;
    }

    @EventListener
    public void onCacheInvalidation(CacheInvalidationService.CacheInvalidationEvent event) {
        String route = event.route();
        if (CacheInvalidationService.CHANNEL_LIST.equals(route)
                || CacheInvalidationService.MODEL_CONFIG.equals(route)
                || CacheInvalidationService.USAGE_STATS.equals(route)
                || route.startsWith(CacheInvalidationService.USER_PREFIX)
                || route.startsWith(CacheInvalidationService.TOKEN_PREFIX)
                || route.startsWith(CacheInvalidationService.TOKEN_ID_PREFIX)) {
            dashboardCache.clear();
        }
    }

    // ========================================================================
    // Admin 仪表盘：从 usage_stats 汇总表读取，当前不完整窗口从 usage_logs 补齐
    // ========================================================================

    private DashboardStats buildAdminStats() {
        List<Channel> channels = channelService.listAll();
        long activeChannels = channels.stream().filter(c -> c.getStatus() == 1).count();
        long blockedChannels = channelHealthTracker.getBlockedChannelIds().size();

        // 累计数据：汇总表（90 天内已完成窗口）+ 当前不完整窗口补齐
        LocalDateTime cutoff = LocalDate.now().minusDays(CUMULATIVE_DAYS).atStartOfDay();
        LocalDateTime lastCompleteWindow = StatsAggregationService.alignWindowEnd(LocalDateTime.now());

        Object[] statsCumulative = getCombinedStats(cutoff, lastCompleteWindow);
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        Object[] statsToday = getCombinedStats(todayStart, lastCompleteWindow);

        Map<String, BigDecimal> creditsByTypeToday = usageLogService.getCreditsByTypeSince(todayStart);

        return DashboardStats.builder()
                .totalChannels((long) channels.size())
                .activeChannels(activeChannels)
                .blockedChannels(blockedChannels)
                .totalTokens(tokenService.count())
                .totalUsers(userService.count())
                .totalRequests(longVal(statsCumulative[0]))
                .totalTokensUsed(longVal(statsCumulative[1]))
                .requestsToday(longVal(statsToday[0]))
                .tokensUsedToday(longVal(statsToday[1]))
                .totalInputTokens(longVal(statsCumulative[2]))
                .totalOutputTokens(longVal(statsCumulative[3]))
                .inputTokensToday(longVal(statsToday[2]))
                .outputTokensToday(longVal(statsToday[3]))
                .totalCreditsConsumed(bigDecVal(statsCumulative[4]))
                .creditsConsumedToday(bigDecVal(statsToday[4]))
                .creditsByTypeToday(creditsByTypeToday)
                .totalCachedPromptTokens(longVal(statsCumulative[5]))
                .cachedPromptTokensToday(longVal(statsToday[5]))
                .totalCacheCreationTokens(longVal(statsCumulative[6]))
                .totalCacheReadTokens(longVal(statsCumulative[7]))
                .todayCacheCreationTokens(longVal(statsToday[6]))
                .todayCacheReadTokens(longVal(statsToday[7]))
                .build();
    }

    /**
     * 合并 usage_stats 汇总表 + usage_logs 当前不完整窗口数据
     */
    private Object[] getCombinedStats(LocalDateTime since, LocalDateTime lastCompleteWindow) {
        // 1. 汇总表：已完成窗口的数据
        List<Object[]> summaryRows = usageStatsRepository.sumSinceTime(since);
        Object[] summary = summaryRows.isEmpty()
                ? emptyRow() : summaryRows.get(0);

        // 2. 当前不完整窗口：从最近一个对齐点到现在
        List<Object[]> liveRows = usageLogRepository.aggregateSince(lastCompleteWindow);
        Object[] live = liveRows.isEmpty()
                ? emptyRow() : liveRows.get(0);

        // 3. 逐字段相加
        return new Object[]{
                longVal(summary[0]) + longVal(live[0]),
                longVal(summary[1]) + longVal(live[1]),
                longVal(summary[2]) + longVal(live[2]),
                longVal(summary[3]) + longVal(live[3]),
                doubleVal(summary[4]) + doubleVal(live[4]),
                longVal(summary[5]) + longVal(live[5]),
                longVal(summary[6]) + longVal(live[6]),
                longVal(summary[7]) + longVal(live[7]),
        };
    }

    // ========================================================================
    // 普通用户仪表盘：按 tokenIds 分片查询（累计数据限 90 天）
    // ========================================================================

    private DashboardStats buildUserStats(User currentUser) {
        LocalDateTime cutoff = LocalDate.now().minusDays(CUMULATIVE_DAYS).atStartOfDay();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();

        List<Long> tokenIds = tokenService.getUserTokenIds(currentUser.getId());
        Object[] tokenStats = tokenService.getUserTokenStats(currentUser.getId());
        long tokenCount      = ((Number) tokenStats[0]).longValue();
        long totalTokensUsed = ((Number) tokenStats[1]).longValue();

        // 累计查询（90 天内）
        long totalRequests = 0, totalInputTokens = 0, totalOutputTokens = 0;
        long totalCachedPromptTokens = 0;
        BigDecimal totalCreditsConsumed = BigDecimal.ZERO;
        if (!tokenIds.isEmpty()) {
            Object[] m = usageLogService.sumAllMetricsByTokenIdsSince(tokenIds, cutoff);
            totalRequests         = longVal(m[0]);
            totalInputTokens      = longVal(m[2]);
            totalOutputTokens     = longVal(m[3]);
            totalCreditsConsumed  = bigDecVal(m[4]);
            totalCachedPromptTokens = longVal(m[5]);
        }

        // 今日查询
        long requestsToday = 0, tokensUsedToday = 0, inputTokensToday = 0, outputTokensToday = 0;
        long cachedPromptTokensToday = 0;
        BigDecimal creditsConsumedToday = BigDecimal.ZERO;
        Map<String, BigDecimal> creditsByTypeToday = defaultCreditsByType();
        if (!tokenIds.isEmpty()) {
            Object[] m = usageLogService.sumAllMetricsByTokenIdsSince(tokenIds, todayStart);
            requestsToday          = longVal(m[0]);
            tokensUsedToday       = longVal(m[1]);
            inputTokensToday       = longVal(m[2]);
            outputTokensToday      = longVal(m[3]);
            creditsConsumedToday   = bigDecVal(m[4]);
            cachedPromptTokensToday = longVal(m[5]);
            creditsByTypeToday = usageLogService.getCreditsByTypeByTokenIdsSince(tokenIds, todayStart);
        }

        // 缓存统计（同样 90 天限制）
        long[] cacheStats = tokenIds.isEmpty() ? new long[]{0, 0}
                : usageLogService.getCacheStatsSince(tokenIds, cutoff);
        long[] cacheStatsToday = tokenIds.isEmpty() ? new long[]{0, 0}
                : usageLogService.getCacheStatsSince(tokenIds, todayStart);

        return DashboardStats.builder()
                .totalChannels(0L)
                .activeChannels(0L)
                .totalTokens(tokenCount)
                .totalUsers(1L)
                .totalRequests(totalRequests)
                .totalTokensUsed(totalTokensUsed)
                .requestsToday(requestsToday)
                .tokensUsedToday(tokensUsedToday)
                .totalInputTokens(totalInputTokens)
                .totalOutputTokens(totalOutputTokens)
                .inputTokensToday(inputTokensToday)
                .outputTokensToday(outputTokensToday)
                .totalCreditsConsumed(totalCreditsConsumed)
                .creditsConsumedToday(creditsConsumedToday)
                .creditsByTypeToday(creditsByTypeToday)
                .totalCachedPromptTokens(totalCachedPromptTokens)
                .cachedPromptTokensToday(cachedPromptTokensToday)
                .totalCacheCreationTokens(cacheStats[0])
                .totalCacheReadTokens(cacheStats[1])
                .todayCacheCreationTokens(cacheStatsToday[0])
                .todayCacheReadTokens(cacheStatsToday[1])
                .myCredits(currentUser.getCredits())
                .build();
    }

    // ========== 工具方法 ==========

    /**
     * 固定四类型（text/image/video/audio）的积分映射，初始为 0
     */
    private static Map<String, BigDecimal> defaultCreditsByType() {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (String type : MODEL_TYPES) map.put(type, BigDecimal.ZERO);
        return map;
    }

    private static Object[] emptyRow() {
        return new Object[]{0L, 0L, 0L, 0L, 0.0, 0L, 0L, 0L};
    }

    private static long longVal(Object v) {
        return ((Number) v).longValue();
    }

    private static double doubleVal(Object v) {
        return ((Number) v).doubleValue();
    }

    private static BigDecimal bigDecVal(Object v) {
        return BigDecimal.valueOf(((Number) v).doubleValue());
    }
}
