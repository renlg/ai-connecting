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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Autowired(required = false)
    private RedisTemplate<String, Long> redisTemplate;

    private static final int LOGIN_MAX_FAIL_ATTEMPTS = 5;
    private static final long LOGIN_FAIL_LOCK_SECONDS = 3600;

    /** 用户缓存，转发请求验证时避免每次查库，缓存 30 秒 */
    private final ConcurrentHashMap<Long, CachedUser> userCache = new ConcurrentHashMap<>();
    private static final long USER_CACHE_TTL_MS = 30 * 1000L;

    private record CachedUser(User user, long cachedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > USER_CACHE_TTL_MS;
        }
    }

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

    /**
     * 应用完全启动后（Hibernate schema 已更新），为缺少邀请码的用户补生邀请码
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        ensureAllUsersHaveInviteCode();
    }

    public LoginResponse login(LoginRequest request, String clientIp) {
        String loginFailKey = "login_fail:" + request.getUsername();

        if (redisTemplate != null) {
            Long failCount = redisTemplate.<String, Long>opsForHash().get(loginFailKey, clientIp);
            if (failCount != null && failCount >= LOGIN_MAX_FAIL_ATTEMPTS) {
                throw new BusinessException("该账号因登录失败次数过多已被锁定，请1小时后再试");
            }
        }

        User user = userRepository.findByUsername(request.getUsername()).orElse(null);
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            recordLoginFailure(loginFailKey, clientIp);
            throw new BusinessException("用户名或密码错误");
        }

        if (user.getStatus() != 1) {
            throw new BusinessException("账号已被禁用");
        }

        if (redisTemplate != null) {
            redisTemplate.opsForHash().delete(loginFailKey, clientIp);
        }

        String token = jwtUtils.generateToken(user.getUsername(), user.getRole());
        return LoginResponse.builder()
                .token(token)
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .role(user.getRole())
                .inviteCode(user.getInviteCode())
                .build();
    }

    private void recordLoginFailure(String loginFailKey, String clientIp) {
        if (redisTemplate == null) {
            return;
        }
        Long failCount = redisTemplate.<String, Long>opsForHash().increment(loginFailKey, clientIp, 1);
        if (failCount != null && failCount == 1) {
            redisTemplate.expire(loginFailKey, LOGIN_FAIL_LOCK_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * 清除该账号在所有IP维度下的登录失败记录
     */
    private void clearLoginFailRecords(String username) {
        if (redisTemplate == null) {
            return;
        }
        redisTemplate.delete("login_fail:" + username);
    }

    public User register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new BusinessException("用户名已存在");
        }

        // 验证邀请码
        if (request.getInviteCode() == null || request.getInviteCode().isBlank()) {
            throw new BusinessException("邀请码不能为空");
        }
        if (!userRepository.existsByInviteCode(request.getInviteCode().trim())) {
            throw new BusinessException("邀请码无效");
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
                .inviteCode(generateInviteCode())
                .build();

        return userRepository.save(user);
    }

    public User getById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new BusinessException("用户不存在"));
    }

    /**
     * 获取用户（带缓存，供转发链路使用，避免每次请求查库）
     * 缓存 30 秒，积分/状态变更时主动清除
     */
    public User getByIdCached(Long id) {
        CachedUser cached = userCache.get(id);
        if (cached != null && !cached.isExpired()) {
            return cached.user();
        }
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        userCache.put(id, new CachedUser(user, System.currentTimeMillis()));
        return user;
    }

    /**
     * 清除用户缓存（积分/状态变更时调用）
     */
    public void evictUserCache(Long userId) {
        if (userId != null) {
            userCache.remove(userId);
        }
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
        clearLoginFailRecords(user.getUsername());
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
        evictUserCache(userId);
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
        evictUserCache(userId);
        clearLoginFailRecords(user.getUsername());
    }

    /**
     * 更新用户积分
     */
    @Transactional
    public void updateCredits(Long userId, BigDecimal credits) {
        User user = getById(userId);
        user.setCredits(credits);
        userRepository.save(user);
        // 清除用户缓存，使积分变更尽快生效
        jwtAuthenticationFilter.evictUserCache(user.getUsername());
        evictUserCache(userId);
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

    /**
     * 获取用户邀请码
     */
    public String getInviteCode(Long userId) {
        User user = getById(userId);
        return user.getInviteCode();
    }

    private static final String INVITE_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int INVITE_CODE_LENGTH = 8;
    private final SecureRandom secureRandom = new SecureRandom();

    private String generateInviteCode() {
        StringBuilder sb = new StringBuilder(INVITE_CODE_LENGTH);
        for (int i = 0; i < INVITE_CODE_LENGTH; i++) {
            sb.append(INVITE_CODE_CHARS.charAt(secureRandom.nextInt(INVITE_CODE_CHARS.length())));
        }
        String code = sb.toString();
        if (userRepository.existsByInviteCode(code)) {
            return generateInviteCode();
        }
        return code;
    }

    private void ensureAllUsersHaveInviteCode() {
        List<User> usersWithoutCode = userRepository.findAll().stream()
                .filter(u -> u.getInviteCode() == null || u.getInviteCode().isBlank())
                .toList();
        for (User u : usersWithoutCode) {
            u.setInviteCode(generateInviteCode());
            userRepository.save(u);
            log.info("为用户 {} 生成邀请码: {}", u.getUsername(), u.getInviteCode());
        }
    }
}
