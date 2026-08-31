package com.aiconnecting.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configurable upper bounds for relay calls and streams, expressed in milliseconds. */
@Component
@ConfigurationProperties(prefix = "app.relay-timeouts")
@Getter
@Setter
public class RelayTimeoutProperties {
    private long connectMs = 30_000L;
    private long readMs = 120_000L;
    private long writeMs = 30_000L;
    private long singleModelTextBudgetMs = 180_000L;
    private long modelGroupBudgetMs = 130_000L;
    private long passthroughReadMs = 300_000L;
    private long passthroughTextCallMs = 300_000L;
    private long passthroughMediaCallMs = 600_000L;
    private long sseMaxDurationMs = 1_800_000L;
    private long imageMs = 300_000L;
    private long videoCreateMs = 120_000L;
    private long videoSynchronousMs = 600_000L;
    private long audioMs = 300_000L;
}
