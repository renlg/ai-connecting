package com.aiconnecting.controller;

import com.aiconnecting.common.ApiResponse;
import com.aiconnecting.dto.DashboardStats;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.User;
import com.aiconnecting.entity.UsageLog;
import com.aiconnecting.repository.ChannelRepository;
import com.aiconnecting.repository.UserRepository;
import com.aiconnecting.repository.TokenRepository;
import com.aiconnecting.service.UsageLogService;
import com.aiconnecting.repository.UsageLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ChannelRepository channelRepository;
    private final UserRepository userRepository;
    private final TokenRepository tokenRepository;
    private final UsageLogService usageLogService;
    private final UsageLogRepository usageLogRepository;

    /**
     * 仪表盘统计
     */
    @GetMapping("/dashboard")
    public ApiResponse<DashboardStats> dashboard() {
        List<Channel> channels = channelRepository.findAll();
        long activeChannels = channels.stream().filter(c -> c.getStatus() == 1).count();

        DashboardStats stats = DashboardStats.builder()
                .totalChannels((long) channels.size())
                .activeChannels(activeChannels)
                .totalTokens(tokenRepository.count())
                .totalUsers(userRepository.count())
                .totalRequests(usageLogService.getTotalRequests())
                .totalTokensUsed(channels.stream().mapToLong(Channel::getUsedQuota).sum())
                .requestsToday(usageLogService.getRequestsToday())
                .tokensUsedToday(usageLogService.getTokensUsedToday())
                .build();

        return ApiResponse.success(stats);
    }

    /**
     * 用户管理 - 列表
     */
    @GetMapping("/users")
    public ApiResponse<List<User>> listUsers() {
        List<User> users = userRepository.findAll();
        users.forEach(u -> u.setPassword(null));
        return ApiResponse.success(users);
    }

    /**
     * 用户管理 - 更新状态
     */
    @PutMapping("/users/{id}/status")
    public ApiResponse<Void> updateUserStatus(@PathVariable Long id, @RequestBody java.util.Map<String, Integer> body) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new com.aiconnecting.common.BusinessException("用户不存在"));
        user.setStatus(body.get("status"));
        userRepository.save(user);
        return ApiResponse.success();
    }

    /**
     * 使用日志
     */
    @GetMapping("/logs")
    public ApiResponse<Page<UsageLog>> getLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<UsageLog> logs = usageLogRepository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return ApiResponse.success(logs);
    }
}
