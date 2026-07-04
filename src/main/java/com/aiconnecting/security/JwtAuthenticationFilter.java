package com.aiconnecting.security;

import com.aiconnecting.entity.User;
import com.aiconnecting.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
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

    /** 用户缓存，减少数据库查询，缓存 1 分钟（缩短以减少角色/状态变更延迟） */
    private final Map<String, CachedUser> userCache = new ConcurrentHashMap<>();
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
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
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

    /**
     * 从缓存获取用户，缓存未命中或过期时查询数据库
     */
    private User getCachedUser(String username) {
        CachedUser cached = userCache.get(username);
        if (cached != null && !cached.isExpired()) {
            return cached.user();
        }
        User user = userRepository.findByUsername(username).orElse(null);
        if (user != null) {
            userCache.put(username, new CachedUser(user, System.currentTimeMillis()));
        } else {
            userCache.remove(username);
        }
        return user;
    }

    /**
     * 清除指定用户的缓存（密码修改、状态变更时调用）
     */
    public void evictUserCache(String username) {
        userCache.remove(username);
    }

}
