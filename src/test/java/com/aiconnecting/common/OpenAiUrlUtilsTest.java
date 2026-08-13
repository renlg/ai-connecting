package com.aiconnecting.common;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAiUrlUtilsTest {

    @ParameterizedTest
    @CsvSource({
            "https://open.bigmodel.cn/api/paas/v4/, https://open.bigmodel.cn/api/paas/v4/chat/completions",
            "https://api.agnes-ai.cn/, https://api.agnes-ai.cn/v1/chat/completions",
            "https://api.deepseek.com, https://api.deepseek.com/v1/chat/completions",
            "https://api.openai.com/v1, https://api.openai.com/v1/chat/completions",
            "https://api.openai.com/v1/, https://api.openai.com/v1/chat/completions"
    })
    void buildsChatCompletionsUrl(String baseUrl, String expected) {
        assertEquals(expected, OpenAiUrlUtils.chatCompletionsUrl(baseUrl));
    }

    @ParameterizedTest
    @CsvSource({
            "https://example.com, /v1/images/generations, https://example.com/v1/images/generations",
            "https://example.com, /v1/videos, https://example.com/v1/videos",
            "https://example.com, /v1/videos/video-123/content, https://example.com/v1/videos/video-123/content",
            "https://example.com, /v1/audio/speech, https://example.com/v1/audio/speech",
            "https://example.com/v1, /v1/images/generations, https://example.com/v1/images/generations",
            "https://example.com/v1, /v1/videos, https://example.com/v1/videos",
            "https://example.com/v1, /v1/videos/video-123/content, https://example.com/v1/videos/video-123/content",
            "https://example.com/v1, /v1/audio/speech, https://example.com/v1/audio/speech",
            "https://open.bigmodel.cn/api/paas/v4/, /v1/images/generations, https://open.bigmodel.cn/api/paas/v4/images/generations",
            "https://open.bigmodel.cn/api/paas/v4/, /v1/videos, https://open.bigmodel.cn/api/paas/v4/videos",
            "https://open.bigmodel.cn/api/paas/v4/, /v1/videos/video-123/content, https://open.bigmodel.cn/api/paas/v4/videos/video-123/content",
            "https://open.bigmodel.cn/api/paas/v4/, /v1/audio/speech, https://open.bigmodel.cn/api/paas/v4/audio/speech"
    })
    void buildsVersionAwareMediaEndpointUrl(String baseUrl, String path, String expected) {
        assertEquals(expected, OpenAiUrlUtils.endpointUrl(baseUrl, path));
    }

    @ParameterizedTest
    @CsvSource({
            "https://example.com, https://example.com/v1/images/edits",
            "https://example.com/v1, https://example.com/v1/images/edits",
            "https://open.bigmodel.cn/api/paas/v4/, https://open.bigmodel.cn/api/paas/v4/images/edits"
    })
    void buildsPassthroughClientPath(String baseUrl, String expected) {
        assertEquals(expected, OpenAiUrlUtils.endpointUrl(baseUrl, "/v1/images/edits"));
    }

    @ParameterizedTest
    @CsvSource({
            "https://example.com, https://example.com/agnesapi?video_id=123",
            "https://example.com/v1/, https://example.com/v1/agnesapi?video_id=123"
    })
    void preservesNonVersionedPaths(String baseUrl, String expected) {
        assertEquals(expected, OpenAiUrlUtils.endpointUrl(baseUrl, "/agnesapi?video_id=123"));
    }

    @ParameterizedTest
    @CsvSource({
            "https://api.example.com, https://api.example.com/v1/models",
            "https://api.example.com/v1, https://api.example.com/v1/models",
            "https://open.bigmodel.cn/api/paas/v4/, https://open.bigmodel.cn/api/paas/v4/models"
    })
    void buildsModelsUrl(String baseUrl, String expected) {
        assertEquals(expected, OpenAiUrlUtils.modelsUrl(baseUrl));
    }

    @ParameterizedTest
    @CsvSource({
            "https://generativelanguage.googleapis.com, https://generativelanguage.googleapis.com/v1beta/models",
            "https://generativelanguage.googleapis.com/v1beta/, https://generativelanguage.googleapis.com/v1beta/models",
            "https://generativelanguage.googleapis.com/v4/, https://generativelanguage.googleapis.com/v4/models"
    })
    void buildsGeminiModelsUrl(String baseUrl, String expected) {
        assertEquals(expected, OpenAiUrlUtils.modelsUrl(baseUrl, "v1beta"));
    }
}
