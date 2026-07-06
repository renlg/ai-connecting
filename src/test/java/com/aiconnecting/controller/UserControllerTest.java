package com.aiconnecting.controller;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.entity.Coupon;
import com.aiconnecting.entity.User;
import com.aiconnecting.service.CouponService;
import com.aiconnecting.service.UserService;
import com.aiconnecting.security.JwtAuthenticationFilter;
import com.aiconnecting.security.JwtUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserService userService;

    @MockBean
    private CouponService couponService;

    @MockBean
    private JwtUtils jwtUtils;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    private User regularUser;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();

        regularUser = User.builder()
                .id(2L).username("user").password("encoded").nickname("TestUser")
                .email("test@example.com").role("user").credits(BigDecimal.valueOf(50)).status(1).build();
    }

    private void setAuthentication(User user) {
        var auth = new UsernamePasswordAuthenticationToken(
                user, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().toUpperCase())));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ==================== GetProfile ====================

    @Test
    void getProfile() throws Exception {
        setAuthentication(regularUser);
        User profile = User.builder()
                .id(2L).username("user").password("encoded").nickname("TestUser")
                .email("test@example.com").role("user").credits(BigDecimal.valueOf(50)).build();
        when(userService.getById(2L)).thenReturn(profile);

        mockMvc.perform(get("/api/user/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value("user"))
                .andExpect(jsonPath("$.data.nickname").value("TestUser"))
                .andExpect(jsonPath("$.data.email").value("test@example.com"))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    void getProfile_userNotFound() throws Exception {
        setAuthentication(regularUser);
        when(userService.getById(2L)).thenThrow(new BusinessException("用户不存在"));

        mockMvc.perform(get("/api/user/profile"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("用户不存在"));
    }

    // ==================== UpdateProfile ====================

    @Test
    void updateProfile() throws Exception {
        setAuthentication(regularUser);
        User updated = User.builder()
                .id(2L).username("user").password("encoded").nickname("NewNick")
                .email("new@example.com").role("user").credits(BigDecimal.valueOf(50)).build();
        when(userService.updateProfile(eq(2L), eq("NewNick"), eq("new@example.com")))
                .thenReturn(updated);

        mockMvc.perform(put("/api/user/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"NewNick\",\"email\":\"new@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("NewNick"))
                .andExpect(jsonPath("$.data.email").value("new@example.com"))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    void updateProfile_partialUpdate() throws Exception {
        setAuthentication(regularUser);
        User updated = User.builder()
                .id(2L).username("user").password("encoded").nickname("OnlyNick")
                .email("test@example.com").role("user").credits(BigDecimal.valueOf(50)).build();
        when(userService.updateProfile(eq(2L), eq("OnlyNick"), isNull()))
                .thenReturn(updated);

        mockMvc.perform(put("/api/user/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"OnlyNick\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("OnlyNick"));
    }

    // ==================== ChangePassword ====================

    @Test
    void changePassword() throws Exception {
        setAuthentication(regularUser);
        doNothing().when(userService).changePassword(2L, "oldPass", "newPass");

        mockMvc.perform(put("/api/user/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"oldPass\",\"newPassword\":\"newPass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void changePassword_wrongOldPassword() throws Exception {
        setAuthentication(regularUser);
        doThrow(new BusinessException("原密码错误"))
                .when(userService).changePassword(2L, "wrongOld", "newPass");

        mockMvc.perform(put("/api/user/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"wrongOld\",\"newPassword\":\"newPass\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("原密码错误"));
    }

    // ==================== RedeemCoupon ====================

    @Test
    void redeemCoupon_success() throws Exception {
        setAuthentication(regularUser);
        Coupon coupon = Coupon.builder().id(1L).code("ABC123").credits(BigDecimal.valueOf(50)).build();
        when(couponService.redeemCoupon(any(User.class), eq("ABC123"))).thenReturn(coupon);

        mockMvc.perform(post("/api/user/coupons/redeem")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ABC123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.credits").value(50.0))
                .andExpect(jsonPath("$.data.message").value("兑换成功，获得50积分"));
    }

    @Test
    void redeemCoupon_invalidCode() throws Exception {
        setAuthentication(regularUser);
        when(couponService.redeemCoupon(any(User.class), eq("INVALID")))
                .thenThrow(new BusinessException("兑换码不存在"));

        mockMvc.perform(post("/api/user/coupons/redeem")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"INVALID\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("兑换码不存在"));
    }

    @Test
    void redeemCoupon_expired() throws Exception {
        setAuthentication(regularUser);
        when(couponService.redeemCoupon(any(User.class), eq("EXPIRED")))
                .thenThrow(new BusinessException("该兑换码已过期"));

        mockMvc.perform(post("/api/user/coupons/redeem")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"EXPIRED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("该兑换码已过期"));
    }

    @Test
    void redeemCoupon_maxUsesReached() throws Exception {
        setAuthentication(regularUser);
        when(couponService.redeemCoupon(any(User.class), eq("MAXED")))
                .thenThrow(new BusinessException("该兑换码已达到使用次数上限"));

        mockMvc.perform(post("/api/user/coupons/redeem")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"MAXED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("该兑换码已达到使用次数上限"));
    }
}
