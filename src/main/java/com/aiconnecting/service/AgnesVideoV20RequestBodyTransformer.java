package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 将 agnes-video-v2.0 的 duration/seconds 参数转换为 Agnes 支持的帧数参数。 */
@Component
public class AgnesVideoV20RequestBodyTransformer implements RequestBodyTransformer {

    private static final String MODEL = "agnes-video-v2.0";
    private static final int FRAME_RATE = 24;
    private static final int MAX_NUM_FRAMES = 441;

    private final ObjectMapper objectMapper;

    public AgnesVideoV20RequestBodyTransformer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String model) {
        return MODEL.equals(model);
    }

    @Override
    public String transform(String model, String requestBodyJson) {
        try {
            JsonNode body = objectMapper.readTree(requestBodyJson);
            if (!(body instanceof ObjectNode objectBody)) {
                return requestBodyJson;
            }

            Integer durationField = RelaySupport.readPositiveIntSeconds(objectBody, "duration");
            Integer secondsField = RelaySupport.readPositiveIntSeconds(objectBody, "seconds");
            if (durationField != null && secondsField != null && !durationField.equals(secondsField)) {
                throw new BusinessException(400, "duration 与 seconds 参数值不一致，请只传其一或保持相等",
                        "duration and seconds differ; provide only one or make them equal");
            }

            objectBody.remove("duration");
            objectBody.remove("seconds");
            Integer durationSeconds = durationField != null ? durationField : secondsField;
            if (durationSeconds != null && !objectBody.has("num_frames")) {
                objectBody.put("num_frames", toNumFrames(durationSeconds));
            }
            if (!objectBody.has("frame_rate")) {
                objectBody.put("frame_rate", FRAME_RATE);
            }
            return objectMapper.writeValueAsString(objectBody);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(400, "视频请求 JSON 格式无效", "Invalid video request JSON", e);
        }
    }

    int toNumFrames(int durationSeconds) {
        if (durationSeconds == 5) {
            return 121;
        }
        if (durationSeconds == 10) {
            return 241;
        }
        if (durationSeconds == 18) {
            return 441;
        }

        long requestedFrames = Math.min((long) durationSeconds * FRAME_RATE, MAX_NUM_FRAMES);
        long nearestStep = Math.round((requestedFrames - 1) / 8.0d);
        return (int) Math.min(nearestStep * 8 + 1, MAX_NUM_FRAMES);
    }
}
