package com.aiconnecting.controller;

import com.aiconnecting.common.ApiResponse;
import com.aiconnecting.dto.CouponRedemptionDTO;
import com.aiconnecting.dto.DashboardStats;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.Coupon;
import com.aiconnecting.entity.User;
import com.aiconnecting.entity.UsageLog;
import com.aiconnecting.repository.ChannelRepository;
import com.aiconnecting.repository.UserRepository;
import com.aiconnecting.repository.TokenRepository;
import com.aiconnecting.service.UserService;
import com.aiconnecting.service.UsageLogService;
import com.aiconnecting.service.CouponService;
import com.aiconnecting.repository.UsageLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ChannelRepository channelRepository;
    private final UserRepository userRepository;
    private final TokenRepository tokenRepository;
    private final UserService userService;
    private final UsageLogService usageLogService;
    private final UsageLogRepository usageLogRepository;
    private final PasswordEncoder passwordEncoder;
    private final CouponService couponService;

    /**
     * 仪表盘统计 - admin 看全局，普通用户看自己的数据
     */
    @GetMapping("/dashboard")
    public ApiResponse<DashboardStats> dashboard(@AuthenticationPrincipal User currentUser) {
        boolean isAdmin = "admin".equalsIgnoreCase(currentUser.getRole());

        if (isAdmin) {
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
                    .totalInputTokens(usageLogService.getTotalInputTokens())
                    .totalOutputTokens(usageLogService.getTotalOutputTokens())
                    .inputTokensToday(usageLogService.getInputTokensToday())
                    .outputTokensToday(usageLogService.getOutputTokensToday())
                    .totalCreditsConsumed(usageLogService.getTotalCreditsConsumed())
                    .creditsConsumedToday(usageLogService.getCreditsConsumedToday())
                    .build();
            return ApiResponse.success(stats);
        } else {
            // 普通用户：只看自己的 Token 和使用记录
            var userTokens = tokenRepository.findByUserId(currentUser.getId());
            var tokenIds = userTokens.stream().map(t -> t.getId()).toList();

            long totalTokensUsed = userTokens.stream().mapToLong(t -> t.getUsedQuota() != null ? t.getUsedQuota() : 0).sum();
            long totalRequests = tokenIds.isEmpty() ? 0 : usageLogRepository.countByTokenIdIn(tokenIds);
            long requestsToday = tokenIds.isEmpty() ? 0 : usageLogRepository.countByTokenIdInSince(tokenIds, java.time.LocalDate.now().atStartOfDay());
            long tokensUsedToday = tokenIds.isEmpty() ? 0 : usageLogRepository.sumTokensByTokenIdInSince(tokenIds, java.time.LocalDate.now().atStartOfDay());

            long totalInputTokens = tokenIds.isEmpty() ? 0 : usageLogRepository.sumPromptTokensByTokenIdIn(tokenIds);
            long totalOutputTokens = tokenIds.isEmpty() ? 0 : usageLogRepository.sumCompletionTokensByTokenIdIn(tokenIds);
            long inputTokensToday = tokenIds.isEmpty() ? 0 : usageLogRepository.sumPromptTokensByTokenIdInSince(tokenIds, java.time.LocalDate.now().atStartOfDay());
            long outputTokensToday = tokenIds.isEmpty() ? 0 : usageLogRepository.sumCompletionTokensByTokenIdInSince(tokenIds, java.time.LocalDate.now().atStartOfDay());
            double totalCreditsConsumed = tokenIds.isEmpty() ? 0 : usageLogRepository.sumCreditCostByTokenIdIn(tokenIds);
            double creditsConsumedToday = tokenIds.isEmpty() ? 0 : usageLogRepository.sumCreditCostByTokenIdInSince(tokenIds, java.time.LocalDate.now().atStartOfDay());

            DashboardStats stats = DashboardStats.builder()
                    .totalChannels(0L)
                    .activeChannels(0L)
                    .totalTokens((long) userTokens.size())
                    .totalUsers(1L)
                    .totalRequests(totalRequests)
                    .totalTokensUsed(totalTokensUsed)
                    .requestsToday(requestsToday)
                    .tokensUsedToday(tokensUsedToday)
                    .totalInputTokens(totalInputTokens)
                    .totalOutputTokens(totalOutputTokens)
                    .inputTokensToday(inputTokensToday)
                    .outputTokensToday(outputTokensToday)
                    .totalCreditsConsumed(totalCreditsConsumed)
                    .creditsConsumedToday(creditsConsumedToday)
                    .myCredits(currentUser.getCredits() != null ? currentUser.getCredits() : 0.0)
                    .build();
            return ApiResponse.success(stats);
        }
    }

    /**
     * 用户管理 - 列表 (支持搜索)
     */
    @GetMapping("/users")
    public ApiResponse<List<User>> listUsers(@RequestParam(required = false) String search) {
        List<User> users;
        if (search != null && !search.trim().isEmpty()) {
            users = userRepository.searchByKeyword(search.trim());
        } else {
            users = userRepository.findAll();
        }
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
     * 用户管理 - 重置密码为 88888888
     */
    @PutMapping("/users/{id}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new com.aiconnecting.common.BusinessException("用户不存在"));
        user.setPassword(passwordEncoder.encode("88888888"));
        userRepository.save(user);
        return ApiResponse.success();
    }

    /**
     * 用户管理 - 更新积分
     */
    @PutMapping("/users/{id}/credits")
    public ApiResponse<Void> updateCredits(@PathVariable Long id, @RequestBody java.util.Map<String, Double> body) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new com.aiconnecting.common.BusinessException("用户不存在"));
        user.setCredits(body.get("credits"));
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

    /**
     * 生成积分券
     */
    @PostMapping("/coupons")
    public ApiResponse<Coupon> generateCoupon(@AuthenticationPrincipal User currentUser,
                                              @RequestBody java.util.Map<String, Object> body) {
        Double credits = Double.valueOf(body.get("credits").toString());
        Integer maxUses = body.get("maxUses") != null ? Integer.valueOf(body.get("maxUses").toString()) : 1;
        LocalDateTime expiryDate = body.get("expiryDate") != null
                ? LocalDateTime.parse(body.get("expiryDate").toString()) : null;
        Coupon coupon = couponService.generateCoupon(currentUser, credits, maxUses, expiryDate);
        return ApiResponse.success(coupon);
    }

    /**
     * 查看所有积分券
     */
    @GetMapping("/coupons")
    public ApiResponse<List<Coupon>> listCoupons() {
        return ApiResponse.success(couponService.listCoupons());
    }

    /**
     * 查看积分券使用记录
     */
    @GetMapping("/coupons/{id}/redemptions")
    public ApiResponse<List<CouponRedemptionDTO>> getCouponRedemptions(@PathVariable Long id) {
        return ApiResponse.success(couponService.getRedemptionsByCouponId(id));
    }

    /**
     * 禁用/启用积分券
     */
    @PutMapping("/coupons/{id}/status")
    public ApiResponse<Coupon> updateCouponStatus(@PathVariable Long id, @RequestBody java.util.Map<String, Integer> body) {
        Integer status = body.get("status");
        return ApiResponse.success(couponService.toggleStatus(id, status));
    }
}
