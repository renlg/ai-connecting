package com.aiconnecting.config;

import com.aiconnecting.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.MediaType;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Value("${app.cors.allowed-origins:}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // ⚠️ 注意：Spring Security 的规则是按顺序匹配的，先匹配的规则优先生效
                // 因此必须将更具体的路径放在前面，更通用的路径放在后面
                // 公开接口
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/v1/chat/completions").permitAll()
                .requestMatchers("/v1/completions").permitAll()
                .requestMatchers("/v1/embeddings").permitAll()
                .requestMatchers("/v1/models").permitAll()
                .requestMatchers("/v1/images/**").permitAll()
                .requestMatchers("/v1/audio/**").permitAll()
                .requestMatchers("/v1/messages").permitAll()
                .requestMatchers("/v1/messages/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/v1/models/*:generateContent").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/v1/models/*:streamGenerateContent").permitAll()
                .requestMatchers("/health").permitAll()
                // 仪表盘和模型列表接口允许所有已认证用户访问（控制器内部按角色返回数据）
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/admin/dashboard").authenticated()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/admin/dashboard/daily-stats").authenticated()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/admin/models").authenticated()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/admin/models/enabled").authenticated()
                // Actuator 端点需要认证
                .requestMatchers("/actuator/**").authenticated()
                // 管理接口需要 admin 角色（必须在 /api/** 之前，否则会被通用规则拦截）
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // 其他 API 需要认证
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.getWriter().write("{\"code\":401,\"message\":\"未登录或登录已过期，请重新登录\"}");
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.getWriter().write("{\"code\":403,\"message\":\"权限不足\"}");
                })
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // 支持通过环境变量配置允许的源，未配置时默认允许所有
        // 统一使用 allowedOriginPatterns：setAllowedOrigins("*") 与 allowCredentials(true) 同时使用不合法，
        // 而 patterns 会按实际请求 Origin 动态匹配返回，即使配置值本身就是 "*" 也不会违反 CORS 规范
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            config.setAllowedOriginPatterns(Arrays.asList(allowedOrigins.split(",")));
        } else {
            config.setAllowedOriginPatterns(List.of("*"));
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
