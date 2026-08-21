package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 将 agnes-video-v2.0 的 duration 参数转换为 Agnes 支持的帧数参数。 */
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
            if (!(body instanceof ObjectNode objectBody) || !objectBody.has("duration")) {
                return requestBodyJson;
            }

            JsonNode durationNode = objectBody.get("duration");
            if (durationNode == null || durationNode.isNull()) {
                objectBody.remove("duration");
                return objectMapper.writeValueAsString(objectBody);
            }
            if (!durationNode.isIntegralNumber() || !durationNode.canConvertToInt() || durationNode.asInt() <= 0) {
                throw new BusinessException(400, "duration 参数必须是正整数秒数",
                        "duration must be a positive integer number of seconds");
            }

            int durationSeconds = durationNode.asInt();
            objectBody.remove("duration");
            if (!objectBody.has("num_frames")) {
                objectBody.put("num_frames", toNumFrames(durationSeconds));
            }
            objectBody.put("frame_rate", FRAME_RATE);
            return objectMapper.writeValueAsString(objectBody);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(400, "视频请求 JSON 格式无效", "Invalid video request JSON", e);
        }
    }

    private int toNumFrames(int durationSeconds) {
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
