package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * 基于 Redis 的分布式限流服务
 * 使用 Lua 脚本 + 滑动窗口算法保证原子性
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true")
public class RateLimitService {

    private final RedisTemplate<String, Long> redisTemplate;
    private final RedisScript<Long> rateLimitScript;

    /** 限流窗口：1 分钟（毫秒） */
    private static final long WINDOW_SIZE_MS = 60 * 1000L;

    /**
     * 检查渠道是否允许请求
     *
     * @param channelId 渠道 ID
     * @param maxRequests 每分钟最大请求数（0 表示不限）
     * @throws BusinessException 如果超过限流阈值
     */
    public void checkChannelRateLimit(Long channelId, Integer maxRequests) {
        log.debug("[限流检查] channelId={}, maxRequests={}", channelId, maxRequests);
        if (maxRequests == null || maxRequests <= 0) {
            log.debug("渠道 {} 不限流", channelId);
            return; // 不限流
        }

        String key = "rate_limit:channel:" + channelId;
        long now = System.currentTimeMillis();

        try {
            Long result = redisTemplate.execute(
                    rateLimitScript,
                    Collections.singletonList(key),
                    WINDOW_SIZE_MS,
                    (long) maxRequests,
                    now
            );

            if (result == null || result == 0L) {
                log.warn("❌ [限流触发] 渠道 {} 请求频率超过限制: {}/min", channelId, maxRequests);
                throw new BusinessException(429,
                        String.format("请求过于频繁，该渠道限制为每分钟 %d 次请求，请稍后重试", maxRequests));
            } else {
                log.info("✅ [限流通过] 渠道 {} 当前请求已放行 (限制: {}/min)", channelId, maxRequests);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // Redis 异常时降级为放行，避免影响正常业务
            log.error("[限流降级] Redis 限流检查异常，降级放行: {} - {}", e.getClass().getName(), e.getMessage(), e);
        }
    }

    /**
     * 检查 Token 是否允许请求（可选：针对 Token 维度的限流）
     *
     * @param tokenId Token ID
     * @param maxRequests 每分钟最大请求数（0 表示不限）
     */
    public void checkTokenRateLimit(Long tokenId, Integer maxRequests) {
        if (maxRequests == null || maxRequests <= 0) {
            return;
        }

        String key = "rate_limit:token:" + tokenId;
        long now = System.currentTimeMillis();

        try {
            Long result = redisTemplate.execute(
                    rateLimitScript,
                    Collections.singletonList(key),
                    WINDOW_SIZE_MS,
                    (long) maxRequests,
                    now
            );

            if (result == null || result == 0L) {
                log.warn("Token {} 请求频率超过限制: {}/min", tokenId, maxRequests);
                throw new BusinessException(429,
                        String.format("请求过于频繁，该 Token 限制为每分钟 %d 次请求，请稍后重试", maxRequests));
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Redis Token 限流检查异常，降级放行: {}", e.getMessage());
        }
    }

    /**
     * 获取渠道当前窗口内的请求数（用于监控/展示）
     */
    public long getChannelCurrentRate(Long channelId) {
        String key = "rate_limit:channel:" + channelId;
        long now = System.currentTimeMillis();
        try {
            Long count = redisTemplate.opsForZSet().count(key, now - WINDOW_SIZE_MS, now);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.warn("获取渠道请求频率失败: {}", e.getMessage());
            return 0;
        }
    }
}
