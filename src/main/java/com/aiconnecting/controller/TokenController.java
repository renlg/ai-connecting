package com.aiconnecting.controller;

import com.aiconnecting.common.ApiResponse;
import com.aiconnecting.dto.TokenRequest;
import com.aiconnecting.entity.Token;
import com.aiconnecting.entity.User;
import com.aiconnecting.service.TokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tokens")
@RequiredArgsConstructor
public class TokenController {

    private final TokenService tokenService;

    /** 普通用户查看自己的 token */
    @GetMapping
    public ApiResponse<List<Token>> list(@AuthenticationPrincipal User user) {
        if ("admin".equals(user.getRole())) {
            return ApiResponse.success(tokenService.listAll());
        }
        return ApiResponse.success(tokenService.listByUser(user.getId()));
    }

    @GetMapping("/{id}")
    public ApiResponse<Token> getById(@PathVariable Long id) {
        return ApiResponse.success(tokenService.getById(id));
    }

    @PostMapping
    public ApiResponse<Token> create(@AuthenticationPrincipal User user,
                                     @RequestBody TokenRequest request) {
        return ApiResponse.success(tokenService.create(user.getId(), request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Token> update(@PathVariable Long id, @RequestBody TokenRequest request) {
        return ApiResponse.success(tokenService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        tokenService.delete(id);
        return ApiResponse.success();
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        tokenService.updateStatus(id, body.get("status"));
        return ApiResponse.success();
    }
}
