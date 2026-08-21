package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgnesVideoV20RequestBodyTransformerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgnesVideoV20RequestBodyTransformer transformer =
            new AgnesVideoV20RequestBodyTransformer(objectMapper);

    @Test
    void toNumFrames_handlesSpecialValuesAlignmentAndCap() {
        assertThat(transformer.toNumFrames(5)).isEqualTo(121);
        assertThat(transformer.toNumFrames(10)).isEqualTo(241);
        assertThat(transformer.toNumFrames(18)).isEqualTo(441);
        assertThat(transformer.toNumFrames(1)).isEqualTo(25);
        assertThat(transformer.toNumFrames(6)).isEqualTo(145);
        assertThat(transformer.toNumFrames(60)).isEqualTo(441);
        assertThat((transformer.toNumFrames(7) - 1) % 8).isZero();
    }

    @Test
    void transform_convertsDurationAndStripsBothDurationFields() throws Exception {
        JsonNode result = transform("{\"duration\":5,\"seconds\":5,\"prompt\":\"cat\"}");

        assertThat(result.has("duration")).isFalse();
        assertThat(result.has("seconds")).isFalse();
        assertThat(result.path("num_frames").asInt()).isEqualTo(121);
        assertThat(result.path("frame_rate").asInt()).isEqualTo(24);
        assertThat(result.path("prompt").asText()).isEqualTo("cat");
    }

    @Test
    void transform_usesSecondsWhenDurationIsMissing() throws Exception {
        JsonNode result = transform("{\"seconds\":10}");

        assertThat(result.has("seconds")).isFalse();
        assertThat(result.path("num_frames").asInt()).isEqualTo(241);
        assertThat(result.path("frame_rate").asInt()).isEqualTo(24);
    }

    @Test
    void transform_rejectsDifferentDurationAndSeconds() {
        assertThatThrownBy(() -> transformer.transform(
                "agnes-video-v2.0", "{\"duration\":5,\"seconds\":10}"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(400);
                    assertThat(error.getMessage()).isEqualTo(
                            "duration 与 seconds 参数值不一致，请只传其一或保持相等");
                });
    }

    @Test
    void transform_treatsNullDurationsAsMissingAndStillDefaultsFrameRate() throws Exception {
        JsonNode result = transform("{\"duration\":null,\"seconds\":null}");

        assertThat(result.has("duration")).isFalse();
        assertThat(result.has("seconds")).isFalse();
        assertThat(result.has("num_frames")).isFalse();
        assertThat(result.path("frame_rate").asInt()).isEqualTo(24);
    }

    @Test
    void transform_defaultsFrameRateEvenWithoutDuration() throws Exception {
        JsonNode result = transform("{\"prompt\":\"cat\"}");

        assertThat(result.has("num_frames")).isFalse();
        assertThat(result.path("frame_rate").asInt()).isEqualTo(24);
    }

    @Test
    void transform_preservesExistingNumFramesAndFrameRate() throws Exception {
        JsonNode result = transform("{\"duration\":5,\"num_frames\":321,\"frame_rate\":30}");

        assertThat(result.has("duration")).isFalse();
        assertThat(result.path("num_frames").asInt()).isEqualTo(321);
        assertThat(result.path("frame_rate").asInt()).isEqualTo(30);
    }

    @Test
    void transform_reusesSharedPositiveIntegerValidationForSeconds() {
        assertThatThrownBy(() -> transformer.transform("agnes-video-v2.0", "{\"seconds\":0}"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(400);
                    assertThat(error.getMessage()).isEqualTo("seconds 参数必须是正整数秒数");
                });
    }

    private JsonNode transform(String body) throws Exception {
        return objectMapper.readTree(transformer.transform("agnes-video-v2.0", body));
    }
}
