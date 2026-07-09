package com.aiconnecting.dto;

import lombok.*;

@Data
@Builder
public class LoginResponse {
    private String token;
    private Long id;
    private String username;
    private String nickname;
    private String role;
    private String inviteCode;
}
