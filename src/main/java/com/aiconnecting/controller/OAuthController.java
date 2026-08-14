package com.aiconnecting.controller;

import com.aiconnecting.entity.OAuthClient;
import com.aiconnecting.entity.OAuthCode;
import com.aiconnecting.service.OAuthException;
import com.aiconnecting.service.OAuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/oauth")
@RequiredArgsConstructor
public class OAuthController {

    private final OAuthService oauthService;

    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize(@RequestParam String client_id,
                                          @RequestParam String redirect_uri,
                                          @RequestParam String response_type,
                                          @RequestParam(required = false) String state,
                                          HttpServletRequest request) {
        OAuthClient client = oauthService.validateAuthorizationRequest(client_id, redirect_uri, response_type);
        String username = oauthService.authenticatedUsername(request);
        if (username == null) {
            String originalUrl = request.getRequestURI()
                    + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
            String loginUrl = siteOrigin(request) + "/login?next="
                    + URLEncoder.encode(originalUrl, StandardCharsets.UTF_8);
            ResponseCookie pendingCookie = ResponseCookie.from("oauth_pending",
                            URLEncoder.encode(originalUrl, StandardCharsets.UTF_8))
                    .httpOnly(true)
                    .sameSite("Lax")
                    .path("/")
                    .maxAge(Duration.ofMinutes(10))
                    .build();
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(loginUrl))
                    .header(HttpHeaders.SET_COOKIE, pendingCookie.toString())
                    .build();
        }

        OAuthCode code = oauthService.createAuthorizationCode(username, client);
        UriComponentsBuilder callback = UriComponentsBuilder.fromUriString(redirect_uri)
                .queryParam("code", code.getCode());
        if (state != null && !state.isEmpty()) callback.queryParam("state", state);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(callback.build().encode().toUri())
                .build();
    }

    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Map<String, Object> tokenForm(@RequestParam MultiValueMap<String, String> form) {
        return exchange(form.toSingleValueMap());
    }

    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> tokenJson(@RequestBody Map<String, String> body) {
        return exchange(body);
    }

    @GetMapping("/userinfo")
    public Map<String, String> userInfo(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                                        String authorization) {
        return oauthService.userInfo(authorization);
    }

    @ExceptionHandler(OAuthException.class)
    public ResponseEntity<Map<String, String>> oauthError(OAuthException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(Map.of("error", exception.getMessage()));
    }

    private Map<String, Object> exchange(Map<String, String> values) {
        return oauthService.exchangeCode(
                required(values, "client_id"),
                required(values, "client_secret"),
                required(values, "code"),
                required(values, "grant_type"));
    }

    private String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw new OAuthException(HttpStatus.BAD_REQUEST, "Missing " + name);
        }
        return value;
    }

    private String siteOrigin(HttpServletRequest request) {
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
        return scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
    }
}
