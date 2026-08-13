package com.aiconnecting.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型级健康跟踪：按 (channelId, modelConfigId) 记录冷却截止时间，供模型组故障转移在选择成员时
 * 跳过近期失败的 渠道+成员模型 组合。与既有 {@link ChannelHealthTracker}（渠道级熔断）完全独立，
 * 不影响非模型组请求的既有渠道级重试/熔断逻辑。
 * 冷却状态存储于 Redis（集群间共享、重启不丢失）；Redis 未启用（{@code app.redis.enabled=false}）
 * 或运行时访问异常时，降级为本地 ConcurrentHashMap（与 {@link com.aiconnecting.common.RedisDistributedLock}
 * 相同的降级模式）。接口保持不变。
 */
@Service
@Slf4j
public class ModelHealthTracker {

    /** 429 限流冷却（无 Retry-After 时的默认值） */
    static final long RATE_LIMIT_COOLDOWN_MS = 30_000L;
    /** 配额错误冷却 */
    static final long QUOTA_COOLDOWN_MS = 60_000L;
    /** model_not_found 冷却 */
    static final long MODEL_NOT_FOUND_COOLDOWN_MS = 5 * 60_000L;
    /** 其他成员级失败冷却，避免模型组在连续请求中反复选择同一失败成员 */
    static final long MEMBER_FAILURE_COOLDOWN_MS = 30_000L;

    /** Redis key TTL 相对冷却时长的冗余秒数，避免刚好在冷却结束边界前 key 被系统提前清除 */
    private static final long TTL_SLACK_SECONDS = 60L;

    public enum FailureType { RATE_LIMIT, QUOTA, MODEL_NOT_FOUND, MEMBER_FAILURE }

    private record Key(Long channelId, Long modelConfigId) {}

    /** Redis 未启用/异常时的降级存储；Redis 可用时不参与读写（避免与 Redis 状态不一致）。 */
    private final ConcurrentHashMap<Key, Long> cooldownUntil = new ConcurrentHashMap<>();

    private final RedisTemplate<String, Long> redisTemplate;

    /** key 命名空间前缀，隔离共享 Redis 的不同环境 */
    private final String keyPrefix;

    public ModelHealthTracker(ObjectProvider<RedisTemplate<String, Long>> redisTemplateProvider,
                               @Value("${APP_ENV:default}") String appEnvironment) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        String env = (appEnvironment == null || appEnvironment.isBlank())
                ? "default" : appEnvironment.trim();
        this.keyPrefix = "ai-connecting:" + env + ":model:cd:";
    }

    private Key key(Long channelId, Long modelConfigId) {
        return new Key(channelId, modelConfigId);
    }

    private String redisKey(Long channelId, Long modelConfigId) {
        return keyPrefix + channelId + ":" + modelConfigId;
    }

    public void recordFailure(Long channelId, Long modelConfigId, FailureType type) {
        recordFailure(channelId, modelConfigId, type, null);
    }

    /**
     * @param retryAfterSeconds 上游返回的 Retry-After/配额重置提示（秒）；为 null 时使用默认冷却时长
     */
    public void recordFailure(Long channelId, Long modelConfigId, FailureType type, Long retryAfterSeconds) {
        if (channelId == null || modelConfigId == null) {
            return;
        }
        long cooldownMs = switch (type) {
            case RATE_LIMIT -> (retryAfterSeconds != null && retryAfterSeconds > 0)
                    ? retryAfterSeconds * 1000L : RATE_LIMIT_COOLDOWN_MS;
            case QUOTA -> (retryAfterSeconds != null && retryAfterSeconds > 0)
                    ? retryAfterSeconds * 1000L : QUOTA_COOLDOWN_MS;
            case MODEL_NOT_FOUND -> MODEL_NOT_FOUND_COOLDOWN_MS;
            case MEMBER_FAILURE -> MEMBER_FAILURE_COOLDOWN_MS;
        };
        long until = System.currentTimeMillis() + cooldownMs;

        if (redisTemplate == null) {
            cooldownUntil.merge(key(channelId, modelConfigId), until, Math::max);
            return;
        }
        String redisKeyStr = redisKey(channelId, modelConfigId);
        try {
            Long existing = redisTemplate.opsForValue().get(redisKeyStr);
            long effectiveUntil = (existing != null) ? Math.max(existing, until) : until;
            long ttlSeconds = Math.max(1L, (effectiveUntil - System.currentTimeMillis()) / 1000L) + TTL_SLACK_SECONDS;
            redisTemplate.opsForValue().set(redisKeyStr, effectiveUntil, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("写入模型冷却状态到 Redis 异常，降级为本地状态: channelId={}, modelConfigId={}, error={}",
                    channelId, modelConfigId, e.getMessage());
            cooldownUntil.merge(key(channelId, modelConfigId), until, Math::max);
        }
    }

    public void recordSuccess(Long channelId, Long modelConfigId) {
        if (channelId == null || modelConfigId == null) {
            return;
        }
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(redisKey(channelId, modelConfigId));
            } catch (Exception e) {
                log.warn("清除模型冷却状态（Redis）异常: channelId={}, modelConfigId={}, error={}",
                        channelId, modelConfigId, e.getMessage());
            }
        }
        // 无论 Redis 是否可用/是否成功，都清理本地降级状态，防止此前降级期间写入的本地条目残留
        cooldownUntil.remove(key(channelId, modelConfigId));
    }

    /** 该 渠道+模型 组合当前是否处于冷却期（近期失败，暂不参与选择） */
    public boolean isInCooldown(Long channelId, Long modelConfigId) {
        if (channelId == null || modelConfigId == null) {
            return false;
        }
        if (redisTemplate == null) {
            return isInLocalCooldown(channelId, modelConfigId);
        }
        String redisKeyStr = redisKey(channelId, modelConfigId);
        try {
            Long until = redisTemplate.opsForValue().get(redisKeyStr);
            if (until == null) {
                return false;
            }
            if (System.currentTimeMillis() >= until) {
                redisTemplate.delete(redisKeyStr);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("读取模型冷却状态（Redis）异常，降级为本地状态: channelId={}, modelConfigId={}, error={}",
                    channelId, modelConfigId, e.getMessage());
            return isInLocalCooldown(channelId, modelConfigId);
        }
    }

    private boolean isInLocalCooldown(Long channelId, Long modelConfigId) {
        Key k = key(channelId, modelConfigId);
        Long until = cooldownUntil.get(k);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            cooldownUntil.remove(k, until);
            return false;
        }
        return true;
    }

    /** 该模型（不区分渠道）是否所有已知渠道组合均处于冷却，用于判断是否跳过该成员模型 */
    public boolean isModelFullyInCooldown(Long modelConfigId, List<Long> candidateChannelIds) {
        if (candidateChannelIds == null || candidateChannelIds.isEmpty()) {
            return false;
        }
        for (Long channelId : candidateChannelIds) {
            if (!isInCooldown(channelId, modelConfigId)) {
                return false;
            }
        }
        return true;
    }
}
