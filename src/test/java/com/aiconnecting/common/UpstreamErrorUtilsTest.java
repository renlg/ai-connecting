package com.aiconnecting.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UpstreamErrorUtilsTest {

    @Test
    void recognizesAnyNonEmptyFourHundredResponse() {
        assertThat(UpstreamErrorUtils.isClientFixableUpstreamError(400,
                "{\"error\":{\"message\":\"Argument not supported: size\"}}"))
                .isTrue();
        assertThat(UpstreamErrorUtils.isClientFixableUpstreamError(400,
                "{\"error\":{\"message\":\"Insufficient quota\"}}"))
                .isTrue();
        assertThat(UpstreamErrorUtils.isClientFixableUpstreamError(400, "bad request"))
                .isTrue();
        assertThat(UpstreamErrorUtils.isClientFixableUpstreamError(400, " ")).isFalse();
        assertThat(UpstreamErrorUtils.isClientFixableUpstreamError(400, null)).isFalse();
    }

    @Test
    void rejectsNonFourHundredAndLocalBusinessErrors() {
        assertThat(UpstreamErrorUtils.isClientFixableUpstreamError(500,
                "{\"error\":{\"message\":\"arguments must be valid JSON\"}}"))
                .isFalse();
        assertThat(UpstreamErrorUtils.isClientFixableUpstreamError(
                new BusinessException(400, "本地参数错误", "Invalid parameter format")))
                .isFalse();
    }

    @Test
    void recognizesAnyUpstreamBusinessExceptionWithCodeFourHundred() {
        assertThat(UpstreamErrorUtils.isClientFixableUpstreamError(
                BusinessException.upstream(400, "上游错误", null, null)))
                .isTrue();
        assertThat(UpstreamErrorUtils.isClientFixableUpstreamError(
                BusinessException.upstream(500, "上游错误", "bad gateway", null)))
                .isFalse();
    }

    @Test
    void extractsProtocolMessageAndFallsBackToPlainText() {
        assertThat(UpstreamErrorUtils.extractUpstreamMessage(
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                        + "\"message\":\"tools.0.input_schema is invalid\"}}"))
                .isEqualTo("tools.0.input_schema is invalid");
        assertThat(UpstreamErrorUtils.extractUpstreamMessage("malformed request body"))
                .isEqualTo("malformed request body");
    }

    @Test
    void exposesRealFourHundredMessageAndFallsBackWhenEmpty() {
        assertThat(UpstreamErrorUtils.clientFacingMessage(400,
                "{\"error\":{\"message\":\"Argument not supported: size\"}}"))
                .isEqualTo("Argument not supported: size");
        assertThat(UpstreamErrorUtils.clientFacingMessage(400, "bad request"))
                .isEqualTo("bad request");
        assertThat(UpstreamErrorUtils.clientFacingMessage(400, " "))
                .isEqualTo(SseUtils.GENERIC_UPSTREAM_ERROR_MESSAGE);
        assertThat(UpstreamErrorUtils.clientFacingMessage(500, "internal details"))
                .isEqualTo(SseUtils.GENERIC_UPSTREAM_ERROR_MESSAGE);
    }
}
