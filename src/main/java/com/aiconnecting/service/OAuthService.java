package com.aiconnecting.service;

import com.aiconnecting.entity.OAuthClient;
import com.aiconnecting.entity.OAuthCode;
import com.aiconnecting.entity.User;
import com.aiconnecting.repository.OAuthClientRepository;
import com.aiconnecting.repository.OAuthCodeRepository;
import com.aiconnecting.repository.UserRepository;
import com.aiconnecting.security.JwtUtils;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OAuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final long ACCESS_TOKEN_EXPIRES_IN_SECONDS = 86_400L;

    private final OAuthClientRepository clientRepository;
    private final OAuthCodeRepository codeRepository;
    private final UserRepository userRepository;
    private final JwtUtils jwtUtils;

    public OAuthClient validateAuthorizationRequest(String clientId, String redirectUri, String responseType) {
        OAuthClient client = clientRepository.findByClientId(clientId)
                .orElseThrow(() -> badRequest("Invalid client_id"));
        if (!Boolean.TRUE.equals(client.getEnabled())) {
            throw badRequest("OAuth client is disabled");
        }
        if (!client.getRedirectUri().equals(redirectUri)) {
            throw badRequest("redirect_uri does not match the registered URI");
        }
        if (!"code".equals(responseType)) {
            throw badRequest("Unsupported response_type");
        }
        return client;
    }

    public String authenticatedUsername(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String username = activeUsername(header.substring(7));
            if (username != null) return username;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("aic_token".equals(cookie.getName())) {
                    String username = activeUsername(cookie.getValue());
                    if (username != null) return username;
                }
            }
        }
        return null;
    }

    public OAuthCode createAuthorizationCode(String username, OAuthClient client) {
        byte[] random = new byte[16];
        SECURE_RANDOM.nextBytes(random);
        OAuthCode code = OAuthCode.builder()
                .code(HexFormat.of().formatHex(random))
                .username(username)
                .clientId(client.getClientId())
                .redirectUri(client.getRedirectUri())
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .used(false)
                .build();
        return codeRepository.save(code);
    }

    @Transactional
    public Map<String, Object> exchangeCode(String clientId, String clientSecret, String code,
                                             String grantType) {
        if (!"authorization_code".equals(grantType)) {
            throw badRequest("Unsupported grant_type");
        }
        OAuthClient client = clientRepository.findByClientId(clientId)
                .orElseThrow(() -> unauthorized("Invalid client credentials"));
        if (!Boolean.TRUE.equals(client.getEnabled()) || !client.getClientSecret().equals(clientSecret)) {
            throw unauthorized("Invalid client credentials");
        }

        OAuthCode authorizationCode = codeRepository.findById(code)
                .orElseThrow(() -> badRequest("Invalid authorization code"));
        if (!clientId.equals(authorizationCode.getClientId())
                || !client.getRedirectUri().equals(authorizationCode.getRedirectUri())) {
            throw badRequest("Authorization code does not belong to this client");
        }
        if (Boolean.TRUE.equals(authorizationCode.getUsed())) {
            throw badRequest("Authorization code has already been used");
        }
        if (!authorizationCode.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw badRequest("Authorization code has expired");
        }
        if (codeRepository.markUsed(code) != 1) {
            throw badRequest("Authorization code has already been used");
        }

        User user = userRepository.findByUsername(authorizationCode.getUsername())
                .filter(candidate -> Integer.valueOf(1).equals(candidate.getStatus()))
                .orElseThrow(() -> unauthorized("User is not active"));
        String accessToken = jwtUtils.generateToken(user.getUsername(), user.getRole());
        return Map.of(
                "access_token", accessToken,
                "token_type", "bearer",
                "expires_in", ACCESS_TOKEN_EXPIRES_IN_SECONDS,
                "username", user.getUsername());
    }

    public Map<String, String> userInfo(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw unauthorized("Missing bearer token");
        }
        String username = activeUsername(authorizationHeader.substring(7));
        if (username == null) {
            throw unauthorized("Invalid access token");
        }
        return Map.of("username", username);
    }

    private String activeUsername(String token) {
        if (!jwtUtils.validateToken(token)) return null;
        String username = jwtUtils.getUsernameFromToken(token);
        return userRepository.findByUsername(username)
                .filter(user -> Integer.valueOf(1).equals(user.getStatus()))
                .map(User::getUsername)
                .orElse(null);
    }

    private OAuthException badRequest(String message) {
        return new OAuthException(HttpStatus.BAD_REQUEST, message);
    }

    private OAuthException unauthorized(String message) {
        return new OAuthException(HttpStatus.UNAUTHORIZED, message);
    }
}
