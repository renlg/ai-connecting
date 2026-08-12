package com.aiconnecting.common;

import java.util.regex.Pattern;

/** URL assembly for OpenAI-compatible upstream endpoints. */
public final class OpenAiUrlUtils {

    private static final Pattern VERSION_SEGMENT = Pattern.compile("/v\\d+$", Pattern.CASE_INSENSITIVE);

    private OpenAiUrlUtils() {
    }

    /**
     * Builds the chat-completions endpoint without inserting {@code /v1} after an
     * upstream-specific version segment such as {@code /v1} or {@code /v4}.
     */
    public static String chatCompletionsUrl(String baseUrl) {
        String base = baseUrl.replaceAll("/+$", "");
        String path = VERSION_SEGMENT.matcher(base).find()
                ? "/chat/completions"
                : "/v1/chat/completions";
        return base + path;
    }
}
