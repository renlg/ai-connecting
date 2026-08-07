package com.aiconnecting.service;

import com.aiconnecting.common.RedisDistributedLock;
import com.aiconnecting.entity.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 渠道探测定时任务
 * 每隔 1 小时探测处于熔断 OPEN 状态的渠道，如果恢复则手动关闭熔断器；
 * HALF_OPEN 渠道不由本任务探测，而是由真实流量触发探测（见 ChannelHealthTracker）。
 * 若渠道持续处于 OPEN 状态超过 2 小时，自动禁用。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChannelProbeTask {

    private final ChannelHealthTracker healthTracker;
    private final ChannelService channelService;
    private final RedisDistributedLock distributedLock;

    /** 分布式锁 key，防止多机重复执行 */
    private static final String LOCK_KEY = "job:channelProbe";
    /** 锁过期时间 30 分钟：小于调度间隔 1 小时，且可证明覆盖下面按 MAX_CHANNELS_PER_RUN 限流后的探测批次 */
    private static final long LOCK_TTL_SECONDS = 30 * 60L;
    /**
     * 单次运行最多探测的渠道数：单渠道最坏耗时 = 10s 连接超时 + 15s 读超时 = 25s（无 callTimeout，
     * 顺序探测）。60 × 25s = 1500s（25 分钟）< LOCK_TTL_SECONDS（30 分钟），留有余量，可证明覆盖。
     * 超出本次批次的渠道仍处于熔断 OPEN 状态，会在下一轮调度（1 小时后）被探测。
     */
    private static final int MAX_CHANNELS_PER_RUN = 60;

    private final OkHttpClient probeClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    /**
     * 每小时执行一次：探测所有被封禁的渠道
     */
    @Scheduled(fixedRate = 60 * 60 * 1000, initialDelay = 60 * 60 * 1000)
    public void probeBlockedChannels() {
        distributedLock.runIfLocked(LOCK_KEY, LOCK_TTL_SECONDS, this::doProbe);
    }

    /**
     * 实际探测逻辑
     */
    private void doProbe() {
        Set<Long> openIds = healthTracker.getCircuitOpenChannelIds();
        if (openIds.isEmpty()) {
            log.debug("没有处于熔断 OPEN 状态的渠道，跳过探测");
            return;
        }

        List<Long> batch = openIds.stream().limit(MAX_CHANNELS_PER_RUN).collect(Collectors.toList());
        if (batch.size() < openIds.size()) {
            log.info("熔断 OPEN 渠道数 {} 超过单次探测上限 {}，本轮仅探测前 {} 个，其余留待下一轮调度",
                    openIds.size(), MAX_CHANNELS_PER_RUN, batch.size());
        }
        log.info("开始探测 {} 个熔断 OPEN 状态的渠道: {}", batch.size(), batch);

        for (Long channelId : batch) {
            try {
                Channel channel = channelService.getById(channelId);
                if (channel.getStatus() == 0) {
                    log.info("渠道 {} 已被手动禁用，跳过探测", channelId);
                    continue;
                }
                if (healthTracker.isOpenTooLong(channelId)) {
                    healthTracker.autoDisableChannel(channelId);
                    continue;
                }
                probeChannel(channel);
            } catch (Exception e) {
                log.error("探测渠道 {} 时发生异常: {}", channelId, e.getMessage(), e);
            }
        }
    }

    /**
     * 探测单个渠道是否恢复
     */
    private void probeChannel(Channel channel) {
        Long channelId = channel.getId();
        log.info("探测渠道 {} ({})...", channelId, channel.getName());

        Request.Builder reqBuilder = new Request.Builder().get();

        if ("gemini".equalsIgnoreCase(channel.getType())) {
            // Gemini 使用 v1beta/models 端点，密钥通过 query 参数传递，不使用 Authorization 头
            String url = channel.getBaseUrl().replaceAll("/+$", "") + "/v1beta/models?key=" + channel.getApiKey();
            reqBuilder.url(url);
        } else if ("claude".equalsIgnoreCase(channel.getType()) || "anthropic".equalsIgnoreCase(channel.getType())) {
            String url = channel.getBaseUrl().replaceAll("/+$", "") + "/v1/models";
            reqBuilder.url(url);
            reqBuilder.addHeader("x-api-key", channel.getApiKey());
            reqBuilder.addHeader("anthropic-version", "2023-06-01");
        } else {
            String url = channel.getBaseUrl().replaceAll("/+$", "") + "/v1/models";
            reqBuilder.url(url);
            reqBuilder.addHeader("Authorization", "Bearer " + channel.getApiKey());
        }

        try (Response response = probeClient.newCall(reqBuilder.build()).execute()) {
            if (response.isSuccessful()) {
                log.info("渠道 {} 探测成功 (HTTP {})，关闭熔断器", channelId, response.code());
                healthTracker.unblockChannel(channelId);
            } else {
                String body = response.body() != null ? response.body().string() : "";
                String errorMsg = String.format("HTTP %d: %s", response.code(),
                        body.length() > 200 ? body.substring(0, 200) : body);
                log.warn("渠道 {} 探测失败，仍处于熔断状态，等待下一轮探测: {}", channelId, errorMsg);
            }
        } catch (IOException e) {
            log.warn("渠道 {} 探测连接失败，仍处于熔断状态，等待下一轮探测: {}", channelId, e.getMessage());
        }
    }
}
