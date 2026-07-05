package com.aiconnecting.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * 渠道健康追踪器 - 管理渠道权重、失败计数、封禁与自动禁用
 * 支持 Redis（分布式）和纯内存两种模式
 */
@Service
@Slf4j
public class ChannelHealthTracker {

    @Autowired(required = false)
    private RedisTemplate<String, Long> redisTemplate;

    private ChannelService channelService;

    @Autowired
    public void setChannelService(ChannelService channelService) {
        this.channelService = channelService;
    }

    // ==================== 配置常量 ====================
    private static final long DEFAULT_WEIGHT = 100L;
    private static final long MIN_WEIGHT = 1L;
    private static final long MAX_WEIGHT = 200L;
    private static final long WEIGHT_DECREMENT = 20L;
    private static final long WEIGHT_INCREMENT = 5L;

    /** 3 分钟内失败 3 次则封禁 */
    private static final long FAILURE_WINDOW_MS = 3 * 60 * 1000L;
    private static final long FAILURE_THRESHOLD = 3L;

    /** 封禁时长：1 小时 */
    private static final long BLOCK_DURATION_MS = 60 * 60 * 1000L;

    /** 探测失败 5 次后自动禁用 */
    private static final int PROBE_DISABLE_THRESHOLD = 5;

    // ==================== Redis Key 前缀 ====================
    private static final String WEIGHT_PREFIX = "channel:weight:";
    private static final String FAILURE_PREFIX = "channel:failures:";
    private static final String BLOCK_PREFIX = "channel:blocked:";
    private static final String PROBE_FAIL_PREFIX = "channel:probe_fail:";

    // ==================== 异步执行器 ====================
    private final ExecutorService healthExecutor = new ThreadPoolExecutor(
            1, 4, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1024),
            r -> {
                Thread t = new Thread(r, "health-tracker");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // 队列满时由调用线程执行，避免丢失失败记录
    );

    // ==================== 内存回退数据结构 ====================
    private final ConcurrentHashMap<Long, Long> memWeights = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, LinkedList<Long>> memFailureTimestamps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> memBlockUntil = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> memProbeFailures = new ConcurrentHashMap<>();

    private boolean isRedisAvailable() {
        return redisTemplate != null;
    }

    // ==================== 权重管理 ====================

    /**
     * 获取渠道当前权重
     */
    public long getWeight(Long channelId) {
        try {
            if (isRedisAvailable()) {
                Long weight = redisTemplate.opsForValue().get(WEIGHT_PREFIX + channelId);
                return weight != null ? weight : DEFAULT_WEIGHT;
            }
        } catch (Exception e) {
            log.warn("Redis 获取渠道 {} 权重失败，降级为内存模式: {}", channelId, e.getMessage());
        }
        return memWeights.getOrDefault(channelId, DEFAULT_WEIGHT);
    }

    /**
     * 设置渠道权重
     */
    private void setWeight(Long channelId, long weight) {
        weight = Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, weight));
        try {
            if (isRedisAvailable()) {
                redisTemplate.opsForValue().set(WEIGHT_PREFIX + channelId, weight, 24, TimeUnit.HOURS);
                return;
            }
        } catch (Exception e) {
            log.warn("Redis 设置渠道 {} 权重失败，降级为内存模式: {}", channelId, e.getMessage());
        }
        memWeights.put(channelId, weight);
    }

    /**
     * 请求失败时降低权重
     */
    public void decreaseWeight(Long channelId) {
        long current = getWeight(channelId);
        long newWeight = Math.max(MIN_WEIGHT, current - WEIGHT_DECREMENT);
        setWeight(channelId, newWeight);
        log.info("渠道 {} 权重降低: {} -> {}", channelId, current, newWeight);
    }

    /**
     * 请求成功时恢复权重
     */
    public void increaseWeight(Long channelId) {
        long current = getWeight(channelId);
        long newWeight = Math.min(MAX_WEIGHT, current + WEIGHT_INCREMENT);
        setWeight(channelId, newWeight);
    }

    // ==================== 失败计数与封禁 ====================

    /**
     * 记录渠道请求失败（异步执行，不阻塞请求线程）
     * 如果在 10 分钟窗口内失败次数达到阈值，则封禁该渠道 1 小时
     *
     * @param channelId    渠道 ID
     * @param errorMessage 失败原因（用于日志）
     */
    public void recordFailure(Long channelId, String errorMessage) {
        healthExecutor.submit(() -> {
            try {
                doRecordFailure(channelId, errorMessage);
            } catch (Exception e) {
                log.error("异步记录渠道 {} 失败异常: {}", channelId, e.getMessage(), e);
            }
        });
    }

    /**
     * 实际记录失败逻辑（在异步线程中执行）
     */
    private void doRecordFailure(Long channelId, String errorMessage) {
        long now = System.currentTimeMillis();

        try {
            if (isRedisAvailable()) {
                String key = FAILURE_PREFIX + channelId;
                // 添加当前失败时间戳
                redisTemplate.opsForZSet().add(key, now, (double) now);
                // 清理窗口外的记录
                redisTemplate.opsForZSet().removeRangeByScore(key, 0, now - FAILURE_WINDOW_MS);
                // 设置 key 过期时间为窗口时长
                redisTemplate.expire(key, FAILURE_WINDOW_MS, TimeUnit.MILLISECONDS);
                // 统计窗口内失败次数
                Long count = redisTemplate.opsForZSet().zCard(key);
                log.warn("渠道 {} 请求失败（第 {}/{} 次，10分钟窗口）: {}",
                        channelId, count, FAILURE_THRESHOLD, errorMessage);

                if (count != null && count >= FAILURE_THRESHOLD) {
                    blockChannel(channelId);
                    // 清除失败计数，封禁期间重新计数
                    redisTemplate.delete(key);
                }
                return;
            }
        } catch (Exception e) {
            log.warn("Redis 记录渠道 {} 失败降级为内存模式: {}", channelId, e.getMessage());
        }

        // 内存回退
        LinkedList<Long> timestamps = memFailureTimestamps.computeIfAbsent(channelId, k -> new LinkedList<>());
        synchronized (timestamps) {
            timestamps.add(now);
            while (!timestamps.isEmpty() && now - timestamps.getFirst() > FAILURE_WINDOW_MS) {
                timestamps.removeFirst();
            }
            log.warn("渠道 {} 请求失败（第 {}/{} 次，10分钟窗口）: {}",
                    channelId, timestamps.size(), FAILURE_THRESHOLD, errorMessage);
            if (timestamps.size() >= FAILURE_THRESHOLD) {
                blockChannel(channelId);
                timestamps.clear();
            }
        }
    }

    /**
     * 记录渠道请求成功（异步执行，不阻塞请求线程）
     */
    public void recordSuccess(Long channelId) {
        healthExecutor.submit(() -> {
            try {
                increaseWeight(channelId);
                resetProbeFailures(channelId);
            } catch (Exception e) {
                log.error("异步记录渠道 {} 成功异常: {}", channelId, e.getMessage(), e);
            }
        });
    }

    /**
     * 封禁渠道 1 小时
     */
    private void blockChannel(Long channelId) {
        long until = System.currentTimeMillis() + BLOCK_DURATION_MS;
        try {
            if (isRedisAvailable()) {
                redisTemplate.opsForValue().set(BLOCK_PREFIX + channelId, until, BLOCK_DURATION_MS, TimeUnit.MILLISECONDS);
                log.warn("渠道 {} 因10分钟内失败达 {} 次，已被封禁至 {}",
                        channelId, FAILURE_THRESHOLD, new Date(until));
                return;
            }
        } catch (Exception e) {
            log.warn("Redis 封禁渠道 {} 失败，降级为内存模式: {}", channelId, e.getMessage());
        }
        memBlockUntil.put(channelId, until);
        log.warn("渠道 {} 因10分钟内失败达 {} 次，已被封禁至 {}",
                channelId, FAILURE_THRESHOLD, new Date(until));
    }

    /**
     * 检查渠道是否被封禁（只读操作，依赖 Redis TTL 自动过期）
     */
    public boolean isBlocked(Long channelId) {
        try {
            if (isRedisAvailable()) {
                Long until = redisTemplate.opsForValue().get(BLOCK_PREFIX + channelId);
                return until != null && System.currentTimeMillis() < until;
            }
        } catch (Exception e) {
            log.warn("Redis 检查渠道 {} 封禁状态失败: {}", channelId, e.getMessage());
        }
        Long until = memBlockUntil.get(channelId);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            memBlockUntil.remove(channelId);
            return false;
        }
        return true;
    }

    /**
     * 解除渠道封禁（探测成功后调用）
     */
    public void unblockChannel(Long channelId) {
        try {
            if (isRedisAvailable()) {
                redisTemplate.delete(BLOCK_PREFIX + channelId);
                log.info("渠道 {} 封禁已解除（探测成功）", channelId);
                return;
            }
        } catch (Exception e) {
            log.warn("Redis 解除渠道 {} 封禁失败: {}", channelId, e.getMessage());
        }
        memBlockUntil.remove(channelId);
        log.info("渠道 {} 封禁已解除（探测成功）", channelId);
    }

    // ==================== 探测失败计数（自动禁用） ====================

    /**
     * 记录一次探测失败
     *
     * @return 当前连续探测失败次数
     */
    public int recordProbeFailure(Long channelId, String errorMessage) {
        int count;
        try {
            if (isRedisAvailable()) {
                Long incremented = redisTemplate.opsForValue().increment(PROBE_FAIL_PREFIX + channelId);
                count = incremented != null ? incremented.intValue() : 1;
                redisTemplate.expire(PROBE_FAIL_PREFIX + channelId, 24, TimeUnit.HOURS);
                log.warn("渠道 {} 探测失败（第 {}/{} 次）: {}",
                        channelId, count, PROBE_DISABLE_THRESHOLD, errorMessage);
                if (count >= PROBE_DISABLE_THRESHOLD) {
                    autoDisableChannel(channelId);
                    redisTemplate.delete(PROBE_FAIL_PREFIX + channelId);
                }
                return count;
            }
        } catch (Exception e) {
            log.warn("Redis 记录渠道 {} 探测失败降级为内存模式: {}", channelId, e.getMessage());
        }
        count = memProbeFailures.merge(channelId, 1, Integer::sum);
        log.warn("渠道 {} 探测失败（第 {}/{} 次）: {}",
                channelId, count, PROBE_DISABLE_THRESHOLD, errorMessage);
        if (count >= PROBE_DISABLE_THRESHOLD) {
            autoDisableChannel(channelId);
            memProbeFailures.remove(channelId);
        }
        return count;
    }

    /**
     * 重置探测失败计数（探测成功时调用）
     */
    public void resetProbeFailures(Long channelId) {
        try {
            if (isRedisAvailable()) {
                redisTemplate.delete(PROBE_FAIL_PREFIX + channelId);
                return;
            }
        } catch (Exception e) {
            log.warn("Redis 重置渠道 {} 探测失败计数异常: {}", channelId, e.getMessage());
        }
        memProbeFailures.remove(channelId);
    }

    /**
     * 自动禁用渠道（探测多次仍失败）
     */
    private void autoDisableChannel(Long channelId) {
        log.error("===== 渠道 {} 连续探测 {} 次仍失败，自动禁用 =====", channelId, PROBE_DISABLE_THRESHOLD);
        try {
            if (channelService != null) {
                channelService.disableChannel(channelId);
                log.error("渠道 {} 已自动禁用，请检查渠道配置和网络状态", channelId);
            }
        } catch (Exception e) {
            log.error("自动禁用渠道 {} 时发生异常: {}", channelId, e.getMessage(), e);
        }
    }

    // ==================== 查询接口 ====================

    // ==================== 封禁列表本地缓存 ====================
    /** getBlockedChannelIds 短时缓存，避免每次请求都 SCAN Redis */
    private volatile Set<Long> blockedIdsCache = null;
    private volatile long blockedIdsCacheAt = 0;
    private static final long BLOCKED_IDS_CACHE_TTL_MS = 2000L; // 2 秒

    /**
     * 获取所有被封禁的渠道 ID（供定时探测任务和路由器使用）
     * 使用 SCAN 代替 KEYS 避免阻塞 Redis
     * 本地缓存 2 秒，避免每次请求都 SCAN
     */
    public Set<Long> getBlockedChannelIds() {
        // 优先返回本地缓存（2 秒有效）
        if (blockedIdsCache != null && System.currentTimeMillis() - blockedIdsCacheAt < BLOCKED_IDS_CACHE_TTL_MS) {
            return blockedIdsCache;
        }
        Set<Long> result = new HashSet<>();
        try {
            if (isRedisAvailable()) {
                Set<String> keys = new HashSet<>();
                var scanOptions = org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(BLOCK_PREFIX + "*").count(100).build();
                try (var cursor = redisTemplate.scan(scanOptions)) {
                    while (cursor.hasNext()) {
                        keys.add(cursor.next());
                    }
                }
                for (String key : keys) {
                    try {
                        Long id = Long.parseLong(key.substring(BLOCK_PREFIX.length()));
                        Long until = redisTemplate.opsForValue().get(key);
                        if (until != null && System.currentTimeMillis() < until) {
                            result.add(id);
                        }
                    } catch (NumberFormatException ignored) {}
                }
                blockedIdsCache = result;
                blockedIdsCacheAt = System.currentTimeMillis();
                return result;
            }
        } catch (Exception e) {
            log.warn("Redis 获取封禁渠道列表失败: {}", e.getMessage());
        }
        // 内存回退
        long now = System.currentTimeMillis();
        memBlockUntil.forEach((id, until) -> {
            if (now < until) result.add(id);
        });
        blockedIdsCache = result;
        blockedIdsCacheAt = System.currentTimeMillis();
        return result;
    }

    /**
     * 获取所有被封禁的渠道 ID 及封禁截止时间
     * @return Map<channelId, blockUntilTimestamp>
     */
    public Map<Long, Long> getBlockedChannelDetails() {
        Map<Long, Long> result = new HashMap<>();
        try {
            if (isRedisAvailable()) {
                Set<String> keys = new HashSet<>();
                var scanOptions = org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(BLOCK_PREFIX + "*").count(100).build();
                try (var cursor = redisTemplate.scan(scanOptions)) {
                    while (cursor.hasNext()) {
                        keys.add(cursor.next());
                    }
                }
                for (String key : keys) {
                    try {
                        Long id = Long.parseLong(key.substring(BLOCK_PREFIX.length()));
                        Long until = redisTemplate.opsForValue().get(key);
                        if (until != null && System.currentTimeMillis() < until) {
                            result.put(id, until);
                        }
                    } catch (NumberFormatException ignored) {}
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("Redis 获取封禁渠道详情失败: {}", e.getMessage());
        }
        // 内存回退
        long now = System.currentTimeMillis();
        memBlockUntil.forEach((id, until) -> {
            if (now < until) result.put(id, until);
        });
        return result;
    }

    /**
     * 获取渠道当前权重（供管理接口使用）
     */
    public Map<String, Object> getChannelHealth(Long channelId) {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("channelId", channelId);
        health.put("weight", getWeight(channelId));
        health.put("blocked", isBlocked(channelId));
        health.put("probeFailures", getProbeFailureCount(channelId));
        return health;
    }

    private int getProbeFailureCount(Long channelId) {
        try {
            if (isRedisAvailable()) {
                Long count = redisTemplate.opsForValue().get(PROBE_FAIL_PREFIX + channelId);
                return count != null ? count.intValue() : 0;
            }
        } catch (Exception ignored) {}
        return memProbeFailures.getOrDefault(channelId, 0);
    }

    /**
     * 清除所有追踪数据（渠道配置变更时调用）
     */
    public void clearAll() {
        // 清除封禁列表本地缓存
        blockedIdsCache = null;
        blockedIdsCacheAt = 0;
        try {
            if (isRedisAvailable()) {
                Set<String> keys = new HashSet<>();
                // 使用 SCAN 代替 KEYS，避免阻塞 Redis
                addScanKeys(keys, WEIGHT_PREFIX + "*");
                addScanKeys(keys, FAILURE_PREFIX + "*");
                addScanKeys(keys, BLOCK_PREFIX + "*");
                addScanKeys(keys, PROBE_FAIL_PREFIX + "*");
                if (!keys.isEmpty()) redisTemplate.delete(keys);
                log.info("渠道健康追踪数据已清除（Redis）");
                return;
            }
        } catch (Exception e) {
            log.warn("Redis 清除渠道健康数据失败: {}", e.getMessage());
        }
        memWeights.clear();
        memFailureTimestamps.clear();
        memBlockUntil.clear();
        memProbeFailures.clear();
        log.info("渠道健康追踪数据已清除（内存）");
    }

    /**
     * 使用 SCAN 收集匹配指定 pattern 的所有 key
     */
    private void addScanKeys(Set<String> keys, String pattern) {
        var scanOptions = org.springframework.data.redis.core.ScanOptions.scanOptions()
                .match(pattern).count(100).build();
        try (var cursor = redisTemplate.scan(scanOptions)) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }
    }
}
