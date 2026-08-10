package com.aiconnecting.common;

public class BusinessException extends RuntimeException {
    private final int code;
    /** Raw body returned by an upstream HTTP response; null for local/business validation failures. */
    private final String upstreamResponseBody;
    /** Retry-After/reset hint supplied by the upstream, in seconds. */
    private final Long retryAfterSeconds;
    private final boolean upstreamResponse;

    public BusinessException(String message) {
        super(message);
        this.code = 400;
        this.upstreamResponseBody = null;
        this.retryAfterSeconds = null;
        this.upstreamResponse = false;
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
        this.upstreamResponseBody = null;
        this.retryAfterSeconds = null;
        this.upstreamResponse = false;
    }

    public BusinessException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.upstreamResponseBody = null;
        this.retryAfterSeconds = null;
        this.upstreamResponse = false;
    }

    public BusinessException(int code, String message, Throwable cause, String upstreamResponseBody,
                             Long retryAfterSeconds, boolean upstreamResponse) {
        super(message, cause);
        this.code = code;
        this.upstreamResponseBody = upstreamResponseBody;
        this.retryAfterSeconds = retryAfterSeconds;
        this.upstreamResponse = upstreamResponse;
    }

    public static BusinessException upstream(int code, String message, String responseBody, Long retryAfterSeconds) {
        return new BusinessException(code, message, null, responseBody, retryAfterSeconds, true);
    }

    public int getCode() {
        return code;
    }

    public String getUpstreamResponseBody() {
        return upstreamResponseBody;
    }

    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public boolean isUpstreamResponse() {
        return upstreamResponse;
    }
}
