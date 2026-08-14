package com.aiconnecting.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

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

    /** Plaintext by design for the current internal-only client registry. */
    @Column(name = "client_secret", nullable = false, length = 255)
    private String clientSecret;

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
