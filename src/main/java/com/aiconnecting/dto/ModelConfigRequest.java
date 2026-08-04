package com.aiconnecting.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 模型配置请求 DTO
 */
@Data
public class ModelConfigRequest {

    private String name;

    private String displayName;

    private String description;

    /** 输入积分兑换比例 (每百万token 消耗多少积分) */
    private Integer inputCreditRate;

    /** 输出积分兑换比例 (每百万token 消耗多少积分) */
    private Integer outputCreditRate;

    private Boolean adminOnly;

    private Integer status;

    /** 缓存 token 积分兑换比例 (每百万token 消耗多少积分) */
    private BigDecimal cacheCreditRate;

    /** 模型类型: text=文本, image=图片, video=视频, audio=音频 */
    private String type;

    /** 图片 1K 档价格 (积分/张) */
    private BigDecimal imagePrice1k;

    /** 图片 2K 档价格 (积分/张) */
    private BigDecimal imagePrice2k;

    /** 图片 4K 档价格 (积分/张) */
    private BigDecimal imagePrice4k;

    /** 视频 480P 档价格 (积分/秒) */
    private BigDecimal videoPrice480p;

    /** 视频 720P 档价格 (积分/秒) */
    private BigDecimal videoPrice720p;

    /** 视频 1080P 档价格 (积分/秒) */
    private BigDecimal videoPrice1080p;

    /** 视频 4K 档价格 (积分/秒) */
    private BigDecimal videoPrice4k;

    /** 音频标准档价格 (积分/秒) */
    private BigDecimal audioPriceStandard;

    /** 音频高清档价格 (积分/秒) */
    private BigDecimal audioPriceHd;
}
