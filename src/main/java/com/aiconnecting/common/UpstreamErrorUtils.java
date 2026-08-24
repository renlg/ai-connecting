package com.aiconnecting.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Exposes upstream 400 errors so callers can relay the real upstream message. */
public final class UpstreamErrorUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private UpstreamErrorUtils() {}

    public static boolean isClientFixableUpstreamError(BusinessException error) {
        return error != null && error.isUpstreamResponse() && error.getCode() == 400;
    }

    public static boolean isClientFixableUpstreamError(int status, String responseBody) {
        return status == 400 && responseBody != null && !responseBody.isBlank();
    }

    /** Returns only the upstream message field, or the original text for a non-JSON response. */
    public static String extractUpstreamMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return null;
        return parse(responseBody).message();
    }

    /** Applies the public relay policy while retaining a trace id through the caller's upstream flag. */
    public static String clientFacingMessage(int status, String responseBody) {
        if (status == 400) {
            String message = extractUpstreamMessage(responseBody);
            if (message != null && !message.isBlank()) return message;
        }
        return SseUtils.GENERIC_UPSTREAM_ERROR_MESSAGE;
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
