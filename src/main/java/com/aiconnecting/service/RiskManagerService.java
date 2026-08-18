package com.aiconnecting.service;

import com.aiconnecting.entity.CircuitBreakerRecord;
import com.aiconnecting.entity.FailureStrategy;
import com.aiconnecting.entity.RiskPolicy;
import com.aiconnecting.repository.CircuitBreakerRecordRepository;
import com.aiconnecting.repository.FailureStrategyRepository;
import com.aiconnecting.repository.RiskPolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import jakarta.annotation.PreDestroy;

@Service
@RequiredArgsConstructor
@Slf4j
public class RiskManagerService {

    private final RiskPolicyRepository riskPolicyRepository;
    private final CircuitBreakerRecordRepository circuitBreakerRecordRepository;
    private final FailureStrategyRepository failureStrategyRepository;
    private final ObjectProvider<RedisTemplate<String, Long>> redisTemplateProvider;
    private final ObjectProvider<RedisScript<Long>> rateLimitScriptProvider;
    private final ObjectProvider<ModelConfigService> modelConfigServiceProvider;

    private static final long FUSED_CACHE_TTL_MS = 2000;
    private static final String REDIS_KEY_PREFIX = "risk:cb:";
    private static final String COUNTER_KEY_PREFIX = "risk:counter:";
    private static final String FAILURE_COUNTER_PREFIX = "risk:failure:";

    private volatile Set<Long> fusedChannelIdsCache = Collections.emptySet();
    private volatile long fusedChannelIdsCachedAt = 0;

    private final ConcurrentHashMap<String, Long> memFusedExpiry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<Long>> memSlidingCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, long[]> memFixedCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> modelNameCache = new ConcurrentHashMap<>();

    private final ThreadPoolExecutor failureExecutor = new ThreadPoolExecutor(
            2, 4, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1024),
            r -> {
                Thread t = new Thread(r, "failure-strategy-" + t_counter.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
    );

    private static final java.util.concurrent.atomic.AtomicLong t_counter = new java.util.concurrent.atomic.AtomicLong();

    private volatile List<FailureStrategy> cachedEnabledStrategies = Collections.emptyList();
    private volatile long cachedStrategiesLoadedAt = 0;
    private static final long STRATEGY_CACHE_TTL_MS = 10_000;

    // ==================== Risk Policy CRUD ====================

    public List<RiskPolicy> listPolicies() {
        return riskPolicyRepository.findAllOrderByCreatedAtDesc();
    }

    public RiskPolicy getPolicy(Long id) {
        return riskPolicyRepository.findById(id).orElse(null);
    }

    @Transactional
    public RiskPolicy createPolicy(RiskPolicy policy) {
        return riskPolicyRepository.save(policy);
    }

    @Transactional
    public RiskPolicy updatePolicy(Long id, RiskPolicy updated) {
        RiskPolicy existing = riskPolicyRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("策略不存在: " + id));
        existing.setChannelId(updated.getChannelId());
        existing.setModelConfigName(updated.getModelConfigName());
        existing.setRateLimit(updated.getRateLimit());
        existing.setTimeWindow(updated.getTimeWindow());
        existing.setWindowType(updated.getWindowType());
        existing.setCircuitBreakerDuration(updated.getCircuitBreakerDuration());
        existing.setStatus(updated.getStatus());
        return riskPolicyRepository.save(existing);
    }

    @Transactional
    public void deletePolicy(Long id) {
        riskPolicyRepository.deleteById(id);
    }

    @Transactional
    public RiskPolicy updatePolicyStatus(Long id, Integer status) {
        RiskPolicy existing = riskPolicyRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("策略不存在: " + id));
        existing.setStatus(status);
        return riskPolicyRepository.save(existing);
    }

    // ==================== Failure Strategy CRUD ====================

    public List<FailureStrategy> listFailureStrategies() {
        return failureStrategyRepository.findAllOrderByPriorityAsc();
    }

    public FailureStrategy getFailureStrategy(Long id) {
        return failureStrategyRepository.findById(id).orElse(null);
    }

    @Transactional
    public FailureStrategy createFailureStrategy(FailureStrategy strategy) {
        return failureStrategyRepository.save(strategy);
    }

    @Transactional
    public FailureStrategy updateFailureStrategy(Long id, FailureStrategy updated) {
        FailureStrategy existing = failureStrategyRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("失败策略不存在: " + id));
        existing.setScope(updated.getScope());
        existing.setChannelId(updated.getChannelId());
        existing.setModelConfigId(updated.getModelConfigId());
        existing.setHttpCodes(updated.getHttpCodes());
        existing.setWindowType(updated.getWindowType());
        existing.setWindowDimension(updated.getWindowDimension());
        existing.setFailureThreshold(updated.getFailureThreshold());
        existing.setFuseDurationSeconds(updated.getFuseDurationSeconds());
        existing.setPriority(updated.getPriority());
        existing.setEnabled(updated.getEnabled());
        return failureStrategyRepository.save(existing);
    }

    @Transactional
    public void deleteFailureStrategy(Long id) {
        failureStrategyRepository.deleteById(id);
    }

    @Transactional
    public FailureStrategy updateFailureStrategyStatus(Long id, Boolean enabled) {
        FailureStrategy existing = failureStrategyRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("失败策略不存在: " + id));
        existing.setEnabled(enabled);
        return failureStrategyRepository.save(existing);
    }

    // ==================== Circuit Breaker Records ====================

    public List<CircuitBreakerRecord> listRecords() {
        return circuitBreakerRecordRepository.findAllOrderByTriggeredAtDesc();
    }

    @Transactional
    public void releaseRecord(Long id) {
        CircuitBreakerRecord record = circuitBreakerRecordRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("熔断记录不存在: " + id));
        record.setStatus("MANUAL_RELEASED");
        circuitBreakerRecordRepository.save(record);
        String fuseKey = buildFuseKey(record.getChannelId(), record.getModelConfigName());
        removeFuse(fuseKey);
        invalidateFusedCache();
    }

    @Transactional
    public CircuitBreakerRecord createManualCircuitBreaker(Long channelId, String modelConfigName,
                                                            int durationSeconds, String reason) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusSeconds(durationSeconds);

        CircuitBreakerRecord record = CircuitBreakerRecord.builder()
                .channelId(channelId)
                .modelConfigName(modelConfigName)
                .source("MANUAL")
                .reason(reason)
                .triggeredAt(now)
                .expiresAt(expiresAt)
                .status("ACTIVE")
                .build();
        circuitBreakerRecordRepository.save(record);

        String fuseKey = buildFuseKey(channelId, modelConfigName);
        setFuse(fuseKey, expiresAt);
        invalidateFusedCache();

        log.warn("手动熔断: channelId={}, model={}, 熔断至 {}", channelId, modelConfigName, expiresAt);
        return record;
    }

    // ==================== Rate Limit Check ====================

    /**
     * 检查渠道+模型是否触发速率策略，如果触发则熔断。
     * 返回 true 表示已被熔断（应拒绝请求），false 表示正常放行。
     */
    public boolean checkAndRecord(Long channelId, String modelConfigName) {
        String fuseKey = buildFuseKey(channelId, modelConfigName);
        if (isFused(fuseKey)) {
            return true;
        }

        List<RiskPolicy> policies = findApplicablePolicies(channelId, modelConfigName);
        if (policies.isEmpty()) {
            return false;
        }

        for (RiskPolicy policy : policies) {
            if (checkPolicyRateLimit(policy, channelId, modelConfigName)) {
                triggerRateLimitCircuitBreaker(policy, channelId, modelConfigName);
                return true;
            }
        }
        return false;
    }

    /**
     * 速率检查入口：使用 channelModelId（可能是 ModelConfig ID 或原始模型名）
     */
    public boolean checkAndRecordByModelId(Long channelId, String channelModelId) {
        String resolvedName = resolveChannelModelIdToName(channelModelId);
        return checkAndRecord(channelId, resolvedName);
    }

    // ==================== Failure Strategy Check ====================

    /**
     * 记录上游失败事件（同步版本，供测试直接调用）。
     * 匹配所有符合条件的失败策略并逐条独立计数。
     */
    public void recordFailureEvent(Long channelId, String modelConfigName, int httpCode) {
        processFailureEvent(channelId, null, modelConfigName, httpCode);
    }

    /**
     * 记录上游失败事件（异步版本，生产入口）。
     * 提交到有界线程池异步处理，不阻塞请求主线程。
     */
    public void recordFailureEventByModelId(Long channelId, String channelModelId, int httpCode) {
        String resolvedName = resolveChannelModelIdToName(channelModelId);
        Long modelConfigId = parseModelConfigId(channelModelId);
        try {
            failureExecutor.submit(() -> {
                try {
                    processFailureEvent(channelId, modelConfigId, resolvedName, httpCode);
                } catch (Exception e) {
                    log.warn("异步失败策略处理异常: channelId={}, model={}, httpCode={}, error={}",
                            channelId, resolvedName, httpCode, e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("失败策略异步处理队列已满，丢弃事件: channelId={}, model={}, httpCode={}",
                    channelId, resolvedName, httpCode);
        }
    }

    /**
     * 内部同步处理：匹配所有符合条件的失败策略，逐条独立计数，达到阈值时独立触发熔断。
     */
    void processFailureEvent(Long channelId, Long modelConfigId, String modelConfigName, int httpCode) {
        List<FailureStrategy> strategies = findAllMatchingFailureStrategies(channelId, modelConfigId, httpCode);
        for (FailureStrategy strategy : strategies) {
            try {
                processSingleFailureStrategy(strategy, channelId, modelConfigName);
            } catch (Exception e) {
                log.warn("失败策略计数/熔断异常(strategyId={}): {}", strategy.getId(), e.getMessage());
            }
        }
    }

    private void processSingleFailureStrategy(FailureStrategy strategy, Long channelId, String modelConfigName) {
        long windowMs = getWindowMs(strategy.getWindowDimension());
        if (windowMs <= 0 || strategy.getFailureThreshold() <= 0) {
            return;
        }

        String modelPart = (modelConfigName != null && !modelConfigName.isEmpty()) ? modelConfigName : "all";
        String counterKey = FAILURE_COUNTER_PREFIX + strategy.getId() + ":" + channelId + ":" + modelPart;
        String windowType = strategy.getWindowType() != null ? strategy.getWindowType() : "SLIDING";
        long now = System.currentTimeMillis();

        boolean thresholdReached = checkFailureWindowCounter(counterKey, windowMs, strategy.getFailureThreshold(),
                now, windowType);

        if (thresholdReached) {
            triggerFailureCircuitBreaker(strategy, channelId, modelConfigName);
            memSlidingCounters.remove(counterKey);
            memFixedCounters.remove(counterKey);
        }
    }

    // ==================== Fused Channel Queries ====================

    /**
     * 获取当前处于熔断状态的渠道 ID 集合（供 ChannelRouter 热路径使用，2 秒本地缓存）
     */
    public Set<Long> getFusedChannelIds() {
        long now = System.currentTimeMillis();
        if (now - fusedChannelIdsCachedAt < FUSED_CACHE_TTL_MS) {
            return fusedChannelIdsCache;
        }
        synchronized (this) {
            if (now - fusedChannelIdsCachedAt < FUSED_CACHE_TTL_MS) {
                return fusedChannelIdsCache;
            }
            Set<Long> fused = loadFusedChannelIds();
            fusedChannelIdsCache = fused;
            fusedChannelIdsCachedAt = System.currentTimeMillis();
            return fused;
        }
    }

    /**
     * 检查指定渠道+模型是否处于熔断状态
     */
    public boolean isChannelFusedForModel(Long channelId, String modelConfigName) {
        String channelFuseKey = buildFuseKey(channelId, null);
        if (isFused(channelFuseKey)) {
            return true;
        }
        if (modelConfigName != null && !modelConfigName.isEmpty()) {
            String modelFuseKey = buildFuseKey(channelId, modelConfigName);
            return isFused(modelFuseKey);
        }
        return false;
    }

    /**
     * 通过 channelModelId 检查渠道+模型熔断状态
     */
    public boolean isChannelFusedForModelId(Long channelId, String channelModelId) {
        String channelFuseKey = buildFuseKey(channelId, null);
        if (isFused(channelFuseKey)) {
            return true;
        }
        if (channelModelId != null && !channelModelId.isEmpty()) {
            String resolvedName = resolveChannelModelIdToName(channelModelId);
            String modelFuseKey = buildFuseKey(channelId, resolvedName);
            if (isFused(modelFuseKey)) {
                return true;
            }
            if (!resolvedName.equals(channelModelId)) {
                String rawFuseKey = buildFuseKey(channelId, channelModelId);
                return isFused(rawFuseKey);
            }
        }
        return false;
    }

    // ==================== Model ID Resolution ====================

    String resolveChannelModelIdToName(String channelModelId) {
        if (channelModelId == null || channelModelId.isEmpty()) return channelModelId;
        try {
            Long.parseLong(channelModelId);
        } catch (NumberFormatException e) {
            return channelModelId;
        }
        String cached = modelNameCache.get(channelModelId);
        if (cached != null) return cached;

        ModelConfigService modelConfigService = modelConfigServiceProvider.getIfAvailable();
        if (modelConfigService != null) {
            try {
                Long id = Long.parseLong(channelModelId);
                com.aiconnecting.entity.ModelConfig config = modelConfigService.getById(id);
                if (config != null && config.getName() != null) {
                    modelNameCache.put(channelModelId, config.getName());
                    return config.getName();
                }
            } catch (Exception e) {
                log.debug("解析 channelModelId 失败: {}", channelModelId);
            }
        }
        return channelModelId;
    }

    public void invalidateFusedCache() {
        fusedChannelIdsCachedAt = 0;
    }

    @Scheduled(fixedRate = 60000)
    public void expireOldRecords() {
        try {
            circuitBreakerRecordRepository.expireOldRecords(LocalDateTime.now());
        } catch (Exception e) {
            log.warn("过期熔断记录清理失败: {}", e.getMessage());
        }
    }

    // ==================== Internal: Policy Matching ====================

    private List<RiskPolicy> findApplicablePolicies(Long channelId, String modelConfigName) {
        List<RiskPolicy> result = new ArrayList<>();
        if (modelConfigName != null && !modelConfigName.isEmpty()) {
            result.addAll(riskPolicyRepository.findEnabledByChannelAndModel(channelId, modelConfigName));
        }
        List<RiskPolicy> channelPolicies = riskPolicyRepository.findEnabledByChannelId(channelId);
        for (RiskPolicy p : channelPolicies) {
            if (p.getModelConfigName() == null || p.getModelConfigName().isEmpty()) {
                result.add(p);
            }
        }
        return result;
    }

    // ==================== Internal: Rate Limit Check ====================

    private boolean checkPolicyRateLimit(RiskPolicy policy, Long channelId, String modelConfigName) {
        long windowMs = getWindowMs(policy.getTimeWindow());
        if (windowMs <= 0 || policy.getRateLimit() <= 0) {
            return false;
        }

        String counterKey = buildCounterKey(channelId, modelConfigName, policy.getTimeWindow());
        String windowType = policy.getWindowType() != null ? policy.getWindowType() : "SLIDING";
        long now = System.currentTimeMillis();

        RedisTemplate<String, Long> redisTemplate = redisTemplateProvider.getIfAvailable();
        RedisScript<Long> script = rateLimitScriptProvider.getIfAvailable();

        if (redisTemplate != null && script != null && "SLIDING".equalsIgnoreCase(windowType)) {
            try {
                Long result = redisTemplate.execute(
                        script,
                        Collections.singletonList(counterKey),
                        windowMs,
                        (long) policy.getRateLimit(),
                        now
                );
                return result != null && result == 0L;
            } catch (Exception e) {
                log.error("Redis 风险策略限流检查异常，降级放行: {} - {}", e.getClass().getName(), e.getMessage());
            }
        }

        if (redisTemplate != null && "FIXED".equalsIgnoreCase(windowType)) {
            try {
                long windowStart = now - (now % windowMs);
                String bucketKey = counterKey + ":" + windowStart;
                Long count = redisTemplate.opsForValue().increment(bucketKey);
                if (count != null && count == 1) {
                    long ttlMs = windowMs - (now - windowStart);
                    redisTemplate.expire(bucketKey, ttlMs, TimeUnit.MILLISECONDS);
                }
                return count != null && count > policy.getRateLimit();
            } catch (Exception e) {
                log.error("Redis 固定窗口限流检查异常，降级放行: {} - {}", e.getClass().getName(), e.getMessage());
            }
        }

        return checkMemoryRateLimit(counterKey, windowMs, policy.getRateLimit(), now, windowType);
    }

    // ==================== Internal: Window Counter (shared by rate-limit & failure) ====================

    private boolean checkMemorySlidingWindow(String key, long windowMs, int maxCount, long now) {
        Deque<Long> deque = memSlidingCounters.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && now - deque.peekFirst() > windowMs) {
                deque.pollFirst();
            }
            if (deque.size() >= maxCount) {
                return true;
            }
            deque.addLast(now);
            return false;
        }
    }

    private boolean checkMemoryFixedWindow(String key, long windowMs, int maxCount, long now) {
        long[] state = memFixedCounters.compute(key, (k, existing) -> {
            if (existing == null) return new long[]{now, 1};
            long windowStart = existing[0];
            if (now - windowStart >= windowMs) {
                return new long[]{now, 1};
            }
            existing[1]++;
            return existing;
        });
        return state[1] > maxCount;
    }

    private boolean checkFailureSlidingWindow(String key, long windowMs, int threshold, long now) {
        Deque<Long> deque = memSlidingCounters.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && now - deque.peekFirst() > windowMs) {
                deque.pollFirst();
            }
            deque.addLast(now);
            return deque.size() >= threshold;
        }
    }

    private boolean checkFailureFixedWindow(String key, long windowMs, int threshold, long now) {
        long[] state = memFixedCounters.compute(key, (k, existing) -> {
            if (existing == null) return new long[]{now, 1};
            long windowStart = existing[0];
            if (now - windowStart >= windowMs) {
                return new long[]{now, 1};
            }
            existing[1]++;
            return existing;
        });
        return state[1] >= threshold;
    }

    private boolean checkFailureWindowCounter(String key, long windowMs, int threshold, long now, String windowType) {
        if ("FIXED".equalsIgnoreCase(windowType)) {
            return checkFailureFixedWindow(key, windowMs, threshold, now);
        }
        return checkFailureSlidingWindow(key, windowMs, threshold, now);
    }

    private boolean checkMemoryRateLimit(String key, long windowMs, int maxRequests, long now, String windowType) {
        if ("FIXED".equalsIgnoreCase(windowType)) {
            return checkMemoryFixedWindow(key, windowMs, maxRequests, now);
        }
        return checkMemorySlidingWindow(key, windowMs, maxRequests, now);
    }

    // ==================== Internal: Failure Strategy Matching ====================

    List<FailureStrategy> findAllMatchingFailureStrategies(Long channelId, Long modelConfigId, int httpCode) {
        List<FailureStrategy> strategies = getEnabledFailureStrategies();
        List<FailureStrategy> result = new ArrayList<>();
        for (FailureStrategy strategy : strategies) {
            if (!matchesHttpCodes(strategy.getHttpCodes(), httpCode)) continue;
            if ("GLOBAL".equals(strategy.getScope())) {
                if (matchesModel(strategy, modelConfigId)) {
                    result.add(strategy);
                }
            } else if ("CHANNEL".equals(strategy.getScope())) {
                if (!channelId.equals(strategy.getChannelId())) continue;
                if (matchesModel(strategy, modelConfigId)) {
                    result.add(strategy);
                }
            }
        }
        return result;
    }

    private boolean matchesModel(FailureStrategy strategy, Long modelConfigId) {
        if (strategy.getModelConfigId() == null) return true;
        if (modelConfigId == null) return false;
        return strategy.getModelConfigId().equals(modelConfigId);
    }

    private List<FailureStrategy> getEnabledFailureStrategies() {
        long now = System.currentTimeMillis();
        if (now - cachedStrategiesLoadedAt < STRATEGY_CACHE_TTL_MS) {
            return cachedEnabledStrategies;
        }
        List<FailureStrategy> loaded = failureStrategyRepository.findAllEnabledOrderByPriorityAsc();
        cachedEnabledStrategies = loaded;
        cachedStrategiesLoadedAt = now;
        return loaded;
    }

    private Long parseModelConfigId(String channelModelId) {
        if (channelModelId == null || channelModelId.isEmpty()) return null;
        try {
            return Long.parseLong(channelModelId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void invalidateFailureStrategyCache() {
        cachedStrategiesLoadedAt = 0;
    }

    static boolean matchesHttpCodes(String httpCodesStr, int httpCode) {
        if (httpCodesStr == null || httpCodesStr.isBlank()) return false;
        for (String part : httpCodesStr.split(",")) {
            String trimmed = part.trim().toLowerCase();
            if (trimmed.isEmpty()) continue;
            if (trimmed.endsWith("xx")) {
                try {
                    int prefix = Integer.parseInt(trimmed.substring(0, trimmed.length() - 2));
                    if (httpCode / 100 == prefix) return true;
                } catch (NumberFormatException ignored) {
                }
            } else {
                try {
                    if (Integer.parseInt(trimmed) == httpCode) return true;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return false;
    }

    // ==================== Internal: Circuit Breaker Triggering ====================

    private void triggerRateLimitCircuitBreaker(RiskPolicy policy, Long channelId, String modelConfigName) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusSeconds(policy.getCircuitBreakerDuration());

        CircuitBreakerRecord record = CircuitBreakerRecord.builder()
                .policyId(policy.getId())
                .channelId(channelId)
                .modelConfigName(modelConfigName)
                .source("AUTO_RATE")
                .triggeredAt(now)
                .expiresAt(expiresAt)
                .status("ACTIVE")
                .build();

        try {
            circuitBreakerRecordRepository.save(record);
        } catch (Exception e) {
            log.error("保存熔断记录失败: {}", e.getMessage());
        }

        String fuseKey = buildFuseKey(channelId, modelConfigName);
        setFuse(fuseKey, expiresAt);
        invalidateFusedCache();

        log.warn("限速策略触发熔断: policyId={}, channelId={}, model={}, 熔断至 {}",
                policy.getId(), channelId, modelConfigName, expiresAt);
    }

    private void triggerFailureCircuitBreaker(FailureStrategy strategy, Long channelId, String modelConfigName) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusSeconds(strategy.getFuseDurationSeconds());

        if (!shouldWriteFailureFuse(channelId, modelConfigName, expiresAt)) {
            log.debug("已有更晚或等期的熔断记录，跳过写入: strategyId={}, channelId={}, model={}",
                    strategy.getId(), channelId, modelConfigName);
            return;
        }

        String resolvedModelName = modelConfigName;

        CircuitBreakerRecord record = CircuitBreakerRecord.builder()
                .policyId(strategy.getId())
                .channelId(channelId)
                .modelConfigName(resolvedModelName)
                .source("AUTO_FAILURE")
                .reason("失败策略#" + strategy.getId() + "触发")
                .triggeredAt(now)
                .expiresAt(expiresAt)
                .status("ACTIVE")
                .build();

        try {
            circuitBreakerRecordRepository.save(record);
        } catch (Exception e) {
            log.error("保存失败策略熔断记录失败: {}", e.getMessage());
        }

        String fuseKey = buildFuseKey(channelId, resolvedModelName);
        setFuse(fuseKey, expiresAt);
        invalidateFusedCache();

        log.warn("失败策略触发熔断: strategyId={}, channelId={}, model={}, 熔断至 {}",
                strategy.getId(), channelId, resolvedModelName, expiresAt);
    }

    // ==================== Internal: Fuse State Management ====================

    private boolean isFused(String fuseKey) {
        RedisTemplate<String, Long> redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate != null) {
            try {
                Long expiresAt = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + fuseKey);
                if (expiresAt != null && expiresAt > System.currentTimeMillis()) {
                    return true;
                }
                if (expiresAt != null) {
                    redisTemplate.delete(REDIS_KEY_PREFIX + fuseKey);
                }
                return false;
            } catch (Exception e) {
                log.debug("Redis 熔断状态读取异常，降级到内存: {}", e.getMessage());
            }
        }

        Long memExpires = memFusedExpiry.get(fuseKey);
        if (memExpires != null && memExpires > System.currentTimeMillis()) {
            return true;
        }
        if (memExpires != null) {
            memFusedExpiry.remove(fuseKey);
        }
        return false;
    }

    private void setFuse(String fuseKey, LocalDateTime expiresAt) {
        long expiresMs = expiresAt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        RedisTemplate<String, Long> redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate != null) {
            try {
                long ttlMs = expiresMs - System.currentTimeMillis();
                if (ttlMs > 0) {
                    redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + fuseKey, expiresMs, ttlMs, TimeUnit.MILLISECONDS);
                }
                return;
            } catch (Exception e) {
                log.debug("Redis 熔断状态写入异常，降级到内存: {}", e.getMessage());
            }
        }
        memFusedExpiry.put(fuseKey, expiresMs);
    }

    private void removeFuse(String fuseKey) {
        RedisTemplate<String, Long> redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(REDIS_KEY_PREFIX + fuseKey);
                return;
            } catch (Exception e) {
                log.debug("Redis 熔断状态删除异常，降级到内存: {}", e.getMessage());
            }
        }
        memFusedExpiry.remove(fuseKey);
    }

    private Set<Long> loadFusedChannelIds() {
        Set<Long> result = new HashSet<>();
        RedisTemplate<String, Long> redisTemplate = redisTemplateProvider.getIfAvailable();

        if (redisTemplate != null) {
            try {
                Set<String> keys = redisTemplate.keys(REDIS_KEY_PREFIX + "channel:*");
                if (keys != null) {
                    for (String key : keys) {
                        Long expiresAt = redisTemplate.opsForValue().get(key);
                        if (expiresAt != null && expiresAt > System.currentTimeMillis()) {
                            String channelIdStr = key.substring((REDIS_KEY_PREFIX + "channel:").length());
                            try {
                                result.add(Long.parseLong(channelIdStr));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
                Set<String> modelKeys = redisTemplate.keys(REDIS_KEY_PREFIX + "model:*");
                if (modelKeys != null) {
                    for (String key : modelKeys) {
                        Long expiresAt = redisTemplate.opsForValue().get(key);
                        if (expiresAt != null && expiresAt > System.currentTimeMillis()) {
                            String rest = key.substring((REDIS_KEY_PREFIX + "model:").length());
                            int colonIdx = rest.indexOf(':');
                            if (colonIdx > 0) {
                                try {
                                    result.add(Long.parseLong(rest.substring(0, colonIdx)));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                    }
                }
                return result;
            } catch (Exception e) {
                log.debug("Redis 熔断渠道加载异常，降级到内存: {}", e.getMessage());
            }
        }

        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : memFusedExpiry.entrySet()) {
            if (entry.getValue() > now) {
                String key = entry.getKey();
                if (key.startsWith("channel:")) {
                    try {
                        result.add(Long.parseLong(key.substring("channel:".length())));
                    } catch (NumberFormatException ignored) {
                    }
                } else if (key.startsWith("model:")) {
                    String rest = key.substring("model:".length());
                    int colonIdx = rest.indexOf(':');
                    if (colonIdx > 0) {
                        try {
                            result.add(Long.parseLong(rest.substring(0, colonIdx)));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        }
        return result;
    }

    // ==================== Internal: Circuit Breaker Dedup ====================

    /**
     * 跨策略判断是否需要写入新的失败熔断记录：按「渠道+模型」维度查询所有未到期记录（不看 strategyId），
     * 仅当本次到期时间严格晚于现有最晚到期时间时才写入（以更晚到期为准，覆盖/延长）。
     * 渠道级（model 空）与渠道+模型级各查各的维度；手动熔断（MANUAL）也参与比较。
     */
    private boolean shouldWriteFailureFuse(Long channelId, String modelConfigName, LocalDateTime newExpiresAt) {
        try {
            LocalDateTime now = LocalDateTime.now();
            List<CircuitBreakerRecord> existing;
            if (modelConfigName != null && !modelConfigName.isEmpty()) {
                existing = circuitBreakerRecordRepository.findActiveByChannelAndModel(channelId, modelConfigName, now);
            } else {
                existing = circuitBreakerRecordRepository.findActiveByChannelIdAndModelIsNull(channelId, now);
            }
            if (existing == null || existing.isEmpty()) {
                return true;
            }
            LocalDateTime maxExpiry = existing.stream()
                    .map(CircuitBreakerRecord::getExpiresAt)
                    .max(LocalDateTime::compareTo)
                    .orElse(null);
            return maxExpiry == null || newExpiresAt.isAfter(maxExpiry);
        } catch (Exception e) {
            log.warn("检查熔断去重失败，继续写入: {}", e.getMessage());
            return true;
        }
    }

    @PreDestroy
    void shutdownFailureExecutor() {
        failureExecutor.shutdown();
        try {
            if (!failureExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                failureExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            failureExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ==================== Internal: Key Builders ====================

    private String buildFuseKey(Long channelId, String modelConfigName) {
        if (modelConfigName != null && !modelConfigName.isEmpty()) {
            return "model:" + channelId + ":" + modelConfigName;
        }
        return "channel:" + channelId;
    }

    private String buildCounterKey(Long channelId, String modelConfigName, String timeWindow) {
        String modelPart = (modelConfigName != null && !modelConfigName.isEmpty()) ? modelConfigName : "all";
        return COUNTER_KEY_PREFIX + channelId + ":" + modelPart + ":" + timeWindow;
    }

    static long getWindowMs(String timeWindow) {
        if (timeWindow == null) return 0;
        return switch (timeWindow.toUpperCase()) {
            case "MINUTE" -> 60 * 1000L;
            case "HOUR" -> 60 * 60 * 1000L;
            case "DAY" -> 24 * 60 * 60 * 1000L;
            default -> 0;
        };
    }
}
