package com.aiconnecting.entity;

import jakarta.persistence.*;
import lombok.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Entity
@Table(name = "oauth_clients")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OAuthClient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", unique = true, nullable = false, length = 100)
    private String clientId;

    /** 存储 sha256:<hex> 哈希值；无前缀的存量行按明文兼容比较 */
    @Column(name = "client_secret", nullable = false, length = 255)
    private String clientSecret;

    /** 入库前调用，避免明文 secret 落库 */
    public static String hashSecret(String plainSecret) {
        return HASHED_SECRET_PREFIX + sha256Hex(plainSecret);
    }

    /** 常量时间比较，防止通过响应时间逐字节猜测 secret */
    public boolean matchesSecret(String providedSecret) {
        if (providedSecret == null || clientSecret == null) {
            return false;
        }
        if (clientSecret.startsWith(HASHED_SECRET_PREFIX)) {
            return MessageDigest.isEqual(
                    clientSecret.substring(HASHED_SECRET_PREFIX.length()).getBytes(StandardCharsets.US_ASCII),
                    sha256Hex(providedSecret).getBytes(StandardCharsets.US_ASCII));
        }
        return MessageDigest.isEqual(
                clientSecret.getBytes(StandardCharsets.UTF_8),
                providedSecret.getBytes(StandardCharsets.UTF_8));
    }

    private static final String HASHED_SECRET_PREFIX = "sha256:";

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }

    @Column(name = "redirect_uri", nullable = false, length = 1000)
    private String redirectUri;

    @Column(nullable = false, length = 200)
    private String name;

    @Builder.Default
    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (enabled == null) enabled = true;
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
