package com.aiconnecting.common;

public class BusinessException extends RuntimeException {
    private final int code;
    /** English message returned by client-facing relay endpoints; null for legacy callers. */
    private final String englishMessage;
    /** Raw body returned by an upstream HTTP response; null for local/business validation failures. */
    private final String upstreamResponseBody;
    /** Retry-After/reset hint supplied by the upstream, in seconds. */
    private final Long retryAfterSeconds;
    private final boolean upstreamResponse;

    public BusinessException(String message) {
        this(400, message, (String) null);
    }

    public BusinessException(String message, String englishMessage) {
        this(400, message, englishMessage);
    }

    public BusinessException(int code, String message) {
        this(code, message, (String) null);
    }

    public BusinessException(int code, String message, String englishMessage) {
        this(code, message, englishMessage, null, null, null, false);
    }

    public BusinessException(int code, String message, Throwable cause) {
        this(code, message, null, cause);
    }

    public BusinessException(int code, String message, String englishMessage, Throwable cause) {
        this(code, message, englishMessage, cause, null, null, false);
    }

    public BusinessException(int code, String message, Throwable cause, String upstreamResponseBody,
                             Long retryAfterSeconds, boolean upstreamResponse) {
        this(code, message, null, cause, upstreamResponseBody, retryAfterSeconds, upstreamResponse);
    }

    public BusinessException(int code, String message, String englishMessage, Throwable cause,
                             String upstreamResponseBody, Long retryAfterSeconds, boolean upstreamResponse) {
        super(message, cause);
        this.code = code;
        this.englishMessage = englishMessage;
        this.upstreamResponseBody = upstreamResponseBody;
        this.retryAfterSeconds = retryAfterSeconds;
        this.upstreamResponse = upstreamResponse;
    }

    public static BusinessException upstream(int code, String message, String responseBody, Long retryAfterSeconds) {
        return upstream(code, message, null, responseBody, retryAfterSeconds);
    }

    public static BusinessException upstream(int code, String message, String englishMessage,
                                             String responseBody, Long retryAfterSeconds) {
        return new BusinessException(code, message, englishMessage, null, responseBody, retryAfterSeconds, true);
    }

    public int getCode() {
        return code;
    }

    public String getEnglishMessage() {
        return englishMessage;
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
