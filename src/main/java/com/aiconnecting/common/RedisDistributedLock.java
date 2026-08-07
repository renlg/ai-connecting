package com.aiconnecting.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

/**
 * Redis 分布式锁，用于保证多实例部署下 {@code @Scheduled} 任务不会被重复执行。
 *
 * 降级策略：当 Redis 未启用（{@code app.rate-limit.enabled=false}，此时 {@link StringRedisTemplate}
 * bean 不存在）或运行时访问 Redis 异常时，{@link #tryLock} 直接返回本地令牌、任务照常单机执行，
 * {@link #unlock} 对本地令牌为空操作——行为与未接入分布式锁前完全一致，不影响单实例部署。
 *
 * 依赖通过 {@link ObjectProvider} 而非构造器参数直接注入：单构造器上的 {@code @Autowired(required=false)}
 * 不会使该参数变为可选（Spring 仍会因缺少 bean 而启动失败），只有 ObjectProvider/Optional 这类
 * 惰性/可缺省的注入方式才能在 Redis bean 不存在时让容器正常启动。
 */
@Slf4j
@Component
public class RedisDistributedLock {

    /** Redis 不可用时返回的哨兵令牌，标识本次为降级单机执行 */
    private static final String LOCAL_TOKEN = "LOCAL-DEGRADED";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> unlockScript;

    /** 锁 key 命名空间前缀，避免共享 Redis 的不同环境之间互相锁定 */
    private final String keyPrefix;

    /** Redis disabled/unavailable fallback for single-instance coordination values. */
    private final ConcurrentHashMap<String, Long> localLongValues = new ConcurrentHashMap<>();

    public RedisDistributedLock(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                 @Qualifier("lockUnlockScript") ObjectProvider<RedisScript<Long>> lockUnlockScriptProvider,
                                 @Value("${APP_ENV:default}") String appEnvironment) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.unlockScript = lockUnlockScriptProvider.getIfAvailable();
        // APP_ENV must be identical for every instance in one deployment and distinct between
        // environments sharing Redis. Unlike spring.profiles.active, it is stable across profile ordering.
        // Deployments upgrading from historical unprefixed job:* keys can overlap for one old-key lease;
        // roll this change when no scheduled job is active (or wait for the old maximum TTL).
        String env = (appEnvironment == null || appEnvironment.isBlank())
                ? "default" : appEnvironment.trim();
        this.keyPrefix = "ai-connecting:" + env + ":";
    }

    /**
     * 尝试获取锁。
     *
     * @param key        锁 key，须在所有实例间保持一致的静态字符串
     * @param ttlSeconds 锁过期时间（秒），必须大于任务预期最长执行时间，防止任务未完成锁已过期
     * @return 持有令牌（用于释放锁）；返回 null 表示锁被其他实例持有，本次应跳过任务
     */
    public String tryLock(String key, long ttlSeconds) {
        if (redisTemplate == null) {
            return LOCAL_TOKEN;
        }
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(keyPrefix + key, token, Duration.ofSeconds(ttlSeconds));
            return Boolean.TRUE.equals(acquired) ? token : null;
        } catch (Exception e) {
            log.warn("获取分布式锁异常，降级为单机执行: key={}, error={}", key, e.getMessage());
            return LOCAL_TOKEN;
        }
    }

    /**
     * 释放锁。仅当 token 与当前持有者一致时才删除（Lua 脚本原子比较并删除），
     * 避免误删已被其他实例重新获取的锁（例如本实例执行超时、锁已自然过期后又被抢占的情况）。
     */
    public void unlock(String key, String token) {
        if (redisTemplate == null || unlockScript == null || LOCAL_TOKEN.equals(token)) {
            return;
        }
        try {
            redisTemplate.execute(unlockScript, Collections.singletonList(keyPrefix + key), token);
        } catch (Exception e) {
            log.warn("释放分布式锁异常: key={}, error={}", key, e.getMessage());
        }
    }

    /**
     * 便捷方法：获取锁成功才执行 job，执行完毕（或异常）后释放锁；未获取到锁则记录日志后跳过。
     */
    public boolean runIfLocked(String key, long ttlSeconds, Runnable job) {
        String token = tryLock(key, ttlSeconds);
        if (token == null) {
            log.debug("未获取到分布式锁，跳过本次执行（其他实例正在处理）: key={}", key);
            return false;
        }
        try {
            job.run();
            return true;
        } finally {
            unlock(key, token);
        }
    }

    /**
     * Reads a namespaced long-lived coordination value. Compound read/modify/write operations must be
     * performed while holding the corresponding distributed lock.
     */
    public Long getLongValue(String key) {
        if (redisTemplate == null) {
            return localLongValues.get(key);
        }
        try {
            String value = redisTemplate.opsForValue().get(keyPrefix + key);
            return value != null ? Long.valueOf(value) : null;
        } catch (Exception e) {
            log.warn("读取分布式协调值异常，降级为单机值: key={}, error={}", key, e.getMessage());
            return localLongValues.get(key);
        }
    }

    /**
     * Writes a namespaced long-lived coordination value. See {@link #getLongValue(String)} for locking rules.
     */
    public void setLongValue(String key, long value) {
        localLongValues.put(key, value);
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(keyPrefix + key, Long.toString(value));
        } catch (Exception e) {
            log.warn("写入分布式协调值异常，已降级为单机值: key={}, error={}", key, e.getMessage());
        }
    }
}
