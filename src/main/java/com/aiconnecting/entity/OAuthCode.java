package com.aiconnecting.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "oauth_codes")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OAuthCode {

    @Id
    @Column(length = 64)
    private String code;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "redirect_uri", nullable = false, length = 1000)
    private String redirectUri;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Builder.Default
    @Column(nullable = false)
    private Boolean used = false;

    @PrePersist
    protected void onCreate() {
        if (used == null) used = false;
    }
}
