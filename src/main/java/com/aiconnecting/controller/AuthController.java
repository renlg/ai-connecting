package com.aiconnecting.controller;

import com.aiconnecting.common.ApiResponse;
import com.aiconnecting.dto.LoginRequest;
import com.aiconnecting.dto.LoginResponse;
import com.aiconnecting.dto.RegisterRequest;
import com.aiconnecting.entity.User;
import com.aiconnecting.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    /** 只信任来自这些直连地址的 X-Forwarded-For 头（反向代理自身的地址），默认仅信任本机回环地址 */
    @Value("${app.security.trusted-proxies:127.0.0.1,0:0:0:0:0:0:0:1,::1}")
    private String trustedProxies;

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(userService.login(request, getClientIp(httpRequest)));
    }

    /**
     * 获取客户端真实 IP。X-Forwarded-For 可被客户端任意伪造，
     * 只有当直连地址（request.getRemoteAddr()）是受信任的反向代理时才采信该请求头，
     * 否则直接使用 TCP 连接的直连地址，避免攻击者伪造该头绕过登录失败锁定。
     */
    private String getClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (isTrustedProxy(remoteAddr)) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank() && !"unknown".equalsIgnoreCase(forwardedFor)) {
                String ip = forwardedFor.split(",")[0].trim();
                if (!ip.isEmpty()) {
                    return ip;
                }
            }
        }
        return remoteAddr;
    }

    private boolean isTrustedProxy(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank() || trustedProxies == null) {
            return false;
        }
        List<String> proxies = Arrays.asList(trustedProxies.split(","));
        return proxies.stream().map(String::trim).anyMatch(remoteAddr::equals);
    }

    @PostMapping("/register")
    public ApiResponse<User> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(userService.register(request));
    }
}
