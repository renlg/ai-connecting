package com.aiconnecting.dto;

import com.aiconnecting.entity.Channel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelResponse {
    private Long id;
    private String name;
    private String type;
    private String apiKey;       // masked: sk-abc...xyz
    private String baseUrl;
    private String modelIds;
    private String supportedLevels;
    private Integer status;
    private Integer priority;
    private Long usedQuota;
    private Integer rateLimit;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ChannelResponse fromChannel(Channel channel) {
        return ChannelResponse.builder()
            .id(channel.getId())
            .name(channel.getName())
            .type(channel.getType())
            .apiKey(maskApiKey(channel.getApiKey()))
            .baseUrl(channel.getBaseUrl())
            .modelIds(channel.getModelIds())
            .supportedLevels(channel.getSupportedLevels())
            .status(channel.getStatus())
            .priority(channel.getPriority())
            .usedQuota(channel.getUsedQuota())
            .rateLimit(channel.getRateLimit())
            .createdAt(channel.getCreatedAt())
            .updatedAt(channel.getUpdatedAt())
            .build();
    }

    private static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) return apiKey;
        return apiKey.substring(0, 6) + "..." + apiKey.substring(apiKey.length() - 3);
    }
}
