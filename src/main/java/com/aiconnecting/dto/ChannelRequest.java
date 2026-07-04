package com.aiconnecting.dto;

import lombok.Data;

@Data
public class ChannelRequest {
    private String name;
    private String type;
    private String baseUrl;
    private String apiKey;
    private String modelIds;
    private Integer status;
    private Integer priority;
    private Integer rateLimit;
}
