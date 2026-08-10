package com.aiconnecting.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型级健康跟踪：按 (channelId, modelConfigId) 记录冷却截止时间，供模型组故障转移在选择成员时
 * 跳过近期失败的 渠道+成员模型 组合。与既有 {@link ChannelHealthTracker}（渠道级熔断）完全独立，
 * 不影响非模型组请求的既有渠道级重试/熔断逻辑。
 * v1 使用本地 ConcurrentHashMap（单实例内有效）；集群模式下可后续替换为 Redis，接口保持不变。
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

    public enum FailureType { RATE_LIMIT, QUOTA, MODEL_NOT_FOUND }

    private record Key(Long channelId, Long modelConfigId) {}

    private final ConcurrentHashMap<Key, Long> cooldownUntil = new ConcurrentHashMap<>();

    private Key key(Long channelId, Long modelConfigId) {
        return new Key(channelId, modelConfigId);
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
        };
        long until = System.currentTimeMillis() + cooldownMs;
        cooldownUntil.merge(key(channelId, modelConfigId), until, Math::max);
    }

    public void recordSuccess(Long channelId, Long modelConfigId) {
        if (channelId == null || modelConfigId == null) {
            return;
        }
        cooldownUntil.remove(key(channelId, modelConfigId));
    }

    /** 该 渠道+模型 组合当前是否处于冷却期（近期失败，暂不参与选择） */
    public boolean isInCooldown(Long channelId, Long modelConfigId) {
        if (channelId == null || modelConfigId == null) {
            return false;
        }
        Long until = cooldownUntil.get(key(channelId, modelConfigId));
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            cooldownUntil.remove(key(channelId, modelConfigId), until);
            return false;
        }
        return true;
    }

    /** 该模型（不区分渠道）是否所有已知渠道组合均处于冷却，用于判断是否跳过该成员模型 */
    public boolean isModelFullyInCooldown(Long modelConfigId, java.util.List<Long> candidateChannelIds) {
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
