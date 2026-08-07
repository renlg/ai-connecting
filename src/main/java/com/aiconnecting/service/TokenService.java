package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.dto.TokenRequest;
import com.aiconnecting.entity.Token;
import com.aiconnecting.repository.TokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class TokenService {

    private final TokenRepository tokenRepository;
    private final CacheInvalidationService cacheInvalidationService;

    /** Token 验证缓存，减少数据库查询，缓存 30 秒（缩短以减少禁用/过期Token延迟） */
    private final ConcurrentHashMap<String, CachedToken> tokenCache = new ConcurrentHashMap<>();
    /** token ID -> token key 的本地反向索引，使 tokenId 广播可 O(1) 精确驱逐。 */
    private final ConcurrentHashMap<Long, String> tokenKeyById = new ConcurrentHashMap<>();
    private static final long TOKEN_CACHE_TTL_MS = 30 * 1000L;

    private record CachedToken(Token token, long cachedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > TOKEN_CACHE_TTL_MS;
        }
    }

    public List<Token> listByUser(Long userId) {
        return tokenRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    public List<Long> getUserTokenIds(Long userId) {
        return tokenRepository.findIdsByUserId(userId);
    }

    /**
     * 聚合查询指定用户的 Token 数量与已用额度总和，避免加载全部 Token 实体
     * @return Object[]{tokenCount, totalUsedQuota}
     */
    public Object[] getUserTokenStats(Long userId) {
        List<Object[]> result = tokenRepository.sumStatsByUserId(userId);
        return result.isEmpty() ? new Object[]{0L, 0L} : result.get(0);
    }

    public List<Token> listAll() {
        return tokenRepository.findAllOrderByUpdatedAtDesc();
    }

    public Token getById(Long id) {
        return tokenRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Token 不存在"));
    }

    public Token create(Long userId, TokenRequest request) {
        String tokenKey = "sk-" + UUID.randomUUID().toString().replace("-", "");

        Token token = Token.builder()
                .name(request.getName())
                .tokenKey(tokenKey)
                .userId(userId)
                .quota(request.getQuota() != null ? request.getQuota() : -1L)
                .usedQuota(0L)
                .credits(request.getCredits() != null ? request.getCredits() : BigDecimal.valueOf(-1))
                .expiredAt(request.getExpiredAt())
                .allowedModels(request.getAllowedModels())
                .rateLimit(request.getRateLimit() != null ? request.getRateLimit() : 0)
                .status(1)
                .build();

        Token saved = tokenRepository.save(token);
        cacheInvalidationService.publish(CacheInvalidationService.TOKEN_ID_PREFIX + saved.getId());
        return saved;
    }

    public Token update(Long id, TokenRequest request) {
        Token token = getById(id);
        if (request.getName() != null) token.setName(request.getName());
        if (request.getQuota() != null) token.setQuota(request.getQuota());
        if (request.getCredits() != null) token.setCredits(request.getCredits());
        if (request.getExpiredAt() != null) token.setExpiredAt(request.getExpiredAt());
        if (request.getAllowedModels() != null) token.setAllowedModels(request.getAllowedModels());
        if (request.getRateLimit() != null) token.setRateLimit(request.getRateLimit());
        Token saved = tokenRepository.save(token);
        evictTokenCache(saved.getId(), saved.getTokenKey());
        return saved;
    }

    public void delete(Long id) {
        Token token = tokenRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Token 不存在"));
        tokenRepository.deleteById(id);
        evictTokenCache(id, token.getTokenKey());
    }

    public void updateStatus(Long id, Integer status) {
        Token token = getById(id);
        token.setStatus(status);
        Token saved = tokenRepository.save(token);
        evictTokenCache(saved.getId(), saved.getTokenKey());
    }

    /**
     * 通过 token key 验证并获取 token 实体（带短时缓存）
     */
    public Token validateTokenKey(String tokenKey) {
        CachedToken cached = tokenCache.get(tokenKey);
        Token token;
        if (cached != null && !cached.isExpired()) {
            token = cached.token();
        } else {
            long generation = cacheInvalidationService.generation(CacheInvalidationService.TOKEN_ID_PREFIX);
            token = tokenRepository.findByTokenKey(tokenKey)
                    .orElseThrow(() -> new BusinessException(401, "无效的 Token"));
            if (cacheInvalidationService.isCurrentGeneration(CacheInvalidationService.TOKEN_ID_PREFIX, generation)) {
                CachedToken fresh = new CachedToken(token, System.currentTimeMillis());
                tokenCache.put(tokenKey, fresh);
                tokenKeyById.put(token.getId(), tokenKey);
                if (!cacheInvalidationService.isCurrentGeneration(CacheInvalidationService.TOKEN_ID_PREFIX, generation)) {
                    tokenCache.remove(tokenKey, fresh);
                    tokenKeyById.remove(token.getId(), tokenKey);
                }
            }
        }

        if (token.getStatus() != 1) {
            throw new BusinessException(403, "Token 已被禁用");
        }

        if (token.getExpiredAt() != null && token.getExpiredAt().isBefore(java.time.LocalDateTime.now())) {
            throw new BusinessException(403, "Token 已过期");
        }

        return token;
    }

    /**
     * 增加已用额度（原子操作，避免并发丢失计数）
     */
    public void addUsedQuota(Long tokenId, long quota) {
        if (quota > 0) {
            tokenRepository.addUsedQuota(tokenId, quota);
            cacheInvalidationService.publish(CacheInvalidationService.TOKEN_ID_PREFIX + tokenId);
        }
    }

    /**
     * 获取 Token 总数
     */
    public long count() {
        return tokenRepository.count();
    }

    /**
     * 清除指定 Token 的缓存
     */
    private void evictTokenCache(Long tokenId, String tokenKey) {
        if (tokenKey != null) {
            tokenCache.remove(tokenKey);
        }
        if (tokenId != null) {
            tokenKeyById.remove(tokenId, tokenKey);
            cacheInvalidationService.publish(CacheInvalidationService.TOKEN_ID_PREFIX + tokenId);
        }
    }

    @EventListener
    public void onCacheInvalidation(CacheInvalidationService.CacheInvalidationEvent event) {
        String route = event.route();
        if (route.startsWith(CacheInvalidationService.TOKEN_ID_PREFIX)) {
            try {
                long tokenId = Long.parseLong(route.substring(CacheInvalidationService.TOKEN_ID_PREFIX.length()));
                String tokenKey = tokenKeyById.remove(tokenId);
                if (tokenKey != null) {
                    tokenCache.remove(tokenKey);
                }
            } catch (NumberFormatException ignored) {
                // 非法/未知消息忽略
            }
        }
    }

}
