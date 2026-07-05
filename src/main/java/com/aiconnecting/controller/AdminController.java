package com.aiconnecting.controller;

import com.aiconnecting.common.ApiResponse;
import com.aiconnecting.common.BusinessException;
import com.aiconnecting.dto.CouponGenerateRequest;
import com.aiconnecting.dto.CouponRedemptionDTO;
import com.aiconnecting.dto.CreditsRequest;
import com.aiconnecting.dto.DashboardStats;
import com.aiconnecting.dto.StatusRequest;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.Coupon;
import com.aiconnecting.entity.User;
import com.aiconnecting.entity.UsageLog;
import com.aiconnecting.service.DashboardService;
import com.aiconnecting.service.UserService;
import com.aiconnecting.service.UsageLogService;
import com.aiconnecting.service.CouponService;
import com.aiconnecting.service.TokenService;
import com.aiconnecting.service.ChannelService;
import com.aiconnecting.service.ChannelHealthTracker;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    @Value("${app.admin.reset-password:}")
    private String resetPassword;

    private final ChannelService channelService;
    private final UserService userService;
    private final UsageLogService usageLogService;
    private final TokenService tokenService;
    private final CouponService couponService;
    private final DashboardService dashboardService;
    private final ChannelHealthTracker channelHealthTracker;

    /**
     * 仪表盘统计 - admin 看全局，普通用户看自己的数据
     */
    @GetMapping("/dashboard")
    public ApiResponse<DashboardStats> dashboard(@AuthenticationPrincipal User currentUser) {
        return ApiResponse.success(dashboardService.buildDashboardStats(currentUser));
    }

    /**
     * 用户管理 - 列表 (支持搜索)
     */
    @GetMapping("/users")
    public ApiResponse<List<User>> listUsers(@RequestParam(required = false) String search) {
        return ApiResponse.success(userService.searchUsers(search));
    }

    /**
     * 用户管理 - 更新状态
     */
    @PutMapping("/users/{id}/status")
    public ApiResponse<Void> updateUserStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        userService.updateUserStatus(id, request.getStatus());
        return ApiResponse.success();
    }

    /**
     * 用户管理 - 重置密码
     */
    @PutMapping("/users/{id}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable Long id) {
        if (resetPassword == null || resetPassword.isBlank()) {
            throw new BusinessException("重置密码未配置，请设置环境变量 ADMIN_RESET_PASSWORD");
        }
        userService.resetPassword(id, resetPassword);
        return ApiResponse.success();
    }

    /**
     * 用户管理 - 更新积分
     */
    @PutMapping("/users/{id}/credits")
    public ApiResponse<Void> updateCredits(@PathVariable Long id, @Valid @RequestBody CreditsRequest request) {
        userService.updateCredits(id, request.getCredits());
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
    public ApiResponse<Coupon> updateCouponStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        return ApiResponse.success(couponService.toggleStatus(id, request.getStatus()));
    }

    /**
     * 获取封禁渠道列表（含渠道名称和封禁截止时间）
     */
    @GetMapping("/channels/blocked")
    public ApiResponse<List<Map<String, Object>>> getBlockedChannels() {
        Map<Long, Long> blockedDetails = channelHealthTracker.getBlockedChannelDetails();
        if (blockedDetails.isEmpty()) {
            return ApiResponse.success(List.of());
        }
        List<Channel> allChannels = channelService.listAll();
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Channel channel : allChannels) {
            Long blockUntil = blockedDetails.get(channel.getId());
            if (blockUntil != null) {
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("id", channel.getId());
                item.put("name", channel.getName());
                item.put("type", channel.getType());
                item.put("blockedUntil", blockUntil);
                result.add(item);
            }
        }
        return ApiResponse.success(result);
    }
}
