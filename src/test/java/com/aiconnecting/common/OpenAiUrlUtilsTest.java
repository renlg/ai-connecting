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
