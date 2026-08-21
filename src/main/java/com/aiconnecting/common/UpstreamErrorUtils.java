package com.aiconnecting.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;

/** Selectively exposes upstream 400 errors that callers can fix by changing request parameters. */
public final class UpstreamErrorUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private UpstreamErrorUtils() {}

    public static boolean isClientFixableUpstreamError(BusinessException error) {
        if (error == null || !error.isUpstreamResponse() || error.getCode() != 400) return false;

        String responseBody = error.getUpstreamResponseBody();
        if (isClientFixableUpstreamError(error.getCode(), responseBody)) return true;
        return matchesClientFixableMessage(error.getEnglishMessage(), null);
    }

    public static boolean isClientFixableUpstreamError(int status, String responseBody) {
        if (status != 400 || responseBody == null || responseBody.isBlank()) return false;

        ParsedUpstreamError parsed = parse(responseBody);
        return matchesClientFixableMessage(parsed.message(), parsed.type());
    }

    /** Returns only the upstream message field, or the original text for a non-JSON response. */
    public static String extractUpstreamMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return null;
        return parse(responseBody).message();
    }

    /** Applies the public relay policy while retaining a trace id through the caller's upstream flag. */
    public static String clientFacingMessage(int status, String responseBody) {
        if (isClientFixableUpstreamError(status, responseBody)) {
            String message = extractUpstreamMessage(responseBody);
            if (message != null && !message.isBlank()) return message;
        }
        return SseUtils.GENERIC_UPSTREAM_ERROR_MESSAGE;
    }

    private static boolean matchesClientFixableMessage(String message, String errorType) {
        if (message == null || message.isBlank()) return false;
        String normalized = message.toLowerCase(Locale.ROOT);

        if (normalized.contains("arguments must be valid json")
                || normalized.contains("invalid json")
                || normalized.contains("json parse error")
                || normalized.contains("json parse")
                || normalized.contains("parse error")
                || normalized.contains("request body malformed")
                || normalized.contains("malformed request")
                || normalized.contains("malformed json")) {
            return true;
        }

        String normalizedType = errorType == null ? "" : errorType.toLowerCase(Locale.ROOT);
        if ((normalized.contains("invalid_request_error")
                || normalizedType.contains("invalid_request_error"))
                && normalized.contains("json")) {
            return true;
        }

        boolean parameterSubject = normalized.contains("argument")
                || normalized.contains("parameter")
                || normalized.contains("tool call")
                || normalized.contains("tool_call")
                || normalized.contains("function call")
                || normalized.contains("function_call")
                || normalized.contains("request body");
        boolean formatOrValidation = normalized.contains("invalid")
                || normalized.contains("not valid")
                || normalized.contains("malformed")
                || normalized.contains("parse")
                || normalized.contains("format")
                || normalized.contains("validation")
                || normalized.contains("validate")
                || normalized.contains("must be")
                || normalized.contains("must contain")
                || normalized.contains("required")
                || normalized.contains("expected")
                || normalized.contains("missing")
                || normalized.contains("unknown field")
                || normalized.contains("unrecognized field");
        return parameterSubject && formatOrValidation;
    }

    private static ParsedUpstreamError parse(String responseBody) {
        String trimmed = responseBody.trim();
        try {
            JsonNode root = MAPPER.readTree(trimmed);
            JsonNode error = root.path("error");
            JsonNode source = error.isObject() ? error : root;
            String message = text(source.path("message"));
            if (message == null && error.isTextual()) message = error.asText();
            if (message == null && root.isTextual()) message = root.asText();
            if (message == null && !root.isContainerNode()) message = trimmed;
            String type = text(source.path("type"));
            if (type == null) type = text(source.path("status"));
            return new ParsedUpstreamError(message, type);
        } catch (Exception ignored) {
            return new ParsedUpstreamError(trimmed, null);
        }
    }

    private static String text(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private record ParsedUpstreamError(String message, String type) {}
}
