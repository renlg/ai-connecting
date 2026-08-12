package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.Set;

/** Classifies failures using structured upstream error metadata whenever it is available. */
final class FailureClassifier {

    enum Kind { RATE_LIMIT, QUOTA, MODEL_NOT_FOUND, CHANNEL, FAST_FAIL }

    record Classification(boolean switchable, Kind kind, Long retryAfterSeconds) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> MODEL_NOT_FOUND = Set.of("model_not_found", "model_decommissioned");
    private static final Set<String> QUOTA = Set.of(
            "quota", "insufficient_quota", "insufficient_user_quota");
    private static final Set<String> RATE_LIMIT = Set.of(
            "rate_limit", "rate_limit_exceeded", "overloaded");
    private static final Set<String> UPSTREAM_AUTH = Set.of(
            "authentication_error", "invalid_api_key", "unauthorized", "upstream_auth_error");
    private static final Set<String> POLICY = Set.of(
            "moderation", "policy", "content_filter", "content_policy_violation");

    private FailureClassifier() {}

    static boolean isSwitchable(BusinessException e) {
        return classify(e).switchable();
    }

    static Classification classify(BusinessException e) {
        BusinessException upstream = findUpstreamResponse(e);
        if (upstream != null) {
            return classifyUpstream(upstream.getCode(), upstream.getUpstreamResponseBody(),
                    upstream.getRetryAfterSeconds());
        }

        Throwable cause = rootCause(e);
        if (cause instanceof SocketTimeoutException) {
            return new Classification(true, Kind.CHANNEL, null);
        }
        if (cause instanceof IOException && !isCancellation(cause)) {
            return new Classification(true, Kind.CHANNEL, null);
        }
        // A locally-created BusinessException is a balance/permission/validation failure, even if its
        // pseudo status happens to be 429/502. It must never activate a fallback group.
        return new Classification(false, Kind.FAST_FAIL, null);
    }

    static boolean isSwitchable(int code, String errorBody) {
        return classifyUpstream(code, errorBody, null).switchable();
    }

    static Classification classifyUpstream(int code, String errorBody, Long retryAfterSeconds) {
        if (code == 408 || code >= 500) {
            return new Classification(true, Kind.CHANNEL, retryAfterSeconds);
        }
        if (code == 429) {
            return new Classification(true, Kind.RATE_LIMIT, retryAfterSeconds);
        }
        ParsedError parsed = parse(errorBody);
        if (parsed != null) {
            // Recognized structured fields are authoritative. Message substring matching remains a
            // compatibility fallback only when code/type/param do not provide a known classification.
            Kind structuredKind = classifyStructuredFields(parsed.code(), parsed.type(), parsed.param());
            if (structuredKind != null) {
                return new Classification(structuredKind != Kind.FAST_FAIL, structuredKind, retryAfterSeconds);
            }
            Kind messageKind = classifyStructuredMessage(parsed.message());
            if (messageKind != null) {
                return new Classification(messageKind != Kind.FAST_FAIL, messageKind, retryAfterSeconds);
            }
        } else {
            // Last-resort compatibility for non-JSON upstreams only.
            Kind fallback = classifyLegacyMessage(errorBody);
            if (fallback != null) {
                return new Classification(fallback != Kind.FAST_FAIL, fallback, retryAfterSeconds);
            }
        }

        if (code == 400) {
            // Product decision: every upstream 400 that isn't one of the specific fast-fail signals
            // above (context_length_exceeded / moderation / request-too-large) must switch to the next
            // group member instead of failing the whole request — e.g. groq/compound (free group
            // member) rejecting tool calling with a generic invalid_request_error.
            return new Classification(true, Kind.CHANNEL, retryAfterSeconds);
        }
        if (code == 413 || code == 422) {
            return new Classification(false, Kind.FAST_FAIL, retryAfterSeconds);
        }
        if (code == 401 || code == 403) {
            return new Classification(true, Kind.CHANNEL, retryAfterSeconds);
        }
        return new Classification(false, Kind.FAST_FAIL, retryAfterSeconds);
    }

    private static BusinessException findUpstreamResponse(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof BusinessException be && be.isUpstreamResponse()) return be;
            current = current.getCause();
        }
        return null;
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    private static boolean isCancellation(Throwable error) {
        String name = error.getClass().getName().toLowerCase(Locale.ROOT);
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        return name.contains("clientabort") || message.contains("cancelled") || message.contains("canceled");
    }

    private static ParsedError parse(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode error = root.path("error");
            JsonNode source = error.isObject() ? error : root;
            return new ParsedError(normalize(source.path("code").asText(null)),
                    normalize(source.path("type").asText(null)),
                    normalize(source.path("param").asText(null)), source.path("message").asText(null));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Kind classifyStructuredMessage(String message) {
        if (message == null) return null;
        String normalized = normalize(message);
        if (normalized.contains("context_length_exceeded") || normalized.contains("request_too_large")
                || normalized.contains("content_filter") || normalized.contains("moderation")
                || normalized.contains("policy_violation")
                || normalized.contains("unable_to_generate_this_content")) return Kind.FAST_FAIL;
        if (normalized.contains("model_not_found") || normalized.contains("model_decommissioned")) return Kind.MODEL_NOT_FOUND;
        if (normalized.contains("insufficient_quota") || normalized.contains("quota_exceeded")) return Kind.QUOTA;
        if (normalized.contains("rate_limit_exceeded") || normalized.contains("rate_limit")) return Kind.RATE_LIMIT;
        return null;
    }

    private static Kind classifyStructuredFields(String code, String type, String param) {
        Set<String> values = new java.util.HashSet<>();
        values.add(code);
        values.add(type);
        if (values.contains("context_length_exceeded") || values.contains("request_too_large")
                || values.stream().anyMatch(POLICY::contains)
                || ("invalid_request_error".equals(type) && "prompt".equals(param))) return Kind.FAST_FAIL;
        if (values.stream().anyMatch(MODEL_NOT_FOUND::contains)) return Kind.MODEL_NOT_FOUND;
        if (values.stream().anyMatch(QUOTA::contains)) return Kind.QUOTA;
        if (values.stream().anyMatch(RATE_LIMIT::contains)) return Kind.RATE_LIMIT;
        if (values.stream().anyMatch(UPSTREAM_AUTH::contains)) return Kind.CHANNEL;
        return null;
    }

    private static Kind classifyLegacyMessage(String message) {
        if (message == null) return null;
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("context_length_exceeded") || lower.contains("request_too_large")
                || lower.contains("content_filter") || lower.contains("moderation")
                || lower.contains("policy") || lower.contains("unable to generate this content")) return Kind.FAST_FAIL;
        if (lower.contains("model_not_found") || lower.contains("model_decommissioned")) return Kind.MODEL_NOT_FOUND;
        if (lower.contains("insufficient_quota") || lower.contains("insufficient_user_quota") || lower.contains("quota")) return Kind.QUOTA;
        if (lower.contains("rate_limit") || lower.contains("overloaded")) return Kind.RATE_LIMIT;
        return null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private record ParsedError(String code, String type, String param, String message) {}
}
