package com.aiconnecting.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 模型组成员：模型组与其绑定的成员模型（{@link ModelConfig}）的多对多关联行，
 * 携带该成员在组内的权重与展示顺序
 */
@Entity
@Table(name = "model_group_members", indexes = {
        @Index(name = "idx_model_group_members_group_id", columnList = "groupId"),
        @Index(name = "idx_model_group_members_model_config_id", columnList = "modelConfigId")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModelGroupMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属模型组 ID */
    @Column(nullable = false)
    private Long groupId;

    /** 成员模型配置 ID (model_configs.id) */
    @Column(nullable = false)
    private Long modelConfigId;

    /** 权重，用于 round_robin/random 策略下的加权选择 */
    @Builder.Default
    @Column(nullable = false)
    private Integer weight = 1;

    /** 组内排序，用于 priority 策略与前端展示顺序 */
    @Builder.Default
    @Column(nullable = false)
    private Integer sortOrder = 0;

    @PrePersist
    protected void onCreate() {
        if (weight == null) weight = 1;
        if (sortOrder == null) sortOrder = 0;
    }
}
