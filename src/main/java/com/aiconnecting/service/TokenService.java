package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.dto.TokenRequest;
import com.aiconnecting.entity.Token;
import com.aiconnecting.repository.TokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TokenService {

    private final TokenRepository tokenRepository;

    public List<Token> listByUser(Long userId) {
        return tokenRepository.findByUserId(userId);
    }

    public List<Token> listAll() {
        return tokenRepository.findAll();
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
                .credits(request.getCredits() != null ? request.getCredits() : -1.0)
                .expiredAt(request.getExpiredAt())
                .allowedModels(request.getAllowedModels())
                .rateLimit(request.getRateLimit() != null ? request.getRateLimit() : 0)
                .status(1)
                .build();

        return tokenRepository.save(token);
    }

    public Token update(Long id, TokenRequest request) {
        Token token = getById(id);
        if (request.getName() != null) token.setName(request.getName());
        if (request.getQuota() != null) token.setQuota(request.getQuota());
        if (request.getCredits() != null) token.setCredits(request.getCredits());
        if (request.getExpiredAt() != null) token.setExpiredAt(request.getExpiredAt());
        if (request.getAllowedModels() != null) token.setAllowedModels(request.getAllowedModels());
        if (request.getRateLimit() != null) token.setRateLimit(request.getRateLimit());
        return tokenRepository.save(token);
    }

    public void delete(Long id) {
        if (!tokenRepository.existsById(id)) {
            throw new BusinessException("Token 不存在");
        }
        tokenRepository.deleteById(id);
    }

    public void updateStatus(Long id, Integer status) {
        Token token = getById(id);
        token.setStatus(status);
        tokenRepository.save(token);
    }

    /**
     * 通过 token key 验证并获取 token 实体
     */
    public Token validateTokenKey(String tokenKey) {
        Token token = tokenRepository.findByTokenKey(tokenKey)
                .orElseThrow(() -> new BusinessException(401, "无效的 Token"));

        if (token.getStatus() != 1) {
            throw new BusinessException(403, "Token 已被禁用");
        }

        if (token.getExpiredAt() != null && token.getExpiredAt().isBefore(java.time.LocalDateTime.now())) {
            throw new BusinessException(403, "Token 已过期");
        }

        return token;
    }

    /**
     * 增加已用额度
     */
    public void addUsedQuota(Long tokenId, long quota) {
        Token token = tokenRepository.findById(tokenId).orElse(null);
        if (token != null) {
            token.setUsedQuota(token.getUsedQuota() + quota);
            tokenRepository.save(token);
        }
    }

}
