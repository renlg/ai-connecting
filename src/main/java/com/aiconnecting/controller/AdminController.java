package com.aiconnecting.controller;

import com.aiconnecting.common.ApiResponse;
import com.aiconnecting.common.BusinessException;
import com.aiconnecting.dto.CouponGenerateRequest;
import com.aiconnecting.dto.CouponRedemptionDTO;
import com.aiconnecting.dto.CreditsRequest;
import com.aiconnecting.dto.DashboardDailyStats;
import com.aiconnecting.dto.DashboardStats;
import com.aiconnecting.dto.LevelRequest;
import com.aiconnecting.dto.StatusRequest;
import com.aiconnecting.dto.AnnouncementRequest;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.Coupon;
import com.aiconnecting.entity.OperationLog;
import com.aiconnecting.entity.User;
import com.aiconnecting.entity.UsageLog;
import com.aiconnecting.entity.Announcement;
import com.aiconnecting.service.DashboardService;
import com.aiconnecting.service.UserService;
import com.aiconnecting.service.UsageLogService;
import com.aiconnecting.service.CouponService;
import com.aiconnecting.service.ChannelService;
import com.aiconnecting.service.ChannelHealthTracker;
import com.aiconnecting.service.OperationLogService;
import com.aiconnecting.service.StatsAggregationService;
import com.aiconnecting.repository.AnnouncementRepository;
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
    private final CouponService couponService;
    private final DashboardService dashboardService;
    private final ChannelHealthTracker channelHealthTracker;
    private final AnnouncementRepository announcementRepository;
    private final OperationLogService operationLogService;
    private final StatsAggregationService statsAggregationService;

    /**
     * 仪表盘统计 - admin 看全局，普通用户看自己的数据
     */
    @GetMapping("/dashboard")
    public ApiResponse<DashboardStats> dashboard(@AuthenticationPrincipal User currentUser) {
        return ApiResponse.success(dashboardService.buildDashboardStats(currentUser));
    }

    /**
     * 仪表盘每日统计（积分消耗 + 按模型 token 消耗）- admin 看全局，普通用户看自己的数据
     */
    @GetMapping("/dashboard/daily-stats")
    public ApiResponse<DashboardDailyStats> getDailyStats(
            @AuthenticationPrincipal User currentUser,
            @RequestParam(defaultValue = "7") int days) {
        return ApiResponse.success(usageLogService.getDailyStats(currentUser, Math.min(days, 90)));
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
    public ApiResponse<Void> updateUserStatus(@AuthenticationPrincipal User currentUser,
                                              @PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        userService.updateUserStatus(id, request.getStatus());
        operationLogService.record(currentUser.getId(), "UPDATE_USER_STATUS", "user:" + id,
                "status=" + request.getStatus());
        return ApiResponse.success();
    }

    /**
     * 用户管理 - 重置密码
     */
    @PutMapping("/users/{id}/reset-password")
    public ApiResponse<Void> resetPassword(@AuthenticationPrincipal User currentUser, @PathVariable Long id) {
        if (resetPassword == null || resetPassword.isBlank()) {
            throw new BusinessException("重置密码未配置，请设置环境变量 ADMIN_RESET_PASSWORD");
        }
        userService.resetPassword(id, resetPassword);
        operationLogService.record(currentUser.getId(), "RESET_PASSWORD", "user:" + id, null);
        return ApiResponse.success();
    }

    /**
     * 用户管理 - 更新积分
     */
    @PutMapping("/users/{id}/credits")
    public ApiResponse<Void> updateCredits(@AuthenticationPrincipal User currentUser,
                                           @PathVariable Long id, @Valid @RequestBody CreditsRequest request) {
        userService.updateCredits(id, request.getCredits());
        operationLogService.record(currentUser.getId(), "UPDATE_USER_CREDITS", "user:" + id,
                "credits=" + request.getCredits());
        return ApiResponse.success();
    }

    /**
     * 用户管理 - 更新等级
     */
    @PutMapping("/users/{id}/level")
    public ApiResponse<Void> updateUserLevel(@AuthenticationPrincipal User currentUser,
                                             @PathVariable Long id, @Valid @RequestBody LevelRequest request) {
        userService.updateLevel(id, request.getLevel());
        operationLogService.record(currentUser.getId(), "UPDATE_USER_LEVEL", "user:" + id,
                "level=" + request.getLevel());
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
     * 管理员操作审计日志
     */
    @GetMapping("/operation-logs")
    public ApiResponse<Page<OperationLog>> getOperationLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(operationLogService.getLogs(page, size));
    }

    /**
     * 生成积分券
     */
    @PostMapping("/coupons")
    public ApiResponse<Coupon> generateCoupon(@AuthenticationPrincipal User currentUser,
                                              @Valid @RequestBody CouponGenerateRequest request) {
        Coupon coupon = couponService.generateCoupon(currentUser, request.getCredits(),
                request.getMaxUses(), request.getExpiryDate());
        operationLogService.record(currentUser.getId(), "GENERATE_COUPON", "coupon:" + coupon.getId(),
                "credits=" + request.getCredits() + ", maxUses=" + request.getMaxUses());
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
    public ApiResponse<Coupon> updateCouponStatus(@AuthenticationPrincipal User currentUser,
                                                  @PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        Coupon coupon = couponService.toggleStatus(id, request.getStatus());
        operationLogService.record(currentUser.getId(), "UPDATE_COUPON_STATUS", "coupon:" + id,
                "status=" + request.getStatus());
        return ApiResponse.success(coupon);
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

    // ==================== 公告管理 ====================

    /**
     * 创建公告
     */
    @PostMapping("/announcements")
    public ApiResponse<Announcement> createAnnouncement(@AuthenticationPrincipal User currentUser,
                                                        @Valid @RequestBody AnnouncementRequest request) {
        Announcement announcement = Announcement.builder()
                .title(request.getTitle())
                .content(request.getContent())
                .status(request.getStatus() != null ? request.getStatus() : 1)
                .createdBy(currentUser.getId())
                .build();
        Announcement saved = announcementRepository.save(announcement);
        operationLogService.record(currentUser.getId(), "CREATE_ANNOUNCEMENT", "announcement:" + saved.getId(),
                "title=" + saved.getTitle());
        return ApiResponse.success(saved);
    }

    /**
     * 获取所有公告列表
     */
    @GetMapping("/announcements")
    public ApiResponse<List<Announcement>> listAnnouncements() {
        return ApiResponse.success(announcementRepository.findAllByOrderByCreatedAtDesc());
    }

    /**
     * 更新公告
     */
    @PutMapping("/announcements/{id}")
    public ApiResponse<Announcement> updateAnnouncement(@AuthenticationPrincipal User currentUser,
                                                        @PathVariable Long id,
                                                        @Valid @RequestBody AnnouncementRequest request) {
        Announcement announcement = announcementRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "公告不存在"));
        announcement.setTitle(request.getTitle());
        announcement.setContent(request.getContent());
        if (request.getStatus() != null) {
            announcement.setStatus(request.getStatus());
        }
        Announcement saved = announcementRepository.save(announcement);
        operationLogService.record(currentUser.getId(), "UPDATE_ANNOUNCEMENT", "announcement:" + id,
                "title=" + saved.getTitle());
        return ApiResponse.success(saved);
    }

    /**
     * 删除公告
     */
    @DeleteMapping("/announcements/{id}")
    public ApiResponse<Void> deleteAnnouncement(@AuthenticationPrincipal User currentUser, @PathVariable Long id) {
        announcementRepository.deleteById(id);
        operationLogService.record(currentUser.getId(), "DELETE_ANNOUNCEMENT", "announcement:" + id, null);
        return ApiResponse.success();
    }

    /**
     * 初始化用量汇总数据（从 usage_logs 历史数据构建 usage_stats 汇总表）
     */
    @PostMapping("/stats/init")
    public ApiResponse<String> initUsageStats() {
        statsAggregationService.initializeHistoricalData();
        return ApiResponse.success("用量汇总数据初始化完成");
    }
}
