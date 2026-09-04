package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.common.DuplicateSubmitGuard;
import com.aiconnecting.dto.TokenRequest;
import com.aiconnecting.entity.Token;
import com.aiconnecting.repository.TokenRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    private final TokenRepository tokenRepository;
    private final CacheInvalidationService cacheInvalidationService;
    private final DuplicateSubmitGuard duplicateSubmitGuard;

    /** Token 验证缓存（按明文 key 索引，仅存在于内存），减少数据库查询，缓存 30 秒（缩短以减少禁用/过期Token延迟） */
    private final ConcurrentHashMap<String, CachedToken> tokenCache = new ConcurrentHashMap<>();
    /** token ID -> 明文 token key 的本地反向索引，使 tokenId 广播可 O(1) 精确驱逐。 */
    private final ConcurrentHashMap<Long, String> tokenKeyById = new ConcurrentHashMap<>();
    private static final long TOKEN_CACHE_TTL_MS = 30 * 1000L;
    private static final int TOKEN_KEY_GENERATION_ATTEMPTS = 10;

    private record CachedToken(Token token, long cachedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > TOKEN_CACHE_TTL_MS;
        }
    }

    /** 明文 tokenKey 的 sha256 hex 哈希；库中只保存该值，明文不再落库 */
    public static String hashTokenKey(String plainKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(plainKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 生成可安全展示的 Token Key 掩码：保留 sk- 前缀、Key 内容首 4 位及末 4 位。 */
    static String maskTokenKey(String plainKey) {
        if (plainKey == null || !plainKey.startsWith("sk-") || plainKey.length() <= 11) {
            throw new IllegalArgumentException("Token Key 长度不足，无法生成掩码");
        }
        return plainKey.substring(0, 7) + "****" + plainKey.substring(plainKey.length() - 4);
    }

    /**
     * 启动时把存量明文 tokenKey（sk- 前缀）改写为哈希，消除历史明文落库；
     * 迁移失败不影响启动，未迁移行仍可经 validateTokenKey 的明文回退路径验证
     */
    @PostConstruct
    public void migratePlaintextTokenKeys() {
        try {
            List<Token> legacy = tokenRepository.findByTokenKeyStartingWith("sk-");
            for (Token token : legacy) {
                token.setTokenKey(hashTokenKey(token.getTokenKey()));
                tokenRepository.save(token);
            }
            if (!legacy.isEmpty()) {
                log.info("已将 {} 个存量明文 tokenKey 迁移为哈希存储", legacy.size());
            }
        } catch (Exception e) {
            log.warn("存量 tokenKey 哈希迁移失败（明文回退路径仍可用）: {}", e.getMessage());
        }
    }

    public List<Token> listByUser(Long userId) {
        return tokenRepository.findByUserIdOrderByCreatedAtDesc(userId);
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
        return tokenRepository.findAllOrderByCreatedAtDesc();
    }

    public Token getById(Long id) {
        return tokenRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Token 不存在", "Token not found"));
    }

    public Token create(Long userId, TokenRequest request) {
        DataIntegrityViolationException lastCollision = null;
        for (int attempt = 0; attempt < TOKEN_KEY_GENERATION_ATTEMPTS; attempt++) {
            String tokenKey = "sk-" + UUID.randomUUID().toString().replace("-", "");
            if (!duplicateSubmitGuard.tryAcquire("token", tokenKey)) {
                continue;
            }

            Token token = Token.builder()
                    .name(request.getName())
                    .tokenKey(hashTokenKey(tokenKey))
                    .keyMask(maskTokenKey(tokenKey))
                    .userId(userId)
                    .quota(request.getQuota() != null ? request.getQuota() : -1L)
                    .usedQuota(0L)
                    .credits(request.getCredits() != null ? request.getCredits() : BigDecimal.valueOf(-1))
                    .expiredAt(request.getExpiredAt())
                    .allowedModels(request.getAllowedModels())
                    .rateLimit(request.getRateLimit() != null ? request.getRateLimit() : 0)
                    .status(1)
                    .build();

            try {
                Token saved = tokenRepository.save(token);
                cacheInvalidationService.publish(CacheInvalidationService.TOKEN_ID_PREFIX + saved.getId());
                // 明文仅在创建响应中一次性回显，此后任何接口不再下发
                saved.setPlainTokenKey(tokenKey);
                return saved;
            } catch (DataIntegrityViolationException e) {
                lastCollision = e;
            }
        }
        throw new BusinessException(400, "Token 标识已存在，请重试",
                "Token identifier already exists; retry", lastCollision);
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
        evictTokenCache(saved.getId());
        return saved;
    }

    public void delete(Long id) {
        Token token = tokenRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Token 不存在", "Token not found"));
        tokenRepository.deleteById(id);
        evictTokenCache(id);
    }

    public void updateStatus(Long id, Integer status) {
        Token token = getById(id);
        token.setStatus(status);
        Token saved = tokenRepository.save(token);
        evictTokenCache(saved.getId());
    }

    /**
     * 通过明文 token key 验证并获取 token 实体（带短时缓存）。
     * 库中存储的是 sha256 哈希，先按哈希查找；未命中再按原值查找以兼容迁移失败的存量明文行。
     * 只接受 sk- 前缀的明文形态，库内哈希不能被直接当作凭据使用
     */
    public Token validateTokenKey(String tokenKey) {
        if (tokenKey == null || !tokenKey.startsWith("sk-")) {
            throw new BusinessException(401, "无效的 Token", "Invalid token");
        }
        CachedToken cached = tokenCache.get(tokenKey);
        Token token;
        if (cached != null && !cached.isExpired()) {
            token = cached.token();
        } else {
            long generation = cacheInvalidationService.generation(CacheInvalidationService.TOKEN_ID_PREFIX);
            token = tokenRepository.findByTokenKey(hashTokenKey(tokenKey))
                    .or(() -> tokenRepository.findByTokenKey(tokenKey))
                    .orElseThrow(() -> new BusinessException(401, "无效的 Token", "Invalid token"));
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
            throw new BusinessException(403, "Token 已被禁用", "Token disabled");
        }

        if (token.getExpiredAt() != null && token.getExpiredAt().isBefore(java.time.LocalDateTime.now())) {
            throw new BusinessException(403, "Token 已过期", "Token expired");
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
     * 清除指定 Token 的缓存：缓存以明文 key 为键而库中只存哈希，
     * 因此按 token ID 经反向索引精确驱逐，并以按 ID 扫描兜底（覆盖本实例未见过明文的条目）
     */
    private void evictTokenCache(Long tokenId) {
        if (tokenId == null) {
            return;
        }
        String plainKey = tokenKeyById.remove(tokenId);
        if (plainKey != null) {
            tokenCache.remove(plainKey);
        }
        tokenCache.values().removeIf(cached -> tokenId.equals(cached.token().getId()));
        cacheInvalidationService.publish(CacheInvalidationService.TOKEN_ID_PREFIX + tokenId);
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
