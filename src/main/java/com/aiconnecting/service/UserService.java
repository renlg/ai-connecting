package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.CacheInvalidationService;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
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
    private final CacheInvalidationService cacheInvalidationService;
    private final InviteCodeService inviteCodeService;

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
        User existingAdmin = userRepository.findByUsername("admin").orElse(null);
        if (existingAdmin != null) {
            if (!Integer.valueOf(5).equals(existingAdmin.getLevel())) {
                existingAdmin.setLevel(5);
                userRepository.save(existingAdmin);
                cacheInvalidationService.publish(CacheInvalidationService.USER_PREFIX + existingAdmin.getId());
                log.info("admin 用户等级已校正为 5");
            }
            log.info("admin 用户已存在，跳过初始化");
            return;
        }
        User admin = User.builder()
                .username("admin")
                .password(passwordEncoder.encode(adminDefaultPassword))
                .nickname("Administrator")
                .role("admin")
                .quota(-1L)
                .usedQuota(0L)
                .status(1)
                .level(5)
                .build();
        try {
            User saved = userRepository.save(admin);
            cacheInvalidationService.publish(CacheInvalidationService.USER_PREFIX + saved.getId());
            log.warn("数据库中无 admin 用户，已使用默认密码创建默认管理员，请尽快修改密码");
        } catch (DataIntegrityViolationException e) {
            // 多实例在全新数据库上同时启动时，两边都可能查到 admin 不存在并尝试创建，
            // username 唯一约束让后完成的一方在此处收到违反约束异常。但同一异常类型也可能是密码列
            // 长度不够、NOT NULL 违反、schema 不匹配等真实错误，不能一概当作良性竞态吞掉——
            // 重新查一次 admin 是否确实已存在：存在才是良性竞态，否则说明是真实错误，必须继续抛出，
            // 避免应用在没有 admin 用户的情况下悄悄启动成功。
            if (userRepository.existsByUsername("admin")) {
                log.info("admin 用户已被其他实例并发创建，跳过本次初始化: {}", e.getMessage());
            } else {
                throw e;
            }
        }
    }

    public LoginResponse login(LoginRequest request, String clientIp) {
        String loginFailKey = "login_fail:" + request.getUsername();

        if (redisTemplate != null) {
            Long failCount = redisTemplate.<String, Long>opsForHash().get(loginFailKey, clientIp);
            if (failCount != null && failCount >= LOGIN_MAX_FAIL_ATTEMPTS) {
                throw new BusinessException("该账号因登录失败次数过多已被锁定，请1小时后再试", "This account is locked due to too many failed login attempts; try again in one hour");
            }
        }

        User user = userRepository.findByUsername(request.getUsername()).orElse(null);
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            recordLoginFailure(loginFailKey, clientIp);
            throw new BusinessException("用户名或密码错误", "Incorrect username or password");
        }

        if (user.getStatus() != 1) {
            throw new BusinessException("账号已被禁用", "Account disabled");
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

    @Transactional
    public User register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new BusinessException("用户名已存在", "Username already exists");
        }

        inviteCodeService.consume(request.getInviteCode());

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

        User saved = userRepository.save(user);
        cacheInvalidationService.publish(CacheInvalidationService.USER_PREFIX + saved.getId());
        return saved;
    }

    public User getById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new BusinessException("用户不存在", "User not found"));
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
        long generation = cacheInvalidationService.generation(CacheInvalidationService.USER_PREFIX);
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException("用户不存在", "User not found"));
        if (cacheInvalidationService.isCurrentGeneration(CacheInvalidationService.USER_PREFIX, generation)) {
            CachedUser fresh = new CachedUser(user, System.currentTimeMillis());
            userCache.put(id, fresh);
            if (!cacheInvalidationService.isCurrentGeneration(CacheInvalidationService.USER_PREFIX, generation)) {
                userCache.remove(id, fresh);
            }
        }
        return user;
    }

    /**
     * 清除用户缓存（积分/状态变更时调用）
     */
    public void evictUserCache(Long userId) {
        if (userId != null) {
            userCache.remove(userId);
            cacheInvalidationService.publish(CacheInvalidationService.USER_PREFIX + userId);
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
        User saved = userRepository.save(user);
        cacheInvalidationService.publish(CacheInvalidationService.USER_PREFIX + userId);
        return saved;
    }

    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = getById(userId);
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException("原密码错误", "Incorrect current password");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        cacheInvalidationService.publish(CacheInvalidationService.USER_PREFIX + userId);
        clearLoginFailRecords(user.getUsername());
    }

    /**
     * 搜索用户（支持关键字）
     */
    public List<User> searchUsers(String keyword) {
        if (keyword != null && !keyword.trim().isEmpty()) {
            return userRepository.searchByKeyword(keyword.trim());
        }
        return userRepository.findAllOrderByCreatedAtDesc();
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
     * 更新用户等级 (1-5)
     */
    @Transactional
    public void updateLevel(Long userId, Integer level) {
        User user = getById(userId);
        user.setLevel(level);
        userRepository.save(user);
        // 清除用户缓存，使等级变更立即生效
        jwtAuthenticationFilter.evictUserCache(user.getUsername());
        evictUserCache(userId);
    }

    /**
     * 余额充足时原子预扣积分（媒体请求调用上游前预扣，失败时通过 refundCredits 退回）
     *
     * @return true=扣减成功；false=余额不足，未扣减
     */
    @Transactional
    public boolean tryDeductCredits(Long userId, BigDecimal amount) {
        boolean deducted = userRepository.tryDeductCredits(userId, amount) > 0;
        if (deducted) {
            evictUserCache(userId);
        }
        return deducted;
    }

    /**
     * 退回预扣积分（上游请求失败时调用）
     */
    @Transactional
    public void refundCredits(Long userId, BigDecimal amount) {
        userRepository.addCredits(userId, amount);
        evictUserCache(userId);
    }

    /**
     * 按实际用量结算时的补扣（预估预扣少于实际消耗时调用），允许余额透支：
     * 无下限扣减保证实际扣减金额与使用日志记录的计费金额一致
     */
    @Transactional
    public void deductCreditsSettlement(Long userId, BigDecimal amount) {
        userRepository.deductCreditsAllowNegative(userId, amount);
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

    @org.springframework.context.event.EventListener
    public void onCacheInvalidation(CacheInvalidationService.CacheInvalidationEvent event) {
        String route = event.route();
        if (!route.startsWith(CacheInvalidationService.USER_PREFIX)) {
            return;
        }
        try {
            Long userId = Long.valueOf(route.substring(CacheInvalidationService.USER_PREFIX.length()));
            userCache.remove(userId);
        } catch (NumberFormatException ignored) {
            // 非法/未知消息忽略
        }
    }
}
