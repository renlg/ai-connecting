package com.aiconnecting.controller;

import com.aiconnecting.common.ApiResponse;
import com.aiconnecting.common.BusinessException;
import com.aiconnecting.dto.TokenRequest;
import com.aiconnecting.entity.Token;
import com.aiconnecting.entity.User;
import com.aiconnecting.repository.UsageLogRepository;
import com.aiconnecting.repository.UserRepository;
import com.aiconnecting.service.TokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/tokens")
@RequiredArgsConstructor
public class TokenController {

    private final TokenService tokenService;
    private final UsageLogRepository usageLogRepository;
    private final UserRepository userRepository;

    /** 普通用户查看自己的 token */
    @GetMapping
    public ApiResponse<List<Token>> list(@AuthenticationPrincipal User user,
                                         @RequestParam(required = false) String search) {
        List<Token> tokens;
        if ("admin".equals(user.getRole())) {
            tokens = tokenService.listAll();
        } else {
            tokens = tokenService.listByUser(user.getId());
        }
        // 填充 ownerName
        fillOwnerName(tokens);
        // 按账号搜索
        if (search != null && !search.trim().isEmpty()) {
            String keyword = search.trim().toLowerCase();
            tokens = tokens.stream()
                    .filter(t -> t.getOwnerName() != null && t.getOwnerName().toLowerCase().contains(keyword))
                    .collect(Collectors.toList());
        }
        return ApiResponse.success(tokens);
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

    @GetMapping("/{id}/credit-history")
    public ApiResponse<List<Map<String, Object>>> creditHistory(@AuthenticationPrincipal User user,
                                                                 @PathVariable Long id) {
        Token token = tokenService.getById(id);
        if (!"admin".equals(user.getRole()) && !token.getUserId().equals(user.getId())) {
            throw new BusinessException(403, "无权查看该 Token 的消耗记录");
        }
        LocalDateTime since = LocalDateTime.now().minusMonths(3);
        List<Object[]> rows = usageLogRepository.findDailyCreditCostByTokenIdSince(id, since);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new HashMap<>();
            item.put("date", row[0]);
            item.put("credits", row[1]);
            result.add(item);
        }
        return ApiResponse.success(result);
    }

    /**
     * 填充 Token 的 ownerName
     */
    private void fillOwnerName(List<Token> tokens) {
        if (tokens == null || tokens.isEmpty()) return;
        // 批量查询用户
        var userIds = tokens.stream().map(Token::getUserId).distinct().collect(Collectors.toList());
        var userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername));
        tokens.forEach(t -> t.setOwnerName(userMap.getOrDefault(t.getUserId(), "unknown")));
    }
}
