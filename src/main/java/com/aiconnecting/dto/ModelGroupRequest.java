package com.aiconnecting.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 模型组创建/更新请求 DTO
 */
@Data
public class ModelGroupRequest {

    private String name;

    /** 模型类型: text/image/video/audio */
    private String type;

    /** 成员选择策略: round_robin/priority/random */
    private String strategy;

    /** 单次请求最多尝试的成员数（硬上限 8） */
    private Integer maxAttempts;

    private Boolean enabled;

    private Boolean adminOnly;

    private BigDecimal inputPrice;
    private BigDecimal outputPrice;
    private BigDecimal cachedPrice;

    private BigDecimal price1k;
    private BigDecimal price2k;
    private BigDecimal price4k;

    private BigDecimal price480p;
    private BigDecimal price720p;
    private BigDecimal price1080p;
    private BigDecimal videoPrice4k;

    private BigDecimal priceStandard;
    private BigDecimal priceHd;

    /** 成员列表，顺序即组内排序 */
    private List<MemberDto> members;

    @Data
    public static class MemberDto {
        private Long modelConfigId;
        private Integer weight;
    }
}
