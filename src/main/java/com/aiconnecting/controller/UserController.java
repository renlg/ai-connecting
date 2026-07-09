package com.aiconnecting.controller;

import com.aiconnecting.common.ApiResponse;
import com.aiconnecting.dto.ChangePasswordRequest;
import com.aiconnecting.dto.ProfileRequest;
import com.aiconnecting.entity.Coupon;
import com.aiconnecting.entity.User;
import com.aiconnecting.service.UserService;
import com.aiconnecting.service.CouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final CouponService couponService;

    @GetMapping("/profile")
    public ApiResponse<User> getProfile(@AuthenticationPrincipal User user) {
        return ApiResponse.success(userService.getById(user.getId()));
    }

    @PutMapping("/profile")
    public ApiResponse<User> updateProfile(@AuthenticationPrincipal User user,
                                           @RequestBody ProfileRequest request) {
        User updated = userService.updateProfile(user.getId(), request.getNickname(), request.getEmail());
        return ApiResponse.success(updated);
    }

    @PutMapping("/password")
    public ApiResponse<Void> changePassword(@AuthenticationPrincipal User user,
                                            @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(user.getId(), request.getOldPassword(), request.getNewPassword());
        return ApiResponse.success();
    }

    @PostMapping("/coupons/redeem")
    public ApiResponse<Map<String, Object>> redeemCoupon(@AuthenticationPrincipal User user,
                                                         @RequestBody Map<String, String> body) {
        Coupon coupon = couponService.redeemCoupon(user, body.get("code"));
        Map<String, Object> result = new HashMap<>();
        result.put("credits", coupon.getCredits());
        result.put("message", "兑换成功，获得" + coupon.getCredits() + "积分");
        return ApiResponse.success(result);
    }

    @GetMapping("/invite-code")
    public ApiResponse<Map<String, String>> getInviteCode(@AuthenticationPrincipal User user) {
        String code = userService.getInviteCode(user.getId());
        Map<String, String> result = new HashMap<>();
        result.put("inviteCode", code);
        return ApiResponse.success(result);
    }
}
