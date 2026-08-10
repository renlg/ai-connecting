package com.aiconnecting.common;

import jakarta.annotation.PreDestroy;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/**
 * Redis 分布式锁，用于保证多实例部署下 {@code @Scheduled} 任务不会被重复执行。
 *
 * 降级策略：当 Redis 未启用（{@code app.redis.enabled=false}，此时 {@link StringRedisTemplate}
 * bean 不存在）时，{@link #tryLock} 直接返回本地令牌、任务照常单机执行，
 * {@link #unlock} 对本地令牌为空操作——行为与未接入分布式锁前完全一致，不影响单实例部署
 * （集群模式下 Redis 缺失会在启动期由 {@link com.aiconnecting.config.ClusterConfigValidator} 直接拒绝启动，
 * 不会走到这里）。
 * 但运行时访问 Redis 异常（Redis 短暂不可用）时改为 FAIL-CLOSED：{@link #tryLock} 返回 {@code null}，
 * 本轮任务跳过不执行，避免"多实例同时误判持锁成功"导致的重复执行；等下一轮调度重试即可。
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
    private final RedisScript<Long> renewScript;

    /** 锁 key 命名空间前缀，避免共享 Redis 的不同环境之间互相锁定 */
    private final String keyPrefix;

    /** Redis disabled/unavailable fallback for single-instance coordination values. */
    private final ConcurrentHashMap<String, Long> localLongValues = new ConcurrentHashMap<>();

    /**
     * 锁续约（watchdog）调度器：任务运行期间每 TTL/3 秒对持有中的锁执行一次原子续约（Lua CAS PEXPIRE），
     * 使得只要任务仍在运行、TTL 就不会到期，从而可以把各任务的 TTL 设置得远小于其“理论最坏耗时”，
     * 只需覆盖到下一次续约之前的窗口即可；进程崩溃（无法再续约）时锁最迟在 TTL 内自然释放。
     * 线程数 2：本进程内同时持有的分布式锁数量很少（各 job 各一把），无需更大的并发度；
     * daemon 线程，不阻止 JVM 正常退出。
     */
    private final ScheduledExecutorService renewalScheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "redis-lock-renewal");
        t.setDaemon(true);
        return t;
    });

    /** redisKey（含前缀）-> 续约任务，用于 unlock / 续约失败时取消，避免线程/任务泄漏 */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> activeRenewals = new ConcurrentHashMap<>();

    public RedisDistributedLock(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                 @Qualifier("lockUnlockScript") ObjectProvider<RedisScript<Long>> lockUnlockScriptProvider,
                                 @Qualifier("renewLockScript") ObjectProvider<RedisScript<Long>> renewLockScriptProvider,
                                 @Value("${APP_ENV:default}") String appEnvironment) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.unlockScript = lockUnlockScriptProvider.getIfAvailable();
        this.renewScript = renewLockScriptProvider.getIfAvailable();
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
        String redisKey = keyPrefix + key;
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(redisKey, token, Duration.ofSeconds(ttlSeconds));
            if (Boolean.TRUE.equals(acquired)) {
                startRenewal(redisKey, token, ttlSeconds);
                return token;
            }
            return null;
        } catch (Exception e) {
            // FAIL-CLOSED：Redis 短暂异常时不知道其他实例是否已持锁，宁可本轮跳过，
            // 也不能像 LOCAL_TOKEN 那样让所有实例同时误判"我获取到了锁"而并发执行。
            log.warn("获取分布式锁异常，跳过本次执行（等待下一轮调度重试）: key={}, error={}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 释放锁。仅当 token 与当前持有者一致时才删除（Lua 脚本原子比较并删除），
     * 避免误删已被其他实例重新获取的锁（例如本实例执行超时、锁已自然过期后又被抢占的情况）。
     */
    public void unlock(String key, String token) {
        String redisKey = keyPrefix + key;
        if (!LOCAL_TOKEN.equals(token)) {
            cancelRenewal(redisKey);
        }
        if (redisTemplate == null || unlockScript == null || LOCAL_TOKEN.equals(token)) {
            return;
        }
        try {
            redisTemplate.execute(unlockScript, Collections.singletonList(redisKey), token);
        } catch (Exception e) {
            log.warn("释放分布式锁异常: key={}, error={}", key, e.getMessage());
        }
    }

    /**
     * 启动续约 watchdog：每 TTL/3 秒尝试续约一次（至少间隔 1 秒），使用 Lua CAS 保证只有仍持有该锁的
     * 实例才能续约成功；LOCAL_TOKEN 降级模式不会走到这里（调用方在 redisTemplate == null 时已提前返回）。
     */
    private void startRenewal(String redisKey, String token, long ttlSeconds) {
        if (renewScript == null) {
            return;
        }
        long intervalSeconds = Math.max(1, ttlSeconds / 3);
        long ttlMillis = ttlSeconds * 1000;
        ScheduledFuture<?> future = renewalScheduler.scheduleAtFixedRate(() -> {
            try {
                Long renewed = redisTemplate.execute(renewScript, Collections.singletonList(redisKey),
                        token, String.valueOf(ttlMillis));
                if (renewed == null || renewed == 0L) {
                    log.debug("锁续约未成功（锁已过期或被其他实例持有），停止续约: key={}", redisKey);
                    cancelRenewal(redisKey);
                }
            } catch (Exception e) {
                // 续约异常（Redis 短暂不可用）：不中断任务本身，仅记录告警；锁可能因此提前过期，
                // 由 tryLock 的 FAIL-CLOSED 语义兜底（届时另一实例最多与本实例短暂并发）。
                log.warn("锁续约异常: key={}, error={}", redisKey, e.getMessage());
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        ScheduledFuture<?> previous = activeRenewals.put(redisKey, future);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    private void cancelRenewal(String redisKey) {
        ScheduledFuture<?> future = activeRenewals.remove(redisKey);
        if (future != null) {
            future.cancel(false);
        }
    }

    /** 优雅关闭时取消所有续约任务，避免线程泄漏。 */
    @PreDestroy
    public void shutdown() {
        activeRenewals.values().forEach(f -> f.cancel(false));
        activeRenewals.clear();
        renewalScheduler.shutdownNow();
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
