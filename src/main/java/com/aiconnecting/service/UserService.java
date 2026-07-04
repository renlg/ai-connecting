package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.dto.LoginRequest;
import com.aiconnecting.dto.LoginResponse;
import com.aiconnecting.dto.RegisterRequest;
import com.aiconnecting.entity.User;
import com.aiconnecting.repository.UserRepository;
import com.aiconnecting.security.JwtUtils;
import com.aiconnecting.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Value("${app.admin.default-password}")
    private String adminDefaultPassword;

    @PostConstruct
    public void initAdmin() {
        if (userRepository.existsByUsername("admin")) {
            log.info("admin 用户已存在，跳过初始化");
        } else {
            User admin = User.builder()
                    .username("admin")
                    .password(passwordEncoder.encode(adminDefaultPassword))
                    .nickname("Administrator")
                    .role("admin")
                    .quota(-1L)
                    .usedQuota(0L)
                    .status(1)
                    .build();
            userRepository.save(admin);
            log.warn("数据库中无 admin 用户，已使用默认密码创建默认管理员，请尽快修改密码");
        }
    }

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BusinessException("用户名或密码错误"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        if (user.getStatus() != 1) {
            throw new BusinessException("账号已被禁用");
        }

        String token = jwtUtils.generateToken(user.getUsername(), user.getRole());
        return LoginResponse.builder()
                .token(token)
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .role(user.getRole())
                .build();
    }

    public User register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new BusinessException("用户名已存在");
        }

        User user = User.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .nickname(request.getNickname() != null ? request.getNickname() : request.getUsername())
                .email(request.getEmail())
                .role("user")
                .quota(-1L)
                .usedQuota(0L)
                .status(1)
                .build();

        return userRepository.save(user);
    }

    public User getById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new BusinessException("用户不存在"));
    }

    /**
     * 获取用户总数
     */
    public long count() {
        return userRepository.count();
    }

    @Transactional
    public User updateProfile(Long userId, String nickname, String email) {
        User user = getById(userId);
        if (nickname != null) user.setNickname(nickname);
        if (email != null) user.setEmail(email);
        return userRepository.save(user);
    }

    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = getById(userId);
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException("原密码错误");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    /**
     * 搜索用户（支持关键字）
     */
    public List<User> searchUsers(String keyword) {
        if (keyword != null && !keyword.trim().isEmpty()) {
            return userRepository.searchByKeyword(keyword.trim());
        }
        return userRepository.findAll();
    }

    /**
     * 更新用户状态
     */
    @Transactional
    public void updateUserStatus(Long userId, Integer status) {
        User user = getById(userId);
        user.setStatus(status);
        userRepository.save(user);
        // 清除用户缓存，使角色/状态变更立即生效
        jwtAuthenticationFilter.evictUserCache(user.getUsername());
    }

    /**
     * 管理员重置用户密码
     */
    @Transactional
    public void resetPassword(Long userId, String newPassword) {
        User user = getById(userId);
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        // 清除用户缓存，使密码变更立即生效
        jwtAuthenticationFilter.evictUserCache(user.getUsername());
    }

    /**
     * 更新用户积分
     */
    @Transactional
    public void updateCredits(Long userId, Double credits) {
        User user = getById(userId);
        user.setCredits(credits);
        userRepository.save(user);
        // 清除用户缓存，使积分变更尽快生效
        jwtAuthenticationFilter.evictUserCache(user.getUsername());
    }

    /**
     * 判断用户是否为管理员
     */
    public boolean isAdmin(Long userId) {
        return userRepository.findById(userId)
                .map(u -> "admin".equals(u.getRole()))
                .orElse(false);
    }

    /**
     * 批量查询用户ID到用户名的映射
     */
    public Map<Long, String> getUserIdToNameMap(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Map.of();
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername));
    }
}
