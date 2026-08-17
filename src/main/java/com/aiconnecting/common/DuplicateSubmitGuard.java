package com.aiconnecting.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis-backed short-lived guard for duplicate admin submissions.
 *
 * <p>Keys deliberately live for the full 30-second window and are not released when a request
 * finishes. When Redis is disabled or temporarily unavailable the guard fails open so local and
 * single-instance deployments remain usable; database constraints remain the durable uniqueness
 * backstop for entities that define them.</p>
 */
@Slf4j
@Component
public class DuplicateSubmitGuard {

    static final Duration DEDUP_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;

    public DuplicateSubmitGuard(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                @Value("${APP_ENV:default}") String appEnvironment) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        String env = appEnvironment == null || appEnvironment.isBlank()
                ? "default" : appEnvironment.trim();
        this.keyPrefix = "ai-connecting:" + env + ":dedup:";
    }

    /**
     * Atomically reserves {@code dedup:{entityType}:{identifier}} for 30 seconds.
     *
     * @return {@code false} only when Redis confirms that the key already exists; Redis being
     * disabled or unavailable degrades to {@code true} (operation allowed).
     */
    public boolean tryAcquire(String entityType, String identifier) {
        if (entityType == null || entityType.isBlank() || identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("entityType and identifier must not be blank");
        }
        if (redisTemplate == null) {
            return true;
        }
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(keyPrefix + entityType + ":" + identifier, "1", DEDUP_TTL);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.warn("Redis 防重复提交不可用，降级为允许请求: entity={}, error={}",
                    entityType, e.getMessage());
            return true;
        }
    }
}
