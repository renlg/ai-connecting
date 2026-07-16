package com.aiconnecting.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * 渠道健康追踪器 - 基于错误率的熔断器（circuit breaker）
 * 支持 Redis（分布式）和纯内存两种模式
 *
 * 状态机：
 * - CLOSED：正常放行，滚动窗口内统计错误率
 * - OPEN：直接拒绝，等待 blockedUntil 到期后（惰性）进入 HALF_OPEN
 * - HALF_OPEN：仅放行 1 个探测请求；成功则 CLOSED，失败则重新 OPEN 并指数退避
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

    /** 滚动窗口时长：1 分钟 */
    private static final long ROLLING_WINDOW_MS = 60 * 1000L;
    /** 窗口内最少请求数才判定熔断 */
    private static final long MIN_REQUESTS_TO_TRIP = 10L;
    /** 错误率阈值 */
    private static final double ERROR_RATE_THRESHOLD = 0.5;

    /** 首次熔断退避时长：30 秒 */
    private static final long INITIAL_BACKOFF_MS = 30 * 1000L;
    /** 退避时长上限：30 分钟 */
    private static final long MAX_BACKOFF_MS = 30 * 60 * 1000L;

    /** 上游限流（429）冷却时长：30 秒，不计入熔断窗口 */
    private static final long RATE_LIMIT_COOLDOWN_MS = 30 * 1000L;

    /** 鉴权失败（401/403）立即封禁时长：1 小时 */
    private static final long AUTH_BLOCK_DURATION_MS = 60 * 60 * 1000L;

    /** 半开探测许可有效期，防止并发请求同时探测 */
    private static final long HALF_OPEN_PERMIT_TTL_MS = 20 * 1000L;

    /** 持续处于 OPEN 状态超过该时长，自动禁用渠道 */
    private static final long OPEN_AUTO_DISABLE_MS = 2 * 60 * 60 * 1000L;

    public enum CircuitState {
        CLOSED, OPEN, HALF_OPEN
    }

    /**
     * 上游错误分类
     * TIMEOUT/SERVER_ERROR/CONNECTION_ERROR 计入滚动窗口错误率
     * RATE_LIMIT 仅短暂冷却，不计入错误率、不影响熔断器状态
     * AUTH_ERROR 立即熔断 1 小时（密钥可能已失效），不经过滚动窗口
     * CLIENT_ERROR 是用户请求本身的问题，不影响渠道健康
     */
    public enum ErrorCategory {
        TIMEOUT, SERVER_ERROR, RATE_LIMIT, AUTH_ERROR, CLIENT_ERROR, CONNECTION_ERROR;

        public static ErrorCategory fromStatusCode(int code) {
            if (code == 429) return RATE_LIMIT;
            if (code == 401 || code == 403) return AUTH_ERROR;
            if (code == 504) return TIMEOUT;
            if (code == 502 || code == 503) return CONNECTION_ERROR;
            if (code >= 500) return SERVER_ERROR;
            if (code >= 400) return CLIENT_ERROR;
            return SERVER_ERROR;
        }

        public static ErrorCategory fromException(Throwable e) {
            if (e instanceof java.net.SocketTimeoutException) return TIMEOUT;
            return CONNECTION_ERROR;
        }
    }

    // ==================== Redis Key 前缀 ====================
    /** 简单冷却（上游限流），沿用旧 key，纯 TTL 语义 */
    private static final String RATE_LIMIT_PREFIX = "channel:blocked:";
    /** 熔断器状态：0=CLOSED, 1=OPEN */
    private static final String CB_STATE_PREFIX = "channel:cb:state:";
    /** 熔断器 OPEN 截止时间 */
    private static final String CB_UNTIL_PREFIX = "channel:cb:until:";
    /** 当前退避时长 */
    private static final String CB_BACKOFF_PREFIX = "channel:cb:backoff:";
    /** 本轮 OPEN 起始时间（用于超时自动禁用） */
    private static final String CB_OPEN_SINCE_PREFIX = "channel:cb:open_since:";
    /** 半开探测许可 */
    private static final String CB_PERMIT_PREFIX = "channel:cb:permit:";
    /** 滚动窗口 - 总请求数 */
    private static final String WIN_TOTAL_PREFIX = "channel:cb:win:total:";
    /** 滚动窗口 - 错误请求数 */
    private static final String WIN_ERROR_PREFIX = "channel:cb:win:error:";

    // ==================== 异步执行器 ====================
    private final ExecutorService healthExecutor = new ThreadPoolExecutor(
            1, 4, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1024),
            r -> {
                Thread t = new Thread(r, "health-tracker");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // 队列满时由调用线程执行，避免丢失记录
    );

    @jakarta.annotation.PreDestroy
    void shutdown() {
        healthExecutor.shutdown();
        try {
            if (!healthExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                healthExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            healthExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 内存回退数据结构 ====================
    private final ConcurrentHashMap<Long, Long> memRateLimitUntil = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> memCbState = new ConcurrentHashMap<>(); // 0/1
    private final ConcurrentHashMap<Long, Long> memCbUntil = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> memCbBackoff = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> memCbOpenSince = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> memCbPermitUntil = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, LinkedList<Long>> memWinTotal = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, LinkedList<Long>> memWinError = new ConcurrentHashMap<>();

    private boolean isRedisAvailable() {
        return redisTemplate != null;
    }

    // ==================== 滚动窗口 ====================

    private void addToWindow(Long channelId, boolean isError) {
        long now = System.currentTimeMillis();
        try {
            if (isRedisAvailable()) {
                String totalKey = WIN_TOTAL_PREFIX + channelId;
                long member = System.nanoTime();
                redisTemplate.opsForZSet().add(totalKey, member, (double) now);
                redisTemplate.opsForZSet().removeRangeByScore(totalKey, 0, now - ROLLING_WINDOW_MS);
                redisTemplate.expire(totalKey, ROLLING_WINDOW_MS, TimeUnit.MILLISECONDS);
                if (isError) {
                    String errorKey = WIN_ERROR_PREFIX + channelId;
                    redisTemplate.opsForZSet().add(errorKey, member, (double) now);
                    redisTemplate.opsForZSet().removeRangeByScore(errorKey, 0, now - ROLLING_WINDOW_MS);
                    redisTemplate.expire(errorKey, ROLLING_WINDOW_MS, TimeUnit.MILLISECONDS);
                }
                return;
            }
        } catch (Exception e) {
            log.warn("Redis 记录渠道 {} 滚动窗口失败，降级为内存模式: {}", channelId, e.getMessage());
        }
        addToMemWindow(memWinTotal, channelId, now);
        if (isError) {
            addToMemWindow(memWinError, channelId, now);
        }
    }

    private void addToMemWindow(ConcurrentHashMap<Long, LinkedList<Long>> map, Long channelId, long now) {
        LinkedList<Long> timestamps = map.computeIfAbsent(channelId, k -> new LinkedList<>());
        synchronized (timestamps) {
            timestamps.add(now);
            while (!timestamps.isEmpty() && now - timestamps.getFirst() > ROLLING_WINDOW_MS) {
                timestamps.removeFirst();
            }
        }
    }

    private long countWindow(ConcurrentHashMap<Long, LinkedList<Long>> map, Long channelId, long now) {
        LinkedList<Long> timestamps = map.get(channelId);
        if (timestamps == null) return 0;
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && now - timestamps.getFirst() > ROLLING_WINDOW_MS) {
                timestamps.removeFirst();
            }
            return timestamps.size();
        }
    }

    /**
     * 获取窗口内总请求数与错误数
     */
    private long[] getWindowCounts(Long channelId) {
        long now = System.currentTimeMillis();
        try {
            if (isRedisAvailable()) {
                String totalKey = WIN_TOTAL_PREFIX + channelId;
                String errorKey = WIN_ERROR_PREFIX + channelId;
                redisTemplate.opsForZSet().removeRangeByScore(totalKey, 0, now - ROLLING_WINDOW_MS);
                redisTemplate.opsForZSet().removeRangeByScore(errorKey, 0, now - ROLLING_WINDOW_MS);
                Long total = redisTemplate.opsForZSet().zCard(totalKey);
                Long errors = redisTemplate.opsForZSet().zCard(errorKey);
                return new long[]{total != null ? total : 0, errors != null ? errors : 0};
            }
        } catch (Exception e) {
            log.warn("Redis 获取渠道 {} 窗口统计失败，降级为内存模式: {}", channelId, e.getMessage());
        }
        return new long[]{countWindow(memWinTotal, channelId, now), countWindow(memWinError, channelId, now)};
    }

    private void clearWindow(Long channelId) {
        try {
            if (isRedisAvailable()) {
                redisTemplate.delete(WIN_TOTAL_PREFIX + channelId);
                redisTemplate.delete(WIN_ERROR_PREFIX + channelId);
                return;
            }
        } catch (Exception e) {
            log.warn("Redis 清除渠道 {} 窗口失败: {}", channelId, e.getMessage());
        }
        memWinTotal.remove(channelId);
        memWinError.remove(channelId);
    }

    // ==================== 失败/成功记录 ====================

    /**
     * 记录渠道请求失败（异步执行，不阻塞请求线程）
     */
    public void recordFailure(Long channelId, ErrorCategory category, String errorMessage) {
        switch (category) {
            case RATE_LIMIT:
                healthExecutor.submit(() -> applyRateLimitCooldown(channelId, errorMessage));
                break;
            case AUTH_ERROR:
                healthExecutor.submit(() -> {
                    log.warn("渠道 {} 鉴权失败（{}），密钥可能已失效，立即熔断 1 小时: {}",
                            channelId, category, errorMessage);
                    forceOpen(channelId, AUTH_BLOCK_DURATION_MS);
                });
                break;
            case CLIENT_ERROR:
                // 用户请求本身有误（400/404/413/422 等），渠道健康不受影响
                break;
            case TIMEOUT:
            case SERVER_ERROR:
            case CONNECTION_ERROR:
            default:
                healthExecutor.submit(() -> {
                    try {
                        onHealthRelevantFailure(channelId, category, errorMessage);
                    } catch (Exception e) {
                        log.error("异步记录渠道 {} 失败异常: {}", channelId, e.getMessage(), e);
                    }
                });
                break;
        }
    }

    private void onHealthRelevantFailure(Long channelId, ErrorCategory category, String errorMessage) {
        CircuitState state = getEffectiveState(channelId);
        if (state == CircuitState.HALF_OPEN) {
            // 半开探测失败，重新熔断并指数退避
            reopenAfterHalfOpenFailure(channelId);
            log.warn("渠道 {} 半开探测失败（{}），重新熔断: {}", channelId, category, errorMessage);
            return;
        }
        if (state == CircuitState.OPEN) {
            // 理论上不应路由到 OPEN 渠道，出现属于竞态，忽略即可
            return;
        }
        addToWindow(channelId, true);
        long[] counts = getWindowCounts(channelId);
        long total = counts[0];
        long errors = counts[1];
        double rate = total > 0 ? (double) errors / total : 0;
        log.warn("渠道 {} 请求失败（1分钟窗口 {}/{}，错误率 {}%）: {}",
                channelId, errors, total, Math.round(rate * 100), errorMessage);
        if (total >= MIN_REQUESTS_TO_TRIP && rate >= ERROR_RATE_THRESHOLD) {
            tripCircuit(channelId);
        }
    }

    /**
     * 上游限流（429）短暂冷却，不计入熔断窗口
     */
    private void applyRateLimitCooldown(Long channelId, String errorMessage) {
        long until = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS;
        try {
            if (isRedisAvailable()) {
                redisTemplate.opsForValue().set(RATE_LIMIT_PREFIX + channelId, until, RATE_LIMIT_COOLDOWN_MS, TimeUnit.MILLISECONDS);
                log.info("渠道 {} 触发上游限流，短暂冷却 {} 秒: {}", channelId, RATE_LIMIT_COOLDOWN_MS / 1000, errorMessage);
                return;
            }
        } catch (Exception e) {
            log.warn("Redis 设置渠道 {} 限流冷却失败，降级为内存模式: {}", channelId, e.getMessage());
        }
        memRateLimitUntil.put(channelId, until);
        log.info("渠道 {} 触发上游限流，短暂冷却 {} 秒: {}", channelId, RATE_LIMIT_COOLDOWN_MS / 1000, errorMessage);
    }

    /**
     * 记录渠道请求成功（异步执行，不阻塞请求线程）
     */
    public void recordSuccess(Long channelId) {
        healthExecutor.submit(() -> {
            try {
                CircuitState state = getEffectiveState(channelId);
                if (state == CircuitState.HALF_OPEN) {
                    closeCircuit(channelId);
                    log.info("渠道 {} 半开探测成功，熔断器关闭", channelId);
                } else if (state == CircuitState.CLOSED) {
                    addToWindow(channelId, false);
                }
                // state == OPEN：竞态，忽略
            } catch (Exception e) {
                log.error("异步记录渠道 {} 成功异常: {}", channelId, e.getMessage(), e);
            }
        });
    }

    // ==================== 熔断器状态机 ====================

    /**
     * 熔断（CLOSED -> OPEN），使用当前退避时长（默认 30 秒）
     */
    private void tripCircuit(Long channelId) {
        long backoff = getBackoff(channelId);
        long until = System.currentTimeMillis() + backoff;
        setState(channelId, true, until);
        setOpenSinceIfAbsent(channelId);
        clearWindow(channelId);
        log.warn("渠道 {} 1分钟内错误率达到阈值，已熔断 {} 秒", channelId, backoff / 1000);
    }

    /**
     * 半开探测失败 -> 重新熔断，退避时长翻倍（上限 30 分钟）
     */
    private void reopenAfterHalfOpenFailure(Long channelId) {
        long backoff = Math.min(getBackoff(channelId) * 2, MAX_BACKOFF_MS);
        setBackoff(channelId, backoff);
        long until = System.currentTimeMillis() + backoff;
        setState(channelId, true, until);
        releasePermit(channelId);
        log.warn("渠道 {} 重新熔断，退避时长翻倍至 {} 秒", channelId, backoff / 1000);
    }

    /**
     * 立即熔断固定时长（用于鉴权失败），不经过窗口判定
     */
    private void forceOpen(Long channelId, long durationMs) {
        long until = System.currentTimeMillis() + durationMs;
        setState(channelId, true, until);
        setOpenSinceIfAbsent(channelId);
        releasePermit(channelId);
    }

    /**
     * 关闭熔断器（探测成功 / 手动解封），退避重置为初始值
     */
    private void closeCircuit(Long channelId) {
        setState(channelId, false, 0);
        setBackoff(channelId, INITIAL_BACKOFF_MS);
        clearOpenSince(channelId);
        releasePermit(channelId);
        clearWindow(channelId);
    }

    /**
     * 解除渠道封禁（探测成功后调用，或管理接口手动解封）
     */
    public void unblockChannel(Long channelId) {
        closeCircuit(channelId);
        log.info("渠道 {} 熔断已解除", channelId);
    }

    /**
     * 获取渠道当前生效状态（惰性将过期的 OPEN 状态派生为 HALF_OPEN，不落盘）
     */
    public CircuitState getEffectiveState(Long channelId) {
        int stateVal = getStateVal(channelId);
        if (stateVal == 0) {
            return CircuitState.CLOSED;
        }
        long until = getUntil(channelId);
        if (System.currentTimeMillis() < until) {
            return CircuitState.OPEN;
        }
        return CircuitState.HALF_OPEN;
    }

    /**
     * 渠道是否处于不可用状态（熔断 OPEN 或上游限流冷却中）
     */
    public boolean isBlocked(Long channelId) {
        if (isRateLimitCoolingDown(channelId)) {
            return true;
        }
        return getEffectiveState(channelId) == CircuitState.OPEN;
    }

    private boolean isRateLimitCoolingDown(Long channelId) {
        try {
            if (isRedisAvailable()) {
                Long until = redisTemplate.opsForValue().get(RATE_LIMIT_PREFIX + channelId);
                return until != null && System.currentTimeMillis() < until;
            }
        } catch (Exception e) {
            log.warn("Redis 检查渠道 {} 限流冷却状态失败: {}", channelId, e.getMessage());
        }
        Long until = memRateLimitUntil.get(channelId);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            memRateLimitUntil.remove(channelId);
            return false;
        }
        return true;
    }

    /**
     * 尝试获取半开探测许可，确保 HALF_OPEN 状态下只放行 1 个请求
     * 只有当渠道确实处于 HALF_OPEN 时才可能获取成功
     */
    public boolean tryAcquireHalfOpenProbe(Long channelId) {
        if (getEffectiveState(channelId) != CircuitState.HALF_OPEN) {
            return false;
        }
        try {
            if (isRedisAvailable()) {
                Boolean acquired = redisTemplate.opsForValue()
                        .setIfAbsent(CB_PERMIT_PREFIX + channelId, 1L, HALF_OPEN_PERMIT_TTL_MS, TimeUnit.MILLISECONDS);
                return Boolean.TRUE.equals(acquired);
            }
        } catch (Exception e) {
            log.warn("Redis 获取渠道 {} 半开探测许可失败: {}", channelId, e.getMessage());
        }
        long now = System.currentTimeMillis();
        synchronized (memCbPermitUntil) {
            Long until = memCbPermitUntil.get(channelId);
            if (until != null && now < until) {
                return false;
            }
            memCbPermitUntil.put(channelId, now + HALF_OPEN_PERMIT_TTL_MS);
            return true;
        }
    }

    private void releasePermit(Long channelId) {
        try {
            if (isRedisAvailable()) {
                redisTemplate.delete(CB_PERMIT_PREFIX + channelId);
                return;
            }
        } catch (Exception e) {
            log.warn("Redis 释放渠道 {} 半开探测许可失败: {}", channelId, e.getMessage());
        }
        memCbPermitUntil.remove(channelId);
    }

    // ==================== 状态/退避/OpenSince 读写辅助 ====================

    private int getStateVal(Long channelId) {
        try {
            if (isRedisAvailable()) {
                Long v = redisTemplate.opsForValue().get(CB_STATE_PREFIX + channelId);
                return v != null ? v.intValue() : 0;
            }
        } catch (Exception e) {
            log.warn("Redis 读取渠道 {} 熔断状态失败: {}", channelId, e.getMessage());
        }
        return memCbState.getOrDefault(channelId, 0);
    }

    private long getUntil(Long channelId) {
        try {
            if (isRedisAvailable()) {
                Long v = redisTemplate.opsForValue().get(CB_UNTIL_PREFIX + channelId);
                return v != null ? v : 0L;
            }
        } catch (Exception e) {
            log.warn("Redis 读取渠道 {} 熔断截止时间失败: {}", channelId, e.getMessage());
        }
        return memCbUntil.getOrDefault(channelId, 0L);
    }

    private void setState(Long channelId, boolean open, long until) {
        try {
            if (isRedisAvailable()) {
                if (open) {
                    redisTemplate.opsForValue().set(CB_STATE_PREFIX + channelId, 1L);
                    redisTemplate.opsForValue().set(CB_UNTIL_PREFIX + channelId, until,
                            Math.max(until - System.currentTimeMillis(), 1000), TimeUnit.MILLISECONDS);
                } else {
                    redisTemplate.delete(CB_STATE_PREFIX + channelId);
                    redisTemplate.delete(CB_UNTIL_PREFIX + channelId);
                }
                return;
            }
        } catch (Exception e) {
            log.warn("Redis 设置渠道 {} 熔断状态失败，降级为内存模式: {}", channelId, e.getMessage());
        }
        if (open) {
            memCbState.put(channelId, 1);
            memCbUntil.put(channelId, until);
        } else {
            memCbState.remove(channelId);
            memCbUntil.remove(channelId);
        }
    }

    private long getBackoff(Long channelId) {
        try {
            if (isRedisAvailable()) {
                Long v = redisTemplate.opsForValue().get(CB_BACKOFF_PREFIX + channelId);
                return v != null ? v : INITIAL_BACKOFF_MS;
            }
        } catch (Exception e) {
            log.warn("Redis 读取渠道 {} 退避时长失败: {}", channelId, e.getMessage());
        }
        return memCbBackoff.getOrDefault(channelId, INITIAL_BACKOFF_MS);
    }

    private void setBackoff(Long channelId, long backoffMs) {
        try {
            if (isRedisAvailable()) {
                redisTemplate.opsForValue().set(CB_BACKOFF_PREFIX + channelId, backoffMs, 24, TimeUnit.HOURS);
                return;
            }
        } catch (Exception e) {
            log.warn("Redis 设置渠道 {} 退避时长失败，降级为内存模式: {}", channelId, e.getMessage());
        }
        memCbBackoff.put(channelId, backoffMs);
    }

    private void setOpenSinceIfAbsent(Long channelId) {
        long now = System.currentTimeMillis();
        try {
            if (isRedisAvailable()) {
                redisTemplate.opsForValue().setIfAbsent(CB_OPEN_SINCE_PREFIX + channelId, now, 24, TimeUnit.HOURS);
                return;
            }
        } catch (Exception e) {
            log.warn("Redis 设置渠道 {} OPEN 起始时间失败，降级为内存模式: {}", channelId, e.getMessage());
        }
        memCbOpenSince.putIfAbsent(channelId, now);
    }

    private void clearOpenSince(Long channelId) {
        try {
            if (isRedisAvailable()) {
                redisTemplate.delete(CB_OPEN_SINCE_PREFIX + channelId);
                return;
            }
        } catch (Exception e) {
            log.warn("Redis 清除渠道 {} OPEN 起始时间失败: {}", channelId, e.getMessage());
        }
        memCbOpenSince.remove(channelId);
    }

    /**
     * 渠道已持续处于 OPEN 状态超过 2 小时，应自动禁用
     */
    public boolean isOpenTooLong(Long channelId) {
        if (getEffectiveState(channelId) != CircuitState.OPEN) {
            return false;
        }
        Long openSince;
        try {
            if (isRedisAvailable()) {
                openSince = redisTemplate.opsForValue().get(CB_OPEN_SINCE_PREFIX + channelId);
            } else {
                openSince = memCbOpenSince.get(channelId);
            }
        } catch (Exception e) {
            openSince = memCbOpenSince.get(channelId);
        }
        return openSince != null && System.currentTimeMillis() - openSince > OPEN_AUTO_DISABLE_MS;
    }

    /**
     * 自动禁用渠道（长时间处于熔断状态）
     */
    public void autoDisableChannel(Long channelId) {
        log.error("===== 渠道 {} 持续熔断超过 {} 小时，自动禁用 =====", channelId, OPEN_AUTO_DISABLE_MS / 3600000);
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
     * 获取所有当前不可用的渠道 ID（熔断 OPEN 或限流冷却中），供路由器过滤使用
     * 本地缓存 2 秒，避免每次请求都 SCAN
     */
    public Set<Long> getBlockedChannelIds() {
        if (blockedIdsCache != null && System.currentTimeMillis() - blockedIdsCacheAt < BLOCKED_IDS_CACHE_TTL_MS) {
            return blockedIdsCache;
        }
        Set<Long> result = new HashSet<>();
        result.addAll(scanIds(RATE_LIMIT_PREFIX, memRateLimitUntil));
        result.addAll(getCircuitOpenChannelIds());
        blockedIdsCache = result;
        blockedIdsCacheAt = System.currentTimeMillis();
        return result;
    }

    /**
     * 获取所有真正处于熔断 OPEN 状态的渠道 ID（不含限流冷却），供探测任务使用
     */
    public Set<Long> getCircuitOpenChannelIds() {
        Set<Long> result = new HashSet<>();
        long now = System.currentTimeMillis();
        try {
            if (isRedisAvailable()) {
                Set<String> keys = new HashSet<>();
                var scanOptions = org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(CB_STATE_PREFIX + "*").count(100).build();
                try (var cursor = redisTemplate.scan(scanOptions)) {
                    while (cursor.hasNext()) {
                        keys.add(cursor.next());
                    }
                }
                for (String key : keys) {
                    try {
                        Long id = Long.parseLong(key.substring(CB_STATE_PREFIX.length()));
                        Long until = redisTemplate.opsForValue().get(CB_UNTIL_PREFIX + id);
                        if (until != null && now < until) {
                            result.add(id);
                        }
                    } catch (NumberFormatException ignored) {}
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("Redis 获取熔断渠道列表失败: {}", e.getMessage());
        }
        memCbState.forEach((id, v) -> {
            if (v != null && v == 1) {
                Long until = memCbUntil.get(id);
                if (until != null && now < until) result.add(id);
            }
        });
        return result;
    }

    private Set<Long> scanIds(String prefix, ConcurrentHashMap<Long, Long> memFallback) {
        Set<Long> result = new HashSet<>();
        try {
            if (isRedisAvailable()) {
                Set<String> keys = new HashSet<>();
                var scanOptions = org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(prefix + "*").count(100).build();
                try (var cursor = redisTemplate.scan(scanOptions)) {
                    while (cursor.hasNext()) {
                        keys.add(cursor.next());
                    }
                }
                for (String key : keys) {
                    try {
                        Long id = Long.parseLong(key.substring(prefix.length()));
                        Long until = redisTemplate.opsForValue().get(key);
                        if (until != null && System.currentTimeMillis() < until) {
                            result.add(id);
                        }
                    } catch (NumberFormatException ignored) {}
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("Redis 扫描 {} 失败: {}", prefix, e.getMessage());
        }
        long now = System.currentTimeMillis();
        memFallback.forEach((id, until) -> {
            if (now < until) result.add(id);
        });
        return result;
    }

    /**
     * 获取所有不可用渠道 ID 及截止时间（熔断 OPEN 或限流冷却）
     * @return Map<channelId, blockUntilTimestamp>
     */
    public Map<Long, Long> getBlockedChannelDetails() {
        Map<Long, Long> result = new HashMap<>();
        for (Long id : getBlockedChannelIds()) {
            long rateLimitUntil = 0;
            try {
                if (isRedisAvailable()) {
                    Long v = redisTemplate.opsForValue().get(RATE_LIMIT_PREFIX + id);
                    if (v != null) rateLimitUntil = v;
                } else {
                    Long v = memRateLimitUntil.get(id);
                    if (v != null) rateLimitUntil = v;
                }
            } catch (Exception ignored) {}
            long cbUntil = getUntil(id);
            long until = Math.max(rateLimitUntil, cbUntil);
            if (until > 0) {
                result.put(id, until);
            }
        }
        return result;
    }

    /**
     * 获取渠道当前健康状态（供管理接口使用）
     */
    public Map<String, Object> getChannelHealth(Long channelId) {
        Map<String, Object> health = new LinkedHashMap<>();
        long[] counts = getWindowCounts(channelId);
        health.put("channelId", channelId);
        health.put("state", getEffectiveState(channelId).name());
        health.put("blocked", isBlocked(channelId));
        health.put("windowTotal", counts[0]);
        health.put("windowErrors", counts[1]);
        health.put("backoffMs", getBackoff(channelId));
        return health;
    }

    /**
     * 清除所有追踪数据（渠道配置变更时调用）
     */
    public void clearAll() {
        blockedIdsCache = null;
        blockedIdsCacheAt = 0;
        try {
            if (isRedisAvailable()) {
                Set<String> keys = new HashSet<>();
                addScanKeys(keys, RATE_LIMIT_PREFIX + "*");
                addScanKeys(keys, CB_STATE_PREFIX + "*");
                addScanKeys(keys, CB_UNTIL_PREFIX + "*");
                addScanKeys(keys, CB_BACKOFF_PREFIX + "*");
                addScanKeys(keys, CB_OPEN_SINCE_PREFIX + "*");
                addScanKeys(keys, CB_PERMIT_PREFIX + "*");
                addScanKeys(keys, WIN_TOTAL_PREFIX + "*");
                addScanKeys(keys, WIN_ERROR_PREFIX + "*");
                if (!keys.isEmpty()) redisTemplate.delete(keys);
                log.info("渠道健康追踪数据已清除（Redis）");
                return;
            }
        } catch (Exception e) {
            log.warn("Redis 清除渠道健康数据失败: {}", e.getMessage());
        }
        memRateLimitUntil.clear();
        memCbState.clear();
        memCbUntil.clear();
        memCbBackoff.clear();
        memCbOpenSince.clear();
        memCbPermitUntil.clear();
        memWinTotal.clear();
        memWinError.clear();
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
