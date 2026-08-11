package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.entity.ModelGroup;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 模型组计费：使用模型组自身价格计算积分消耗，与 {@link UsageLogService} 中单模型的
 * "每百万 token 积分比例" 计费公式口径一致（模型组文本同样按 "每百万 token 单价" 计费）。
 * 图片/视频/音频沿用与 {@link UsageLogService} 相同的分辨率/音质档位识别逻辑（复用其静态方法），
 * 仅计价所用的单价来源换成模型组价格。
 */
@Service
public class ModelGroupBillingService {

    private static final BigDecimal PER_MILLION = new BigDecimal("1000000");

    /**
     * 文本积分消耗 = (输入token - 缓存token)/1000000 × input_price + 缓存token/1000000 × cached_price
     *              + 输出token/1000000 × output_price
     */
    public BigDecimal calculateTextCreditCost(ModelGroup group, int promptTokens, int completionTokens, int cachedTokens) {
        if (promptTokens == 0 && completionTokens == 0) return BigDecimal.ZERO;
        BigDecimal inputPrice = nz(group.getInputPrice());
        BigDecimal outputPrice = nz(group.getOutputPrice());
        BigDecimal cachedPrice = nz(group.getCachedPrice());
        int clampedCachedTokens = Math.min(cachedTokens, promptTokens);
        int effectivePromptTokens = promptTokens - clampedCachedTokens;
        BigDecimal inputCost = BigDecimal.valueOf(effectivePromptTokens).divide(PER_MILLION, 10, RoundingMode.HALF_UP).multiply(inputPrice);
        BigDecimal cacheCost = BigDecimal.valueOf(clampedCachedTokens).divide(PER_MILLION, 10, RoundingMode.HALF_UP).multiply(cachedPrice);
        BigDecimal outputCost = BigDecimal.valueOf(completionTokens).divide(PER_MILLION, 10, RoundingMode.HALF_UP).multiply(outputPrice);
        return inputCost.add(cacheCost).add(outputCost);
    }

    public BigDecimal resolveImageUnitPrice(ModelGroup group, String size) {
        String tier = UsageLogService.resolveImageTier(
                size == null || size.isBlank() ? UsageLogService.DEFAULT_IMAGE_SIZE : size);
        if (tier == null) {
            throw new BusinessException(400, "不支持的图片分辨率: " + size + "，请使用如 1024x1024 的宽x高格式");
        }
        BigDecimal price = switch (tier) {
            case "2K" -> group.getPrice2k();
            case "4K" -> group.getPrice4k();
            default -> group.getPrice1k();
        };
        return nz(price);
    }

    public BigDecimal calculateImageCreditCost(ModelGroup group, String size, int n) {
        return resolveImageUnitPrice(group, size).multiply(BigDecimal.valueOf(Math.max(n, 1)));
    }

    public BigDecimal resolveVideoUnitPrice(ModelGroup group, String size) {
        String tier = UsageLogService.resolveVideoTier(size);
        if (tier == null) {
            throw new BusinessException(400, "视频请求缺少或包含不支持的分辨率参数 (size/resolution): " + size
                    + "，支持 480p/720p/1080p/4k 或宽x高格式");
        }
        BigDecimal price = switch (tier) {
            case "720P" -> group.getPrice720p();
            case "1080P" -> group.getPrice1080p();
            case "4K" -> group.getVideoPrice4k();
            default -> group.getPrice480p();
        };
        return nz(price);
    }

    public BigDecimal calculateVideoCreditCost(ModelGroup group, String size, int durationSeconds) {
        if (durationSeconds <= 0) {
            throw new BusinessException(400, "视频请求缺少有效的时长参数 (duration/seconds)，需为正整数秒数");
        }
        return resolveVideoUnitPrice(group, size).multiply(BigDecimal.valueOf(durationSeconds));
    }

    public BigDecimal calculateAudioCreditCost(ModelGroup group, String quality, int durationSeconds) {
        String tier = UsageLogService.resolveAudioTier(quality);
        if (tier == null) {
            throw new BusinessException(400, "不支持的音频音质参数 (quality): " + quality + "，支持 standard/hd");
        }
        if (durationSeconds <= 0) {
            throw new BusinessException(400, "音频请求缺少有效的时长参数 (duration/seconds)，需为正整数秒数");
        }
        BigDecimal price = "HD".equals(tier) ? group.getPriceHd() : group.getPriceStandard();
        return nz(price).multiply(BigDecimal.valueOf(durationSeconds));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
