package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 将 agnes-video-2.5-flash 的时长参数转换为 Agnes 要求的字符串 seconds。 */
@Component
public class AgnesVideo25FlashRequestBodyTransformer implements RequestBodyTransformer {

    private static final String MODEL = "agnes-video-2.5-flash";
    private static final int MIN_DURATION_SECONDS = 4;
    private static final int MAX_DURATION_SECONDS = 12;

    private final ObjectMapper objectMapper;

    public AgnesVideo25FlashRequestBodyTransformer(ObjectMapper objectMapper) {
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

            Integer durationSeconds = RelaySupport.readDurationSeconds(objectBody);

            objectBody.remove("duration");
            objectBody.remove("seconds");
            objectBody.remove("num_frames");
            objectBody.remove("frame_rate");
            objectBody.remove("width");
            objectBody.remove("height");

            if (durationSeconds != null) {
                if (durationSeconds < MIN_DURATION_SECONDS || durationSeconds > MAX_DURATION_SECONDS) {
                    throw new BusinessException(400,
                            "agnes-video-2.5-flash 仅支持 4-12 秒的视频",
                            "agnes-video-2.5-flash only supports video durations from 4 to 12 seconds");
                }
                objectBody.put("seconds", String.valueOf(durationSeconds));
            }

            return objectMapper.writeValueAsString(objectBody);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(400, "视频请求 JSON 格式无效", "Invalid video request JSON", e);
        }
    }
}
