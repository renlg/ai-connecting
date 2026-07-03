package com.aiconnecting.controller;

import com.aiconnecting.common.ApiResponse;
import com.aiconnecting.dto.CouponGenerateRequest;
import com.aiconnecting.dto.CouponRedemptionDTO;
import com.aiconnecting.dto.DashboardStats;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.Coupon;
import com.aiconnecting.entity.User;
import com.aiconnecting.entity.UsageLog;
import com.aiconnecting.service.UserService;
import com.aiconnecting.service.UsageLogService;
import com.aiconnecting.service.CouponService;
import com.aiconnecting.service.TokenService;
import com.aiconnecting.service.ChannelService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    @Value("${app.admin.reset-password:88888888}")
    private String resetPassword;

    private final ChannelService channelService;
    private final UserService userService;
    private final UsageLogService usageLogService;
    private final TokenService tokenService;
    private final CouponService couponService;

    /**
     * 仪表盘统计 - admin 看全局，普通用户看自己的数据
     */
    @GetMapping("/dashboard")
    public ApiResponse<DashboardStats> dashboard(@AuthenticationPrincipal User currentUser) {
        boolean isAdmin = "admin".equalsIgnoreCase(currentUser.getRole());

        if (isAdmin) {
            List<Channel> channels = channelService.listAll();
            long activeChannels = channels.stream().filter(c -> c.getStatus() == 1).count();

            DashboardStats stats = DashboardStats.builder()
                    .totalChannels((long) channels.size())
                    .activeChannels(activeChannels)
                    .totalTokens(tokenService.count())
                    .totalUsers(userService.count())
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
            // 普通用户：只看自己的 Token 和使用记录（优化为 2 次聚合查询）
            var userTokens = tokenService.listByUser(currentUser.getId());
            var tokenIds = userTokens.stream().map(t -> t.getId()).toList();

            long totalTokensUsed = userTokens.stream().mapToLong(t -> t.getUsedQuota() != null ? t.getUsedQuota() : 0).sum();

            // 聚合查询：全部时间
            long totalRequests = 0, totalTokensUsedLog = 0, totalInputTokens = 0, totalOutputTokens = 0;
            double totalCreditsConsumed = 0;
            if (!tokenIds.isEmpty()) {
                Object[] allMetrics = usageLogService.sumAllMetricsByTokenIds(tokenIds);
                totalRequests = ((Number) allMetrics[0]).longValue();
                totalTokensUsedLog = ((Number) allMetrics[1]).longValue();
                totalInputTokens = ((Number) allMetrics[2]).longValue();
                totalOutputTokens = ((Number) allMetrics[3]).longValue();
                totalCreditsConsumed = ((Number) allMetrics[4]).doubleValue();
            }

            // 聚合查询：今日
            long requestsToday = 0, tokensUsedToday = 0, inputTokensToday = 0, outputTokensToday = 0;
            double creditsConsumedToday = 0;
            if (!tokenIds.isEmpty()) {
                Object[] todayMetrics = usageLogService.sumAllMetricsByTokenIdsSince(tokenIds, java.time.LocalDate.now().atStartOfDay());
                requestsToday = ((Number) todayMetrics[0]).longValue();
                tokensUsedToday = ((Number) todayMetrics[1]).longValue();
                inputTokensToday = ((Number) todayMetrics[2]).longValue();
                outputTokensToday = ((Number) todayMetrics[3]).longValue();
                creditsConsumedToday = ((Number) todayMetrics[4]).doubleValue();
            }

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
        List<User> users = userService.searchUsers(search);
        users.forEach(u -> u.setPassword(null));
        return ApiResponse.success(users);
    }

    /**
     * 用户管理 - 更新状态
     */
    @PutMapping("/users/{id}/status")
    public ApiResponse<Void> updateUserStatus(@PathVariable Long id, @RequestBody java.util.Map<String, Integer> body) {
        userService.updateUserStatus(id, body.get("status"));
        return ApiResponse.success();
    }

    /**
     * 用户管理 - 重置密码
     */
    @PutMapping("/users/{id}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable Long id) {
        userService.resetPassword(id, resetPassword);
        return ApiResponse.success();
    }

    /**
     * 用户管理 - 更新积分
     */
    @PutMapping("/users/{id}/credits")
    public ApiResponse<Void> updateCredits(@PathVariable Long id, @RequestBody java.util.Map<String, Double> body) {
        userService.updateCredits(id, body.get("credits"));
        return ApiResponse.success();
    }

    /**
     * 使用日志
     */
    @GetMapping("/logs")
    public ApiResponse<Page<UsageLog>> getLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(usageLogService.getLogs(page, size));
    }

    /**
     * 生成积分券
     */
    @PostMapping("/coupons")
    public ApiResponse<Coupon> generateCoupon(@AuthenticationPrincipal User currentUser,
                                              @Valid @RequestBody CouponGenerateRequest request) {
        Coupon coupon = couponService.generateCoupon(currentUser, request.getCredits(),
                request.getMaxUses(), request.getExpiryDate());
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
