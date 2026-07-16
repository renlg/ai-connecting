package com.aiconnecting.service;

import com.aiconnecting.entity.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;

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

    @Autowired(required = false)
    private RedisTemplate<String, Long> redisTemplate;

    /** 分布式锁 key，防止多机重复执行 */
    private static final String LOCK_KEY = "lock:channel_probe";
    /** 锁过期时间 30 分钟（小于定小时间隔 1 小时） */
    private static final long LOCK_EXPIRE_MS = 30 * 60 * 1000L;

    private final OkHttpClient probeClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    /**
     * 每小时执行一次：探测所有被封禁的渠道
     */
    @Scheduled(fixedRate = 60 * 60 * 1000, initialDelay = 60 * 60 * 1000)
    public void probeBlockedChannels() {
        if (!tryLock()) {
            log.debug("未获取到分布式锁，跳过本次探测（可能其他实例正在执行）");
            return;
        }
        try {
            doProbe();
        } finally {
            releaseLock();
        }
    }

    /**
     * 尝试获取 Redis 分布式锁
     */
    private boolean tryLock() {
        if (redisTemplate == null) {
            // Redis 不可用，单机模式直接放行
            return true;
        }
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(LOCK_KEY, 1L, LOCK_EXPIRE_MS, TimeUnit.MILLISECONDS);
            if (Boolean.TRUE.equals(acquired)) {
                log.info("获取探测分布式锁成功");
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("获取分布式锁异常，降级放行: {}", e.getMessage());
            return true; // Redis 异常时降级放行
        }
    }

    /**
     * 释放分布式锁
     */
    private void releaseLock() {
        if (redisTemplate == null) return;
        try {
            redisTemplate.delete(LOCK_KEY);
            log.debug("释放探测分布式锁");
        } catch (Exception e) {
            log.warn("释放分布式锁异常: {}", e.getMessage());
        }
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

        log.info("开始探测 {} 个熔断 OPEN 状态的渠道: {}", openIds.size(), openIds);

        for (Long channelId : openIds) {
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
