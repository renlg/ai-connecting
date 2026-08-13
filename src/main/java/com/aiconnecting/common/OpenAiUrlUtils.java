package com.aiconnecting.common;

import java.util.regex.Pattern;

/** URL assembly for OpenAI-compatible upstream endpoints. */
public final class OpenAiUrlUtils {

    private static final Pattern VERSION_SEGMENT = Pattern.compile("/v\\d+$", Pattern.CASE_INSENSITIVE);

    private OpenAiUrlUtils() {
    }

    /**
     * Builds an OpenAI-compatible endpoint URL. When the base URL already ends
     * in a version segment, the conventional leading {@code /v1} is omitted
     * from the endpoint path so the upstream version is preserved.
     */
    public static String endpointUrl(String baseUrl, String subPath) {
        String base = baseUrl.replaceAll("/+$", "");
        String path = subPath;
        if (VERSION_SEGMENT.matcher(base).find() && path.matches("(?i)^/v1(?:/.*)?$")) {
            path = path.substring(3);
        }
        return base + path;
    }

    /**
     * Builds the chat-completions endpoint without inserting {@code /v1} after an
     * upstream-specific version segment such as {@code /v1} or {@code /v4}.
     */
    public static String chatCompletionsUrl(String baseUrl) {
        return endpointUrl(baseUrl, "/v1/chat/completions");
    }

    /**
     * Builds the models endpoint without inserting {@code /v1} after an
     * upstream-specific version segment such as {@code /v1} or {@code /v4}.
     */
    public static String modelsUrl(String baseUrl) {
        return modelsUrl(baseUrl, "v1");
    }

    /**
     * Builds a protocol-specific models endpoint, using {@code defaultVersion}
     * only when the base URL does not already end in a version segment.
     */
    public static String modelsUrl(String baseUrl, String defaultVersion) {
        String base = baseUrl.replaceAll("/+$", "");
        String versionSuffix = "/" + defaultVersion;
        boolean hasVersionSegment = VERSION_SEGMENT.matcher(base).find()
                || base.regionMatches(true, base.length() - Math.min(base.length(), versionSuffix.length()),
                        versionSuffix, 0, versionSuffix.length());
        return base + (hasVersionSegment ? "/models" : versionSuffix + "/models");
    }
}
