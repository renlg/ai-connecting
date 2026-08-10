package com.aiconnecting.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多实例缓存失效广播：写操作在更新本地缓存后调用 {@link #publish}，
 * 通过 Redis pub/sub 通知其他实例清除各自的本地缓存副本，实现最终一致。
 *
 * 降级策略：当 Redis 未启用（{@code app.redis.enabled=false}，此时 {@link StringRedisTemplate}
 * bean 不存在）时，{@link #publish} 直接空操作且不会产生进程内事件；运行时访问 Redis 异常也只记录告警。
 * 因此未配置 Redis 的部署保持接入前的纯 TTL/原有本地驱逐行为。
 *
 * 依赖通过 {@link ObjectProvider} 惰性获取（同 {@link RedisDistributedLock} 的做法），
 * 避免 Redis bean 缺失时导致容器启动失败。
 */
@Slf4j
@Component
public class CacheInvalidationService implements MessageListener {

    /** 缓存失效广播频道名，所有实例统一订阅 */
    public static final String CHANNEL = "cache:invalidate";
    public static final String CHANNEL_LIST = "channelList";
    public static final String MODEL_CONFIG = "modelConfig";
    public static final String USER_PREFIX = "user:";
    public static final String TOKEN_ID_PREFIX = "tokenId:";
    public static final String USAGE_STATS = "usageStats";

    private static final String USER_CACHE = "user";
    private static final String TOKEN_CACHE = "token";
    private final ConcurrentHashMap<String, AtomicLong> generations = new ConcurrentHashMap<>();

    private final StringRedisTemplate redisTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public CacheInvalidationService(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                    ApplicationEventPublisher eventPublisher) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.eventPublisher = eventPublisher;
    }

    /**
     * 发布一条缓存失效消息。message 格式约定为紧凑的路由字符串，如 "channelList"、"modelConfig"、
     * "user:12"、"tokenId:9"、"usageStats"，由监听端路由到具体缓存的清除方法。
     * 凭证原文绝不得作为路由的一部分；Token 只使用数据库 ID。
     */
    public void publish(String message) {
        if (redisTemplate == null || !isValidRoute(message)) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishNow(message);
                }
            });
            return;
        }
        publishNow(message);
    }

    private void publishNow(String message) {
        try {
            redisTemplate.convertAndSend(CHANNEL, message);
        } catch (Exception e) {
            log.warn("发布缓存失效消息异常，其他实例可能暂时看到旧缓存（等待 TTL 过期）: route={}, error={}",
                    safeRouteForLog(message), e.getMessage());
        }
    }

    /**
     * Redis 订阅回调。Redis 会把消息投递给所有订阅实例（也包括发布实例本身），统一转成
     * Spring 进程内事件后由各缓存按精确路由处理。未知消息没有监听者，天然被忽略。
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String route = new String(message.getBody(), StandardCharsets.UTF_8);
            if (!isValidRoute(route)) {
                return;
            }
            generations.computeIfAbsent(generationKey(route), ignored -> new AtomicLong()).incrementAndGet();
            eventPublisher.publishEvent(new CacheInvalidationEvent(route));
        } catch (Exception e) {
            log.warn("处理缓存失效消息异常，已忽略: error={}", e.getMessage());
        }
    }

    /** 仅由 Redis 订阅回调产生，Redis 未配置时不会产生本地失效事件。 */
    public record CacheInvalidationEvent(String route) {
    }

    /**
     * 返回路由对应缓存的当前世代。缓存加载器在查库前捕获它，写缓存前再比较，
     * 从而防止“查库中途收到失效广播”后把旧值回填进缓存。
     */
    public long generation(String route) {
        return generations.computeIfAbsent(generationKey(route), ignored -> new AtomicLong()).get();
    }

    public boolean isCurrentGeneration(String route, long generation) {
        return generation(route) == generation;
    }

    private boolean isValidRoute(String route) {
        if (route == null || route.isBlank() || route.length() > 512) {
            return false;
        }
        if (CHANNEL_LIST.equals(route) || MODEL_CONFIG.equals(route) || USAGE_STATS.equals(route)) {
            return true;
        }
        if (route.startsWith(USER_PREFIX)) {
            return isPositiveLong(route.substring(USER_PREFIX.length()));
        }
        if (route.startsWith(TOKEN_ID_PREFIX)) {
            return isPositiveLong(route.substring(TOKEN_ID_PREFIX.length()));
        }
        return false;
    }

    private String generationKey(String route) {
        if (route != null && route.startsWith(USER_PREFIX)) {
            return USER_CACHE;
        }
        if (route != null && route.startsWith(TOKEN_ID_PREFIX)) {
            return TOKEN_CACHE;
        }
        return route;
    }

    /** 防御性日志脱敏：未知路由可能是误传的密钥，绝不输出原文。 */
    private String safeRouteForLog(String route) {
        return isValidRoute(route) ? route : "<redacted-invalid-route>";
    }

    private boolean isPositiveLong(String value) {
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
