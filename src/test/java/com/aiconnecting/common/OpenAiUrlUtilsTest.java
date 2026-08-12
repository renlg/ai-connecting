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
}
