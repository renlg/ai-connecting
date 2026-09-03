package com.aiconnecting.security;

import com.aiconnecting.common.CacheInvalidationService;
import com.aiconnecting.entity.User;
import com.aiconnecting.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Cookie;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final UserRepository userRepository;
    private final CacheInvalidationService cacheInvalidationService;

    /** 用户缓存，减少数据库查询，缓存 1 分钟（缩短以减少角色/状态变更延迟） */
    private final Map<String, CachedUser> userCache = new ConcurrentHashMap<>();
    /** user ID -> username 反向索引，避免每次余额变更都扫描整个 JWT 缓存。 */
    private final Map<Long, String> usernameByUserId = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 60 * 1000L;

    private record CachedUser(User user, long cachedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > CACHE_TTL_MS;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : cookieToken(request);
        if (token != null) {
            if (jwtUtils.validateToken(token)) {
                String username = jwtUtils.getUsernameFromToken(token);
                User user = getCachedUser(username);
                if (user != null && user.getStatus() == 1) {
                    var auth = new UsernamePasswordAuthenticationToken(
                            user, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().toUpperCase()))
                    );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    private String cookieToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if ("aic_token".equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    /**
     * 从缓存获取用户，缓存未命中或过期时查询数据库
     */
    private User getCachedUser(String username) {
        CachedUser cached = userCache.get(username);
        if (cached != null && !cached.isExpired()) {
            return cached.user();
        }
        long generation = cacheInvalidationService.generation(CacheInvalidationService.USER_PREFIX);
        User user = userRepository.findByUsername(username).orElse(null);
        if (user != null) {
            if (cacheInvalidationService.isCurrentGeneration(CacheInvalidationService.USER_PREFIX, generation)) {
                CachedUser fresh = new CachedUser(user, System.currentTimeMillis());
                userCache.put(username, fresh);
                usernameByUserId.put(user.getId(), username);
                if (!cacheInvalidationService.isCurrentGeneration(CacheInvalidationService.USER_PREFIX, generation)) {
                    userCache.remove(username, fresh);
                    usernameByUserId.remove(user.getId(), username);
                }
            }
        } else {
            userCache.remove(username);
        }
        return user;
    }

    /**
     * 清除指定用户的缓存（密码修改、状态变更时调用）
     */
    public void evictUserCache(String username) {
        if (username != null) {
            CachedUser removed = userCache.remove(username);
            if (removed != null) {
                usernameByUserId.remove(removed.user().getId(), username);
            }
        }
    }

    @EventListener
    public void onCacheInvalidation(CacheInvalidationService.CacheInvalidationEvent event) {
        String route = event.route();
        if (!route.startsWith(CacheInvalidationService.USER_PREFIX)) {
            return;
        }
        try {
            long userId = Long.parseLong(route.substring(CacheInvalidationService.USER_PREFIX.length()));
            String username = usernameByUserId.remove(userId);
            if (username != null) {
                userCache.remove(username);
            }
        } catch (NumberFormatException ignored) {
            // 非法/未知消息忽略
        }
    }

}
