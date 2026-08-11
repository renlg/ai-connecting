package com.aiconnecting.common;

/**
 * 用户等级可见性判断：与 {@link com.aiconnecting.entity.Channel#getSupportedLevels()} 的语义一致，
 * 但模型/模型组的空值语义是"对所有等级开放"（渠道空值在 {@code ChannelService} 中会被规整为 "1,2,3,4,5"）
 */
public final class LevelUtils {

    private LevelUtils() {
    }

    /**
     * @param supportedLevels 逗号分隔的等级列表，例如 "1,2,3"；为空表示对所有等级开放
     * @param userLevel       用户等级；为 null 时视为不满足任何显式等级限制
     */
    public static boolean isAllowed(String supportedLevels, Integer userLevel) {
        if (supportedLevels == null || supportedLevels.isBlank()) {
            return true;
        }
        if (userLevel == null) {
            return false;
        }
        for (String part : supportedLevels.split(",")) {
            if (part.trim().equals(String.valueOf(userLevel))) {
                return true;
            }
        }
        return false;
    }
}
