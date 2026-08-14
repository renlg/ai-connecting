package com.aiconnecting.service;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class OAuthException extends RuntimeException {
    private final HttpStatus status;

    public OAuthException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
