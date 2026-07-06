package com.aiconnecting.controller;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.dto.CouponRedemptionDTO;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.Coupon;
import com.aiconnecting.entity.Token;
import com.aiconnecting.entity.UsageLog;
import com.aiconnecting.entity.User;
import com.aiconnecting.service.CouponService;
import com.aiconnecting.service.DashboardService;
import com.aiconnecting.service.UsageLogService;
import com.aiconnecting.service.UserService;
import com.aiconnecting.service.TokenService;
import com.aiconnecting.service.ChannelService;
import com.aiconnecting.security.JwtAuthenticationFilter;
import com.aiconnecting.security.JwtUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.aiconnecting.dto.DashboardStats;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "app.admin.reset-password=Test1234!")
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean private ChannelService channelService;
    @MockBean private UserService userService;
    @MockBean private UsageLogService usageLogService;
    @MockBean private TokenService tokenService;
    @MockBean private CouponService couponService;
    @MockBean private DashboardService dashboardService;
    @MockBean private JwtUtils jwtUtils;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;

    private User adminUser;
    private User regularUser;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();

        adminUser = User.builder()
                .id(1L).username("admin").password("encoded").role("admin")
                .credits(BigDecimal.valueOf(100)).status(1).build();
        regularUser = User.builder()
                .id(2L).username("user").password("encoded").role("user")
                .credits(BigDecimal.valueOf(50)).status(1).build();
    }

    private void setAuthentication(User user) {
        var auth = new UsernamePasswordAuthenticationToken(
                user, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().toUpperCase())));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ==================== Dashboard ====================

    @Test
    void dashboard_asAdmin() throws Exception {
        setAuthentication(adminUser);

        DashboardStats stats = DashboardStats.builder()
                .totalChannels(2L).activeChannels(1L).totalTokens(10L)
                .totalUsers(5L).totalRequests(1000L).build();
        when(dashboardService.buildDashboardStats(adminUser)).thenReturn(stats);

        mockMvc.perform(get("/api/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalChannels").value(2))
                .andExpect(jsonPath("$.data.activeChannels").value(1))
                .andExpect(jsonPath("$.data.totalTokens").value(10))
                .andExpect(jsonPath("$.data.totalUsers").value(5))
                .andExpect(jsonPath("$.data.totalRequests").value(1000));
    }

    @Test
    void dashboard_asRegularUser() throws Exception {
        setAuthentication(regularUser);

        DashboardStats stats = DashboardStats.builder()
                .totalChannels(0L).activeChannels(0L).totalTokens(1L)
                .totalUsers(1L).totalRequests(50L).requestsToday(10L)
                .myCredits(BigDecimal.valueOf(50)).build();
        when(dashboardService.buildDashboardStats(regularUser)).thenReturn(stats);

        mockMvc.perform(get("/api/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalTokens").value(1))
                .andExpect(jsonPath("$.data.totalRequests").value(50))
                .andExpect(jsonPath("$.data.requestsToday").value(10))
                .andExpect(jsonPath("$.data.myCredits").value(50.0));
    }

    // ==================== Users ====================

    @Test
    void listUsers() throws Exception {
        setAuthentication(adminUser);
        when(userService.searchUsers(anyString())).thenReturn(List.of(regularUser));

        mockMvc.perform(get("/api/admin/users").param("search", "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void listUsers_noSearch() throws Exception {
        setAuthentication(adminUser);
        when(userService.searchUsers(null)).thenReturn(List.of(adminUser, regularUser));

        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void updateUserStatus() throws Exception {
        setAuthentication(adminUser);
        doNothing().when(userService).updateUserStatus(2L, 0);

        mockMvc.perform(put("/api/admin/users/2/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void updateUserStatus_notFound() throws Exception {
        setAuthentication(adminUser);
        doThrow(new BusinessException("用户不存在")).when(userService).updateUserStatus(99L, 0);

        mockMvc.perform(put("/api/admin/users/99/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("用户不存在"));
    }

    @Test
    void resetUserPassword() throws Exception {
        setAuthentication(adminUser);
        doNothing().when(userService).resetPassword(eq(2L), anyString());

        mockMvc.perform(put("/api/admin/users/2/reset-password"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void updateUserCredits() throws Exception {
        setAuthentication(adminUser);
        doNothing().when(userService).updateCredits(2L, BigDecimal.valueOf(200));

        mockMvc.perform(put("/api/admin/users/2/credits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credits\":200.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== Logs ====================

    @Test
    void getLogs() throws Exception {
        setAuthentication(adminUser);
        UsageLog log = UsageLog.builder().id(1L).model("gpt-4").totalTokens(100).build();
        Page<UsageLog> page = new PageImpl<>(List.of(log));
        when(usageLogService.getLogs(0, 20)).thenReturn(page);

        mockMvc.perform(get("/api/admin/logs").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.content").isArray());
    }

    // ==================== Coupons ====================

    @Test
    void generateCoupon() throws Exception {
        setAuthentication(adminUser);
        Coupon coupon = Coupon.builder().id(1L).code("ABC123").credits(BigDecimal.valueOf(50)).maxUses(10).build();
        when(couponService.generateCoupon(any(User.class), any(BigDecimal.class), anyInt(), any()))
                .thenReturn(coupon);

        mockMvc.perform(post("/api/admin/coupons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credits\":50.0,\"maxUses\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("ABC123"));
    }

    @Test
    void listCoupons() throws Exception {
        setAuthentication(adminUser);
        Coupon coupon = Coupon.builder().id(1L).code("ABC123").credits(BigDecimal.valueOf(50)).build();
        when(couponService.listCoupons()).thenReturn(List.of(coupon));

        mockMvc.perform(get("/api/admin/coupons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].code").value("ABC123"));
    }

    @Test
    void getCouponRedemptions() throws Exception {
        setAuthentication(adminUser);
        CouponRedemptionDTO dto = CouponRedemptionDTO.builder()
                .userId(2L).username("user").credits(BigDecimal.valueOf(50)).build();
        when(couponService.getRedemptionsByCouponId(1L)).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/admin/coupons/1/redemptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].username").value("user"));
    }

    @Test
    void toggleCouponStatus() throws Exception {
        setAuthentication(adminUser);
        Coupon coupon = Coupon.builder().id(1L).status(0).build();
        when(couponService.toggleStatus(1L, 0)).thenReturn(coupon);

        mockMvc.perform(put("/api/admin/coupons/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
