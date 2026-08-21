package com.aiconnecting.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UpstreamErrorUtilsTest {

    @Test
    void recognizesExplicitJsonAndMalformedRequestErrors() {
        assertThat(UpstreamErrorUtils.isClientFixableUpstreamError(400,
                "{\"error\":{\"message\":\"Assistant tool call arguments must be valid JSON\"}}"))
                .isTrue();
        assertThat(UpstreamErrorUtils.isClientFixableUpstreamError(400,
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                        + "\"message\":\"JSON parsing failed for tools[0].input_schema\"}}"))
                .isTrue();
        assertThat(UpstreamErrorUtils.isClientFixableUpstreamError(400,
                "{\"error\":{\"status\":\"INVALID_ARGUMENT\","
                        + "\"message\":\"Request body malformed: invalid parameter format\"}}"))
                .isTrue();
        assertThat(UpstreamErrorUtils.isClientFixableUpstreamError(400,
                "400 - JSON parse error in tool call arguments"))
                .isTrue();
    }

    @Test
    void recognizesCombinedParameterAndValidationSemantics() {
        assertThat(UpstreamErrorUtils.isClientFixableUpstreamError(400,
                "{\"error\":{\"message\":\"Tool call parameter has an invalid format\"}}"))
                .isTrue();
        assertThat(UpstreamErrorUtils.isClientFixableUpstreamError(400,
                "{\"error\":{\"message\":\"Missing required argument: location\"}}"))
                .isTrue();
    }

    @Test
    void rejectsNonFourHundredAndUnrelatedFourHundredErrors() {
        assertThat(UpstreamErrorUtils.isClientFixableUpstreamError(500,
                "{\"error\":{\"message\":\"arguments must be valid JSON\"}}"))
                .isFalse();
        assertThat(UpstreamErrorUtils.isClientFixableUpstreamError(400,
                "{\"error\":{\"message\":\"Insufficient quota\"}}"))
                .isFalse();
        assertThat(UpstreamErrorUtils.isClientFixableUpstreamError(400,
                "{\"error\":{\"type\":\"invalid_request_error\","
                        + "\"message\":\"This model is unavailable\"}}"))
                .isFalse();
        assertThat(UpstreamErrorUtils.isClientFixableUpstreamError(
                new BusinessException(400, "本地参数错误", "Invalid parameter format")))
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
}
