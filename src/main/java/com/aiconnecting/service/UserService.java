package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.dto.LoginRequest;
import com.aiconnecting.dto.LoginResponse;
import com.aiconnecting.dto.RegisterRequest;
import com.aiconnecting.entity.User;
import com.aiconnecting.repository.UserRepository;
import com.aiconnecting.security.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;

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

    public User updateProfile(Long userId, String nickname, String email) {
        User user = getById(userId);
        if (nickname != null) user.setNickname(nickname);
        if (email != null) user.setEmail(email);
        return userRepository.save(user);
    }

    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = getById(userId);
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException("原密码错误");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }
}
