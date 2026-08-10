package com.aiconnecting.service;

import com.aiconnecting.common.RedisDistributedLock;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.ModelConfig;
import com.aiconnecting.repository.ModelConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
    private final ModelConfigRepository modelConfigRepository;

    /** 分布式锁 key，防止多机重复执行 */
    private static final String LOCK_KEY = "job:channelProbe";
    /**
     * 锁过期时间 5 分钟：小于调度间隔 1 小时。RedisDistributedLock 续约 watchdog（每 TTL/3 续约一次）
     * 在探测批次仍在运行时持续延长锁有效期，TTL 不必再证明覆盖 MAX_CHANNELS_PER_RUN 全部探测的
     * 理论最坏耗时；进程崩溃时锁最迟 5 分钟后自然释放。
     */
    private static final long LOCK_TTL_SECONDS = 5 * 60L;
    /**
     * 单次运行最多探测的渠道数：单渠道最坏耗时 = 10s 连接超时 + 15s 读超时 = 25s（无 callTimeout，
     * 顺序探测）。超出本次批次的渠道仍处于熔断 OPEN 状态，会在下一轮调度（1 小时后）被探测。
     */
    private static final int MAX_CHANNELS_PER_RUN = 60;
    /**
     * 上次批次最后一个 ID 的共享游标。该值与任务锁使用相同的 APP_ENV 命名空间持久化在 Redis，
     * 因此任意实例接手或实例重启后都会从同一位置继续；Redis 未启用时仅用于单实例内存回退。
     */
    private static final String CURSOR_KEY = "job:channelProbe:cursor";

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

        List<Long> sortedIds = openIds.stream().sorted().toList();
        Long savedCursor = distributedLock.getLongValue(CURSOR_KEY);
        long cursor = savedCursor != null ? savedCursor : Long.MIN_VALUE;
        List<Long> batch = java.util.stream.Stream.concat(
                        sortedIds.stream().filter(id -> id > cursor),
                        sortedIds.stream().filter(id -> id <= cursor))
                .limit(MAX_CHANNELS_PER_RUN)
                .toList();
        // doProbe itself is guarded by LOCK_KEY, so this shared read/advance is serialized across instances.
        // Every finite set of continuously OPEN channels is therefore covered after at most
        // ceil(channelCount / MAX_CHANNELS_PER_RUN) successful rounds, regardless of which instance runs them.
        distributedLock.setLongValue(CURSOR_KEY, batch.get(batch.size() - 1));
        if (batch.size() < openIds.size()) {
            log.info("熔断 OPEN 渠道数 {} 超过单次探测上限 {}，本轮从轮转游标后探测 {} 个",
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
        boolean isOpenaiType = false;
        String base = channel.getBaseUrl().replaceAll("/+$", "");

        if ("gemini".equalsIgnoreCase(channel.getType())) {
            // Gemini 使用 v1beta/models 端点，密钥通过 query 参数传递，不使用 Authorization 头
            reqBuilder.url(base + "/v1beta/models?key=" + channel.getApiKey());
        } else if ("claude".equalsIgnoreCase(channel.getType()) || "anthropic".equalsIgnoreCase(channel.getType())) {
            reqBuilder.url(base + "/v1/models");
            reqBuilder.addHeader("x-api-key", channel.getApiKey());
            reqBuilder.addHeader("anthropic-version", "2023-06-01");
        } else {
            isOpenaiType = true;
            reqBuilder.url(base + "/v1/models");
            reqBuilder.addHeader("Authorization", "Bearer " + channel.getApiKey());
        }

        try (Response response = probeClient.newCall(reqBuilder.build()).execute()) {
            if (response.isSuccessful()) {
                log.info("渠道 {} 探测成功 (HTTP {})，关闭熔断器", channelId, response.code());
                healthTracker.unblockChannel(channelId);
            } else if (isOpenaiType && (response.code() == 404 || response.code() == 405)) {
                // 部分上游（如 Cloudflare Workers AI）不支持 GET /v1/models，不代表连通性异常，
                // 改用最小化的 POST chat/completions 请求做连通性探测
                log.info("渠道 {} GET /v1/models 返回 HTTP {}（端点不支持），改用 POST chat/completions 探测",
                        channelId, response.code());
                probeViaChatCompletions(channel, base);
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

    /**
     * 针对不支持 GET /v1/models 的 openai 兼容上游（如 Cloudflare Workers AI），
     * 用最小化的 chat/completions 请求探测连通性
     */
    private void probeViaChatCompletions(Channel channel, String base) {
        Long channelId = channel.getId();
        String modelName = resolveProbeModelName(channel);

        String jsonBody = String.format(
                "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":1}",
                modelName);
        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(base + "/chat/completions")
                .addHeader("Authorization", "Bearer " + channel.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = probeClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                log.info("渠道 {} probe via POST chat/completions (HTTP {})，关闭熔断器", channelId, response.code());
                healthTracker.unblockChannel(channelId);
            } else {
                String respBody = response.body() != null ? response.body().string() : "";
                String errorMsg = String.format("HTTP %d: %s", response.code(),
                        respBody.length() > 200 ? respBody.substring(0, 200) : respBody);
                log.warn("渠道 {} POST chat/completions 探测失败，仍处于熔断状态，等待下一轮探测: {}", channelId, errorMsg);
            }
        } catch (IOException e) {
            log.warn("渠道 {} POST chat/completions 探测连接失败，仍处于熔断状态，等待下一轮探测: {}", channelId, e.getMessage());
        }
    }

    /**
     * 解析渠道启用的第一个模型名称，用于连通性探测；解析失败时回退到 gpt-3.5-turbo
     */
    private String resolveProbeModelName(Channel channel) {
        if (channel.getModelIds() != null && !channel.getModelIds().isEmpty()) {
            for (String idStr : channel.getModelIds().split(",")) {
                idStr = idStr.trim();
                if (idStr.isEmpty()) {
                    continue;
                }
                try {
                    Long modelId = Long.parseLong(idStr);
                    ModelConfig model = modelConfigRepository.findById(modelId).orElse(null);
                    if (model != null && model.getName() != null && !model.getName().isEmpty()) {
                        return model.getName();
                    }
                } catch (NumberFormatException ignored) {
                    // modelIds 中存在非法 ID，跳过
                }
            }
        }
        return "gpt-3.5-turbo";
    }
}
