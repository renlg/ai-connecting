package com.aiconnecting.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "channel_failure_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChannelFailureRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long channelId;

    @Column(length = 100)
    private String channelName;

    @Column(length = 200)
    private String modelName;

    @Column(length = 50)
    private String errorCode;

    @Column(length = 500)
    private String errorMessage;

    @Column(nullable = false)
    @Builder.Default
    private Boolean analyzed = false;

    @Column(nullable = false, updatable = false)
    private Long createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = System.currentTimeMillis();
        if (analyzed == null) analyzed = false;
    }
}
