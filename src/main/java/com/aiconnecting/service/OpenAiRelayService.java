package com.aiconnecting.service;

import com.aiconnecting.common.AudioDurationUtil;
import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.MediaDurationLimits;
import com.aiconnecting.common.ProtocolConverter;
import com.aiconnecting.common.RedisDistributedLock;
import com.aiconnecting.common.SseUtils;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.Token;
import com.aiconnecting.entity.VideoTask;
import com.aiconnecting.repository.VideoTaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI 兼容协议的中转服务
 * 处理 OpenAI / DeepSeek / Qwen 等 OpenAI 兼容协议的请求转发和 SSE 流式处理
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OpenAiRelayService {

    private final RelaySupport support;
    private final VideoTaskRepository videoTaskRepository;
    private final ChannelService channelService;
    private final UsageLogService usageLogService;
    private final VideoTaskSettlementService videoTaskSettlementService;
    private final VideoTaskUsageLogService videoTaskUsageLogService;
    private final OssMediaStorageService ossMediaStorageService;

    // 字段注入而非通过 @RequiredArgsConstructor 加入构造函数参数列表，
    // 以保持现有构造函数签名不变（OpenAiRelayServiceTest 直接以固定参数个数 new 出该类，不经过 Spring 容器）
    @Autowired
    private RedisDistributedLock distributedLock;

    // 同上：模型组故障转移组（fallback_group_id）为后续新增能力，字段注入避免改动构造函数签名
    @Autowired(required = false)
    private ModelGroupFailoverExecutor modelGroupFailoverExecutor;

    /**
     * 中转请求 (非流式) - 最多重试 3 次，每次选择不同渠道
     */
    public String relayRequest(String tokenKey, String path, String requestBody,
                               String model, HttpServletRequest httpRequest) {
        RelaySupport.RelayContext ctx = support.validateAndPrepare(tokenKey, model);
        try {
            return relayRequestSingleModel(ctx, path, requestBody, model, httpRequest);
        } catch (BusinessException e) {
            if (modelGroupFailoverExecutor != null && ctx.modelConfig() != null && ctx.modelConfig().getFallbackGroupId() != null
                    && FailureClassifier.isSwitchable(e)) {
                var fallbackGroup = modelGroupFailoverExecutor.resolveFallbackGroup(
                        ctx.modelConfig().getFallbackGroupId(), "text", "admin".equals(ctx.user().getRole()));
                if (fallbackGroup.isPresent()) {
                    log.warn("模型 {} 请求失败，转入故障转移组 {} 继续尝试: {}", model, fallbackGroup.get().getName(), e.getMessage());
                    return modelGroupFailoverExecutor.relayRequestWithContext(ctx, fallbackGroup.get(), path, requestBody, httpRequest);
                }
            }
            throw e;
        }
    }

    private String relayRequestSingleModel(RelaySupport.RelayContext ctx, String path, String requestBody,
                                           String model, HttpServletRequest httpRequest) {
        requestBody = support.normalizeContentForUpstream(requestBody);
        Set<Long> triedChannels = new HashSet<>();
        Long modelConfigId = ctx.modelConfig() != null ? ctx.modelConfig().getId() : null;
        long startTime = System.currentTimeMillis();
        String lastError = null;
        BusinessException lastFailure = null;
        int attempt = 0;

        while (attempt < RelaySupport.MAX_RETRIES) {
            Channel channel;
            try {
                channel = support.channelRouter.selectChannel(ctx.channelModelId(), triedChannels, ctx.userLevel());
            } catch (BusinessException e) {
                if (lastError != null) {
                    throw lastFailure != null
                            ? wrapFailure(lastFailure, "所有渠道均不可用，最后错误: " + lastError)
                            : new BusinessException(502, "所有渠道均不可用，最后错误: " + lastError);
                }
                throw e;
            }
            triedChannels.add(channel.getId());

            if (support.isChannelRateLimited(channel)) {
                lastError = "渠道 " + channel.getId() + " 请求频率超限";
                log.warn("跳过限流渠道 {}: {}", channel.getId(), lastError);
                continue;
            }

            attempt++;
            try {
                String response;
                if (support.isGeminiTypeChannel(channel)) {
                    String geminiBody = ProtocolConverter.convertOpenAiToGeminiRequest(requestBody);
                    response = support.forwardGeminiRequest(channel, geminiBody);
                    response = ProtocolConverter.convertGeminiToOpenAiResponse(response);
                } else {
                    response = support.forwardRequest(channel, path, requestBody);
                }
                long duration = System.currentTimeMillis() - startTime;
                support.recordUsage(ctx.token(), channel, model, response, duration, httpRequest, path);
                support.channelHealthTracker.recordSuccess(channel.getId());
                return response;
            } catch (BusinessException e) {
                lastFailure = e;
                lastError = e.getMessage();
                log.error("渠道 {} 请求失败 (尝试 {}/{}): {}", channel.getId(), attempt, RelaySupport.MAX_RETRIES, e.getMessage());
                support.dispatchRelayFailure(channel.getId(), modelConfigId, e, () ->
                        support.channelHealthTracker.recordFailure(channel.getId(),
                                ChannelHealthTracker.ErrorCategory.fromStatusCode(e.getCode()), e.getMessage()));
                if (attempt == RelaySupport.MAX_RETRIES) {
                    throw wrapFailure(e, "所有渠道均不可用，最后错误: " + lastError);
                }
            }
        }
        throw new BusinessException(502, "所有渠道均不可用，最后错误: " + lastError);
    }

    /**
     * 图片/视频生成中转 (非流式)：价格在请求前完全可知，先原子校验余额并预扣积分，再转发上游；
     * 上游失败时退回预扣积分。视频响应使用可轮询的 video_id，并持久化 video_id→渠道映射，
     * 供 GET /v1/videos/{id} 通过中转向原始渠道轮询任务状态（不向客户端暴露渠道凭据）。
     *
     * @param mediaType image 或 video，入口处强制模型类型与端点匹配
     */
    public String relayMediaRequest(String tokenKey, String path, String requestBody,
                                    String model, HttpServletRequest httpRequest, String mediaType) {
        RelaySupport.RelayContext ctx = support.validateAndPrepare(tokenKey, model, mediaType);
        boolean isVideo = "video".equals(mediaType);
        RelaySupport.VideoChargeInfo videoChargeInfo = isVideo ? support.prepareVideoCharge(ctx, requestBody) : null;
        RelaySupport.MediaCharge charge = isVideo ? videoChargeInfo.charge() : support.prepareMediaCharge(ctx, requestBody);

        long startTime = System.currentTimeMillis();
        ChannelResult<String> result;
        try {
            result = forwardWithRetry(ctx, channel -> {
                String upstreamBody = "image".equals(mediaType)
                        ? support.prepareImageRequestBody(channel, requestBody)
                        : requestBody;
                return support.forwardRequest(channel, path, upstreamBody);
            });
        } catch (RuntimeException e) {
            // 只有上游/渠道级别的失败（BusinessException 且分类为 SWITCH）才转入故障转移组；
            // 本地校验错误或非预期的运行时异常直接向上抛出，不掩盖真实错误
            if (modelGroupFailoverExecutor != null && ctx.modelConfig() != null && ctx.modelConfig().getFallbackGroupId() != null
                    && e instanceof BusinessException be && FailureClassifier.isSwitchable(be)) {
                var fallbackGroup = modelGroupFailoverExecutor.resolveFallbackGroup(ctx.modelConfig().getFallbackGroupId(),
                        mediaType, "admin".equals(ctx.user().getRole()));
                if (fallbackGroup.isPresent()) {
                    requireMediaRefundBeforeFallback(ctx, charge);
                    log.warn("模型 {} 媒体请求失败，转入故障转移组 {} 继续尝试", model, fallbackGroup.get().getName());
                    return isVideo
                            ? modelGroupFailoverExecutor.relayVideoRequest(tokenKey, path, requestBody, fallbackGroup.get().getName(), httpRequest)
                            : modelGroupFailoverExecutor.relayImageRequest(tokenKey, path, requestBody, fallbackGroup.get().getName(), httpRequest);
                }
            }
            throw e;
        }

        String response = result.value();
        if (isVideo) {
            long duration = System.currentTimeMillis() - startTime;
            Long videoTaskId;
            try {
                VideoResponseResult videoResult = handleVideoResponse(response, result.channel(), ctx.token(),
                        model, videoChargeInfo, charge, duration, httpRequest, path);
                response = videoResult.response();
                videoTaskId = videoResult.taskId();
                // 同步终态创建响应已在 handleVideoResponse 内部按 正常日志→结算→OSS 的顺序处理完毕，
                // 此处仅需补记仍在处理中（未同步到终态）任务尚未写入的正常使用日志
                if (videoTaskId != null && !videoResult.usageLogRecorded()) {
                    // 使用日志落库与 usage_log_id 回链在同一个事务内完成（见 RelaySupport），
                    // 任一环节失败都不写入孤立记录；失败不影响响应——任务与预扣积分已落地，仅本次日志缺失，
                    // 结算阶段会因 usageLogId 为空跳过金额回写（安全，不影响余额正确性）
                    try {
                        support.recordPrepaidMediaUsageAndLink(ctx.token(), result.channel(), model, charge, duration, httpRequest, path, videoTaskId);
                    } catch (Exception e) {
                        log.error("视频使用日志写入/关联失败（积分已扣减，保持扣减，不影响响应）: model={}, cost={}",
                                model, charge.cost(), e);
                    }
                }
            } catch (RuntimeException e) {
                // 响应无效或任务映射保存失败时客户端拿到的 id 将无法轮询，
                // 退回预扣并报错，不返回不可轮询的任务结果；退款状态如实反馈给客户端
                boolean refunded = support.refundMediaCharge(ctx, charge);
                String suffix = refunded ? "，预扣积分已退回" : "，退回预扣积分失败，已记录待人工补偿";
                if (e instanceof BusinessException be) {
                    throw new BusinessException(be.getCode(), be.getMessage() + suffix);
                }
                throw e;
            }
            return response;
        }
        if ("image".equals(mediaType)) {
            int actualCount = countReturnedImages(response);
            BigDecimal finalCost = support.settleImageCharge(ctx, charge, requestBody, actualCount);
            charge = new RelaySupport.MediaCharge(finalCost, charge.deducted());
            // 计费按上游原始 data 数组长度结算（已在上一行完成），转存 OSS 仅替换地址字段，不影响计费
            response = ossMediaStorageService.rewriteImageResponse(response);
        }
        long duration = System.currentTimeMillis() - startTime;
        recordPrepaidUsageSafely(ctx.token(), result.channel(), model, charge, duration, httpRequest, path);
        return response;
    }

    /**
     * 统计图片响应中实际返回的图片数量（data 数组长度）；响应缺少 data 数组或解析失败按 0 张计（全额退回）
     */
    private int countReturnedImages(String response) {
        try {
            JsonNode json = support.objectMapper.readTree(response);
            JsonNode data = json.get("data");
            return data != null && data.isArray() ? data.size() : 0;
        } catch (Exception e) {
            log.warn("图片响应解析失败，按 0 张返回结算（全额退回预扣积分）: {}", e.getMessage());
            return 0;
        }
    }

    /** 上游成功转发的结果及所用渠道 */
    private record ChannelResult<T>(T value, Channel channel) {}

    /**
     * 渠道选择 + 重试的通用转发骨架：仅上游调用本身参与重试，
     * 成功后的本地处理（计费落库、任务映射等）由调用方在循环外执行，避免本地失败触发重复生成
     */
    private <T> ChannelResult<T> forwardWithRetry(RelaySupport.RelayContext ctx,
                                                  java.util.function.Function<Channel, T> call) {
        Set<Long> triedChannels = new HashSet<>();
        Long modelConfigId = ctx.modelConfig() != null ? ctx.modelConfig().getId() : null;
        String lastError = null;
        BusinessException lastFailure = null;
        int attempt = 0;

        while (attempt < RelaySupport.MAX_RETRIES) {
            Channel channel;
            try {
                channel = support.channelRouter.selectChannel(ctx.channelModelId(), triedChannels, ctx.userLevel());
            } catch (BusinessException e) {
                if (lastError != null) {
                    throw lastFailure != null
                            ? wrapFailure(lastFailure, "所有渠道均不可用，最后错误: " + lastError)
                            : new BusinessException(502, "所有渠道均不可用，最后错误: " + lastError);
                }
                throw e;
            }
            triedChannels.add(channel.getId());

            if (support.isChannelRateLimited(channel)) {
                lastError = "渠道 " + channel.getId() + " 请求频率超限";
                log.warn("跳过限流渠道 {}: {}", channel.getId(), lastError);
                continue;
            }

            attempt++;
            try {
                T value = call.apply(channel);
                support.channelHealthTracker.recordSuccess(channel.getId());
                return new ChannelResult<>(value, channel);
            } catch (BusinessException e) {
                lastFailure = e;
                lastError = e.getMessage();
                log.error("渠道 {} 媒体请求失败 (尝试 {}/{}): {}", channel.getId(), attempt, RelaySupport.MAX_RETRIES, e.getMessage());
                support.dispatchRelayFailure(channel.getId(), modelConfigId, e, () ->
                        support.channelHealthTracker.recordFailure(channel.getId(),
                                ChannelHealthTracker.ErrorCategory.fromStatusCode(e.getCode()), e.getMessage()));
                if (attempt == RelaySupport.MAX_RETRIES) {
                    throw wrapFailure(e, "所有渠道均不可用，最后错误: " + lastError);
                }
            }
        }
        throw new BusinessException(502, "所有渠道均不可用，最后错误: " + lastError);
    }

    private BusinessException wrapFailure(BusinessException cause, String message) {
        return new BusinessException(cause.getCode(), message, cause, cause.getUpstreamResponseBody(),
                cause.getRetryAfterSeconds(), cause.isUpstreamResponse());
    }

    /**
     * 记录已预扣积分的媒体使用日志；上游已成功，日志落库失败不回退积分也不影响响应（仅记录错误）
     *
     * @return 落库后的使用日志 id，失败时返回 null
     */
    private Long recordPrepaidUsageSafely(Token token, Channel channel, String model,
                                          RelaySupport.MediaCharge charge, long duration,
                                          HttpServletRequest httpRequest, String path) {
        try {
            return support.recordPrepaidMediaUsage(token, channel, model, charge, duration, httpRequest, path);
        } catch (Exception e) {
            log.error("媒体使用日志写入失败（积分已扣减，保持扣减，不影响响应）: model={}, cost={}",
                    model, charge.cost(), e);
            return null;
        }
    }

    /** 视频任务映射保存结果：透传给客户端的响应体，及新建任务的本地 id（供关联预扣使用日志） */
    /** @param usageLogRecorded true 表示同步终态路径已在本方法内完成正常使用日志的写入，调用方无需再补记 */
    private record VideoResponseResult(String response, Long taskId, boolean usageLogRecorded) {}

    /**
     * 持久化上游视频任务 id 与渠道的映射，供轮询接口回查；优先使用 Agnes 创建响应中的
     * video_id，并将响应 id 规范化为该值，使兼容 OpenAI 的客户端后续轮询正确的标识。
     * 响应非法 JSON、缺少可用 id 或映射保存失败均抛错（由调用方退回预扣积分并如实反馈退款状态），
     * 绝不向已付费的客户端返回无法轮询的任务结果
     */
    private VideoResponseResult handleVideoResponse(String response, Channel channel, Token token,
                                                    String model, RelaySupport.VideoChargeInfo videoChargeInfo,
                                                    RelaySupport.MediaCharge charge, long duration,
                                                    HttpServletRequest httpRequest, String path) {
        JsonNode json;
        try {
            json = support.objectMapper.readTree(response);
        } catch (Exception e) {
            log.error("视频上游响应不是合法 JSON，无法建立任务轮询映射");
            throw new BusinessException(502, "上游视频响应无效（非 JSON）");
        }
        // readTree 对空白/空字符串输入不抛异常而是直接返回 null，必须显式判空，
        // 否则下一行 json.isObject() 将抛 NPE，被外层当作普通 RuntimeException 处理，
        // 丢失"上游响应无效"这一明确的业务错误信息
        if (json == null) {
            log.error("视频上游响应为空，无法建立任务轮询映射");
            throw new BusinessException(502, "上游视频响应无效（空响应）");
        }
        String upstreamVideoId = json.isObject() ? firstText(json.get("video_id"), json.get("id")) : null;
        if (upstreamVideoId == null) {
            log.error("视频上游响应缺少有效的 video_id/id 字段，无法建立任务轮询映射");
            throw new BusinessException(502, "上游视频响应缺少有效的任务 id");
        }
        Long taskId;
        VideoTask saved;
        try {
            saved = videoTaskRepository.save(VideoTask.builder()
                    .upstreamId(upstreamVideoId)
                    .channelId(channel.getId())
                    .tokenId(token.getId())
                    .userId(token.getUserId())
                    .model(model)
                    .prepaidCost(videoChargeInfo.charge().cost())
                    .deducted(videoChargeInfo.charge().deducted())
                    .size(videoChargeInfo.size())
                    .durationSeconds(videoChargeInfo.durationSeconds())
                    .unitPrice(videoChargeInfo.unitPrice())
                    .settled(false)
                    .build());
            taskId = saved.getId();
        } catch (Exception e) {
            log.error("视频任务映射保存失败: upstreamId={}", upstreamVideoId, e);
            throw new BusinessException(500, "视频任务保存失败，请重试");
        }
        com.fasterxml.jackson.databind.node.ObjectNode responseObject =
                (com.fasterxml.jackson.databind.node.ObjectNode) json;
        responseObject.put("id", upstreamVideoId);
        responseObject.put("channel_id", channel.getId());

        // 极少数上游会同步返回已完成/失败终态并直接携带播放地址：此时必须按 正常使用日志 → 结算 → OSS 转存
        // 的顺序处理——先写入携带 IP/耗时的正常使用日志并回链，避免结算内部的 ensureLinked() 抢先创建
        // 缺少这些字段的降级日志，导致随后正常写入因 usage_log_id 已被占用而判重复回滚，只留下降级日志
        boolean usageLogRecorded = false;
        OssMediaStorageService.VideoTaskStatus resolved = ossMediaStorageService.extractVideoStatus(json);
        boolean synchronousTerminal = resolved != null && isTerminalStatus(resolved.status());
        if (synchronousTerminal) {
            try {
                support.recordPrepaidMediaUsageAndLink(token, channel, model, charge, duration, httpRequest, path, taskId);
                usageLogRecorded = true;
            } catch (Exception e) {
                log.error("视频任务同步终态使用日志写入失败（积分已扣减，保持扣减，不影响响应）: model={}, cost={}",
                        model, charge.cost(), e);
            }
        }
        try {
            // 结算必须先于 OSS 转存完成（否则预扣积分永远不会被对账，因为该任务此后再也不会被轮询到）；
            // 结算失败时不得转存 OSS / 缓存任何地址，直接返回上游原始响应
            boolean settlementOk = settleVideoTaskIfTerminal(saved, json);
            if (!settlementOk) {
                log.error("视频任务同步终态结算失败，跳过 OSS 转存，返回上游原始响应: taskId={}, upstreamId={}",
                        taskId, upstreamVideoId);
                return new VideoResponseResult(response, taskId, usageLogRecorded);
            }
            String normalized = support.objectMapper.writeValueAsString(json);
            normalized = ossMediaStorageService.rewriteVideoResponseIfCompleted(normalized, null,
                    ossUrl -> videoTaskRepository.updateOssUrl(taskId, ossUrl));
            return new VideoResponseResult(normalized, taskId, usageLogRecorded);
        } catch (Exception e) {
            return new VideoResponseResult(response, taskId, usageLogRecorded);
        }
    }

    /**
     * 视频任务状态查询：按上游任务 id 找到当初处理该任务的渠道，
     * 用渠道自身凭据向上游转发查询并透传结果；仅任务发起用户可查询。
     * 任务到达终态（failed/completed）时结算预扣积分，结算通过 settled 标志位保证
     * 在重复轮询下的幂等性，绝不重复退款/结算
     */
    public String relayVideoStatusRequest(String tokenKey, String videoId) {
        Token token = support.validateToken(tokenKey);
        if (videoId == null || videoId.isBlank() || !videoId.matches("[A-Za-z0-9_\\-.:+/=]+")) {
            throw new BusinessException(400, "无效的视频任务 id");
        }
        VideoTask task = videoTaskRepository
                .findFirstByUpstreamIdAndUserIdOrderByCreatedAtDesc(videoId, token.getUserId())
                .orElseThrow(() -> new BusinessException(404, "视频任务不存在: " + videoId));
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseUntil = now.plusMinutes(VIDEO_TASK_LEASE_MINUTES);
        if (videoTaskRepository.claimForPolling(task.getId(), now, leaseUntil) != 1) {
            throw new BusinessException(409, "视频任务正在处理中，请稍后重试");
        }
        try {
            videoTaskUsageLogService.ensureLinked(task.getId());
            Channel channel = channelService.getById(task.getChannelId());
            String response = support.forwardGetRequest(channel,
                    support.videoStatusPath(channel, task.getUpstreamId(), task.getModel()));
            // 结算必须先于 OSS 重写完成（该保证由返回值强制执行，而非仅靠调用顺序）：结算失败/异常时
            // settleVideoTaskIfTerminal 返回 false，本次直接返回上游原始响应，绝不转存 OSS / 缓存任何地址，
            // 否则会出现"OSS 转存成功但结算异常，预扣积分错失随本次响应结算的机会"的情况
            boolean settlementOk = settleVideoTaskIfTerminal(task, response);
            if (!settlementOk) {
                log.error("视频任务终态结算失败，跳过本次 OSS 转存，返回上游原始响应: taskId={}, upstreamId={}",
                        task.getId(), task.getUpstreamId());
                return response;
            }
            return ossMediaStorageService.rewriteVideoResponseIfCompleted(response, task.getOssUrl(),
                    ossUrl -> videoTaskRepository.updateOssUrl(task.getId(), ossUrl));
        } finally {
            videoTaskRepository.releaseClaim(task.getId(), leaseUntil);
        }
    }

    private static final Set<String> VIDEO_FAILED_STATUSES = Set.of(
            "failed", "failure", "error", "cancelled", "canceled");
    private static final Set<String> VIDEO_COMPLETED_STATUSES = Set.of("completed", "succeeded");

    private boolean isTerminalStatus(String status) {
        String normalized = status.toLowerCase(java.util.Locale.ROOT);
        return VIDEO_FAILED_STATUSES.contains(normalized) || VIDEO_COMPLETED_STATUSES.contains(normalized);
    }

    /**
     * 视频任务终态计费结算：failed 退回预扣积分；completed 按实际时长（若上游暴露）多退少补，
     * 否则保持预扣金额。实际的置位与退款/补扣、使用日志回写在 {@link VideoTaskSettlementService}
     * 中位于同一个数据库事务，任一环节失败都会整体回滚（任务保持未结算，供下次轮询或后台对账
     * 任务安全重试）。
     *
     * @return true 表示无需结算（未到终态/已结算/解析失败）或结算成功；false 表示检测到终态但结算抛出异常——
     *         调用方据此必须放弃本次 OSS 转存，不能让客户端拿到一个从未真正结算过的产物地址
     */
    private boolean settleVideoTaskIfTerminal(VideoTask task, String response) {
        if (task.isSettled()) {
            return true;
        }
        JsonNode json;
        try {
            json = support.objectMapper.readTree(response);
        } catch (Exception e) {
            return true;
        }
        return settleVideoTaskIfTerminal(task, json);
    }

    /**
     * 与字符串重载相同的结算逻辑，直接接受已解析的 JsonNode，避免调用方（如创建接口同步返回终态时）
     * 重复解析同一个响应。状态解析复用 {@link OssMediaStorageService#extractVideoStatus}，
     * 与 OSS 重写识别的嵌套状态位置（顶层 status/state、data.status、video.status 等，内层任务对象
     * 优先于外层字段）完全一致，防止两处识别范围不一致导致「已转存 OSS 但从未结算」的计费遗漏
     */
    private boolean settleVideoTaskIfTerminal(VideoTask task, JsonNode json) {
        if (task.isSettled() || json == null || !json.isObject()) {
            return true;
        }
        OssMediaStorageService.VideoTaskStatus resolved = ossMediaStorageService.extractVideoStatus(json);
        if (resolved == null || !isTerminalStatus(resolved.status())) {
            return true;
        }
        boolean failed = VIDEO_FAILED_STATUSES.contains(resolved.status().toLowerCase(java.util.Locale.ROOT));
        try {
            if (failed) {
                videoTaskSettlementService.settleFailed(task.getId());
            } else {
                // 时长必须从状态实际来源的同一节点读取（video/data/顶层），而非固定读顶层字段——
                // 否则终态从 data.status 解析出来时，顶层没有时长字段将导致结算永远拿到 null，
                // 任务被永久按预扣金额结算而非实际时长
                Integer actualSeconds = parsePositiveSecondsField(resolved.duration());
                videoTaskSettlementService.settleCompleted(task.getId(), actualSeconds);
            }
            return true;
        } catch (Exception e) {
            log.error("视频任务终态结算异常，任务保持未结算，将由下次轮询或后台对账任务重试: taskId={}, upstreamId={}",
                    task.getId(), task.getUpstreamId(), e);
            return false;
        }
    }

    /** 后台对账任务每次最多处理的任务数，避免单次调度扫描过多行 */
    private static final int RECONCILE_BATCH_SIZE = 50;
    /** 创建时间早于该时长的未结算任务才纳入对账扫描，避免与仍在正常轮询中的新任务抢跑 */
    private static final long RECONCILE_MIN_AGE_MINUTES = 2;
    /**
     * 每次对账尝试后，将任务的下次可扫描时间推迟该时长（与调度间隔一致）。
     * 配合按 nextReconcileAt 升序排序，卡住的任务会被推到队列末尾，让其后创建、
     * 尚未检查过的任务在下一轮优先进入批次，避免长期不可达的任务永久占满固定的 50 个名额
     */
    private static final long RECONCILE_RETRY_DELAY_MINUTES = 5;
    /** 上游状态查询的租约时长，覆盖常规请求超时并防止进程崩溃后永久占用。 */
    private static final long VIDEO_TASK_LEASE_MINUTES = 10;

    /**
     * 对账任务分布式锁 key。任务本身已通过 claimForProcessing 做了行级租约，多实例并发执行本身是安全的；
     * 加此锁主要是避免多实例同时扫描同一批 50 条候选任务、造成重复的行级 CAS 竞争和无谓的上游查询。
     */
    private static final String RECONCILE_LOCK_KEY = "job:videoReconcile";
    /**
     * 锁 TTL：15 分钟。RedisDistributedLock 内置续约 watchdog（每 TTL/3 ≈ 5 分钟续约一次），
     * 只要任务仍在运行锁就不会过期，因此 TTL 不必再覆盖“批次最多 50 个任务、单任务最坏 150s”
     * 这种理论最长耗时（原为 4 小时），只需大于两次续约之间的间隔即可。
     * 进程崩溃（无法再续约）时锁最迟 15 分钟后自然释放，相比原先 4 小时的对账延迟大幅收窄。
     */
    private static final long RECONCILE_LOCK_TTL_SECONDS = 15 * 60L;

    /**
     * 后台对账：定期扫描长时间未结算的视频任务，主动向上游查询状态并结算，
     * 兜底客户端从不轮询、轮询提前中止或断线的场景（否则这些任务将永远不会被退款/结算）。
     * 与轮询接口共用同一套幂等结算逻辑（{@link VideoTaskSettlementService} 内的原子置位 + 同事务
     * 落库），不会与轮询路径发生竞态或重复结算
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000L, initialDelay = 5 * 60 * 1000L)
    public void reconcileUnsettledVideoTasks() {
        distributedLock.runIfLocked(RECONCILE_LOCK_KEY, RECONCILE_LOCK_TTL_SECONDS, () -> {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime cutoff = now.minusMinutes(RECONCILE_MIN_AGE_MINUTES);
            List<VideoTask> pending = videoTaskRepository.findReconcileCandidates(
                    cutoff, now, PageRequest.of(0, RECONCILE_BATCH_SIZE));
            if (pending.isEmpty()) {
                return;
            }
            log.info("视频任务对账：待处理 {} 个未结算任务", pending.size());
            for (VideoTask task : pending) {
                LocalDateTime claimTime = LocalDateTime.now();
                LocalDateTime leaseUntil = claimTime.plusMinutes(VIDEO_TASK_LEASE_MINUTES);
                if (videoTaskRepository.claimForProcessing(task.getId(), claimTime, leaseUntil) != 1) {
                    continue;
                }
                try {
                    videoTaskUsageLogService.ensureLinked(task.getId());
                    Channel channel = channelService.getById(task.getChannelId());
                    String response = support.forwardGetRequest(channel,
                            support.videoStatusPath(channel, task.getUpstreamId(), task.getModel()));
                    settleVideoTaskIfTerminal(task, response);
                } catch (Exception e) {
                    log.warn("视频任务对账失败，将于下次调度重试: taskId={}, upstreamId={}", task.getId(), task.getUpstreamId(), e);
                } finally {
                    // 无论本轮是否结算成功都推迟 nextReconcileAt：已结算的任务后续查询会被 settled=false
                    // 条件过滤掉，不受影响；仍未结算的任务借此让位给其它任务，避免同一批任务被反复选中
                    try {
                        videoTaskRepository.touchNextReconcileAt(task.getId(),
                                LocalDateTime.now().plusMinutes(RECONCILE_RETRY_DELAY_MINUTES));
                    } finally {
                        videoTaskRepository.releaseClaim(task.getId(), leaseUntil);
                    }
                }
            }
        });
    }

    private String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && node.isTextual() && !node.asText().isBlank()) {
                return node.asText();
            }
        }
        return null;
    }

    /** 时长必须是有限正数且不超过合理上限，拒绝 Infinity/NaN/溢出型输入（如 "1e309"） */
    private boolean isValidVideoSeconds(double value) {
        return Double.isFinite(value) && value > 0
                && value <= MediaDurationLimits.MAX_VIDEO_DURATION_SECONDS;
    }

    /** 解析 seconds 字段：OpenAI 标准字段为字符串数字，同时兼容 JSON 数字类型 */
    private Integer parsePositiveSecondsField(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber() && isValidVideoSeconds(node.asDouble())) {
            return (int) Math.ceil(node.asDouble());
        }
        if (node.isTextual()) {
            try {
                double value = Double.parseDouble(node.asText().trim());
                if (isValidVideoSeconds(value)) {
                    return (int) Math.ceil(value);
                }
            } catch (NumberFormatException ignored) {
                // 非数字字符串，忽略
            }
        }
        return null;
    }

    // ==================== 音频中转 ====================

    /**
     * 语音合成 (/v1/audio/speech)：请求为 JSON，响应为二进制音频，按原始字节透传。
     * 计费不信任客户端申报时长：按输入文本与语速预估秒数预扣，
     * 拿到上游音频后测量实际生成时长并多退少补结算。
     * quality 为可选自定义参数（standard/hd 选择档位，缺省 STANDARD），转发上游前剥离。
     */
    public void relayAudioSpeech(String tokenKey, String requestBody, String model,
                                 HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException {
        RelaySupport.RelayContext ctx = support.validateAndPrepare(tokenKey, model, "audio");

        JsonNode body = support.objectMapper.readTree(requestBody);
        if (!body.isObject()) {
            throw new BusinessException(400, "请求体必须是 JSON 对象");
        }
        JsonNode inputNode = body.get("input");
        if (inputNode == null || !inputNode.isTextual() || inputNode.asText().isEmpty()) {
            throw new BusinessException(400, "语音合成请求缺少有效的 input 文本参数");
        }
        double speed = 1.0;
        if (body.hasNonNull("speed")) {
            JsonNode s = body.get("speed");
            if (!s.isNumber() || s.asDouble() < 0.25 || s.asDouble() > 4.0) {
                throw new BusinessException(400, "speed 参数必须是 0.25~4.0 之间的数字");
            }
            speed = s.asDouble();
        }
        String quality = extractStrictQuality(body);
        String responseFormat = body.hasNonNull("response_format") ? body.get("response_format").asText() : "mp3";

        // 剥离非标准计费参数后转发上游
        com.fasterxml.jackson.databind.node.ObjectNode upstreamBody = ((com.fasterxml.jackson.databind.node.ObjectNode) body).deepCopy();
        upstreamBody.remove("quality");
        upstreamBody.remove("duration");
        upstreamBody.remove("seconds");
        String upstreamJson = support.objectMapper.writeValueAsString(upstreamBody);

        int estimatedSeconds = estimateSpeechSeconds(inputNode.asText(), speed);
        BigDecimal estimatedCost = usageLogService.calculateAudioCreditCost(ctx.modelConfig(), quality, estimatedSeconds);
        RelaySupport.MediaCharge charge = support.chargeMediaCredits(ctx, estimatedCost);

        long startTime = System.currentTimeMillis();
        ChannelResult<RelaySupport.BinaryResponse> result;
        try {
            result = forwardWithRetry(ctx, channel -> support.forwardBinaryRequest(channel, "/v1/audio/speech", upstreamJson));
        } catch (RuntimeException e) {
            if (modelGroupFailoverExecutor != null && ctx.modelConfig() != null
                    && ctx.modelConfig().getFallbackGroupId() != null
                    && e instanceof BusinessException be && FailureClassifier.isSwitchable(be)) {
                var fallback = modelGroupFailoverExecutor.resolveFallbackGroup(ctx.modelConfig().getFallbackGroupId(),
                        "audio", "admin".equals(ctx.user().getRole()));
                if (fallback.isPresent()) {
                    requireMediaRefundBeforeFallback(ctx, charge);
                    modelGroupFailoverExecutor.relayAudioSpeechWithContext(ctx, fallback.get(), requestBody,
                            httpRequest, httpResponse);
                    return;
                }
            }
            throw e;
        }
        byte[] audio = result.value().body();
        long duration = System.currentTimeMillis() - startTime;

        // 按实际生成音频时长结算：无法测量时视为上游返回不可信（格式未知/容器损坏/伪造结构），
        // 退回预扣积分并拒绝请求，绝不在无法验证时长的情况下仍按预估金额计费、返回 200
        double measured = "pcm".equalsIgnoreCase(responseFormat)
                ? AudioDurationUtil.pcmSeconds(audio)
                : AudioDurationUtil.measure(audio);
        if (measured <= 0) {
            boolean refunded = support.refundMediaCharge(ctx, charge);
            log.error("无法测量生成音频的实际时长 (format={})，拒绝计费并退回预扣积分", responseFormat);
            throw new BusinessException(400, "生成的音频无法测量有效时长，上游返回可能已损坏或格式不受支持"
                    + (refunded ? "，预扣积分已退回" : "，退回预扣积分失败，已记录待人工补偿"));
        }
        int actualSeconds = Math.max(1, (int) Math.ceil(measured));
        BigDecimal actualCost = usageLogService.calculateAudioCreditCost(ctx.modelConfig(), quality, actualSeconds);
        BigDecimal finalCost = support.settleMediaCharge(ctx, charge, actualCost);
        recordPrepaidUsageSafely(ctx.token(), result.channel(), model,
                new RelaySupport.MediaCharge(finalCost, charge.deducted()),
                duration, httpRequest, "/v1/audio/speech");

        String contentType = result.value().contentType();
        httpResponse.setStatus(200);
        httpResponse.setContentType(contentType != null ? contentType : "application/octet-stream");
        httpResponse.setContentLength(audio.length);
        httpResponse.getOutputStream().write(audio);
        httpResponse.getOutputStream().flush();
    }

    /**
     * 语音转写/翻译 (/v1/audio/transcriptions, /v1/audio/translations)：
     * 请求为 multipart/form-data 文件上传，重建 multipart 转发上游；
     * 响应（JSON 或 response_format=text/srt/vtt 的纯文本）按原始字节透传。
     * 计费不信任客户端申报时长：解码上传文件的音频元数据获得真实时长，按实际秒数预扣。
     */
    /** 上传音频大小上限（与 OpenAI 25MB 限制一致），配合限时长测量约束堆内存占用 */
    static final long MAX_AUDIO_UPLOAD_BYTES = 25L * 1024 * 1024;

    /** OpenAI speech input 的字符数上限 */
    static final int MAX_SPEECH_INPUT_CHARACTERS = 4096;

    /** 这些 response_format 的响应是纯文本，按原始字节透传；其余（json/verbose_json）必须校验为合法 JSON 才计费 */
    private static final Set<String> RAW_TEXT_RESPONSE_FORMATS = Set.of("text", "srt", "vtt");

    public ResponseEntity<byte[]> relayAudioTranscription(String tokenKey, String path, MultipartFile file,
                                                          MultiValueMap<String, String> formFields,
                                                          HttpServletRequest httpRequest) throws IOException {
        rejectInconsistentDuplicateFields(formFields);
        String model = formFields.getFirst("model");
        RelaySupport.RelayContext ctx = support.validateAndPrepare(tokenKey, model, "audio");

        String quality = formFields.getFirst("quality");
        if (quality != null && UsageLogService.resolveAudioTier(quality) == null) {
            throw new BusinessException(400, "不支持的音频音质参数 (quality): " + quality + "，支持 standard/hd");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请求缺少音频文件 (file)");
        }
        if (file.getSize() > MAX_AUDIO_UPLOAD_BYTES) {
            throw new BusinessException(413, "上传音频超过大小限制 (" + MAX_AUDIO_UPLOAD_BYTES / (1024 * 1024) + "MB)");
        }
        byte[] fileBytes = file.getBytes();
        double measured = AudioDurationUtil.measure(fileBytes);
        if (measured <= 0) {
            throw new BusinessException(400, "无法可靠测量上传音频的时长（按秒计费所需），"
                    + "支持 wav/mp3/flac/ogg/opus/m4a/mp4/aac/webm 格式；裸 PCM 不支持，请封装为 WAV 后上传");
        }
        int seconds = Math.max(1, (int) Math.ceil(measured));
        BigDecimal cost = usageLogService.calculateAudioCreditCost(ctx.modelConfig(), quality, seconds);

        okhttp3.MultipartBody.Builder mb = new okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM);
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "audio";
        okhttp3.MediaType fileType = okhttp3.MediaType.parse(
                file.getContentType() != null ? file.getContentType() : "application/octet-stream");
        mb.addFormDataPart("file", fileName, okhttp3.RequestBody.create(fileBytes, fileType));
        // 逐 part 重建：数组参数（如 timestamp_granularities[]）保留每个值；
        // 单值参数（如 model、response_format）此前已校验过不存在取值冲突的重复，
        // 去重为单值转发，避免本地按第一个值做的校验（如 rawPassThrough 判定）
        // 与实际转发给上游、可能被上游按最后一个值处理的语义不一致
        for (Map.Entry<String, java.util.List<String>> field : formFields.entrySet()) {
            String key = field.getKey();
            // 剥离非标准计费参数，其余表单字段原样转发
            if ("quality".equals(key) || "duration".equals(key) || "seconds".equals(key)) {
                continue;
            }
            java.util.List<String> values = field.getValue();
            if (!key.endsWith("[]") && values.size() > 1) {
                mb.addFormDataPart(key, values.get(0));
            } else {
                for (String value : values) {
                    mb.addFormDataPart(key, value);
                }
            }
        }
        okhttp3.MultipartBody multipartBody = mb.build();

        String responseFormat = formFields.getFirst("response_format");
        boolean rawPassThrough = responseFormat != null
                && RAW_TEXT_RESPONSE_FORMATS.contains(responseFormat.trim().toLowerCase());

        RelaySupport.MediaCharge charge = support.chargeMediaCredits(ctx, cost);
        long startTime = System.currentTimeMillis();
        ChannelResult<RelaySupport.BinaryResponse> result;
        try {
            result = forwardWithRetry(ctx, channel -> support.forwardMultipartRequest(channel, path, multipartBody));
        } catch (RuntimeException e) {
            if (modelGroupFailoverExecutor != null && ctx.modelConfig() != null
                    && ctx.modelConfig().getFallbackGroupId() != null
                    && e instanceof BusinessException be && FailureClassifier.isSwitchable(be)) {
                var fallback = modelGroupFailoverExecutor.resolveFallbackGroup(ctx.modelConfig().getFallbackGroupId(),
                        "audio", "admin".equals(ctx.user().getRole()));
                if (fallback.isPresent()) {
                    requireMediaRefundBeforeFallback(ctx, charge);
                    return modelGroupFailoverExecutor.relayAudioTranscriptionWithContext(ctx, fallback.get(), path,
                            fileBytes, fileName, file.getContentType(), formFields, rawPassThrough,
                            seconds, quality, httpRequest);
                }
            }
            throw e;
        }

        // json/verbose_json 必须解析为含 text 字段的 JSON 对象才算成功，否则退款报错，不对坏响应计费
        if (!rawPassThrough && !isValidTranscriptionJson(result.value().body())) {
            boolean refunded = support.refundMediaCharge(ctx, charge);
            throw new BusinessException(502, "上游返回无法解析的转写结果"
                    + (refunded ? "，预扣积分已退回" : "，退回预扣积分失败，已记录待人工补偿"));
        }

        long duration = System.currentTimeMillis() - startTime;
        recordPrepaidUsageSafely(ctx.token(), result.channel(), model, charge, duration, httpRequest, path);

        String contentType = result.value().contentType();
        return ResponseEntity.ok()
                .header("Content-Type", contentType != null ? contentType : "application/json")
                .body(result.value().body());
    }

    /**
     * 单值参数（表单字段名不以 "[]" 结尾，如 model、response_format）若重复传值且取值不一致，
     * 直接拒绝：本地校验（如按 response_format 第一个值判定是否跳过 JSON 校验）只会采信其中一个值，
     * 若上游按不同规则（如最后一个值）解析，会导致本地校验通过的语义与实际转发生效的语义不一致
     */
    private void rejectInconsistentDuplicateFields(MultiValueMap<String, String> formFields) {
        for (Map.Entry<String, java.util.List<String>> field : formFields.entrySet()) {
            String key = field.getKey();
            if (key.endsWith("[]")) {
                continue;
            }
            java.util.List<String> values = field.getValue();
            if (values.size() > 1 && new HashSet<>(values).size() > 1) {
                throw new BusinessException(400, "参数 " + key + " 重复传值且取值不一致，请只传一个值");
            }
        }
    }

    private boolean isValidTranscriptionJson(byte[] body) {
        if (body == null || body.length == 0) {
            return false;
        }
        try {
            JsonNode json = support.objectMapper.readTree(body);
            return json != null && json.isObject() && json.has("text");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 严格校验可选的 quality 参数：缺省返回 null（按 STANDARD 档计费），
     * 非字符串或无法识别的值直接拒绝，不静默降级
     */
    private String extractStrictQuality(JsonNode body) {
        if (!body.has("quality") || body.get("quality").isNull()) {
            return null;
        }
        JsonNode q = body.get("quality");
        if (!q.isTextual() || UsageLogService.resolveAudioTier(q.asText()) == null) {
            throw new BusinessException(400, "不支持的音频音质参数 (quality): " + q + "，支持 standard/hd");
        }
        return q.asText();
    }

    /**
     * 语音合成预估时长（秒）：按约 14 字符/秒的常见语速估算并随 speed 缩放，实际时长测得后结算差额
     */
    private int estimateSpeechSeconds(String input, double speed) {
        int chars = input.codePointCount(0, input.length());
        if (chars > MAX_SPEECH_INPUT_CHARACTERS) {
            throw new BusinessException(413, "语音合成 input 超过字符数限制 (4096)");
        }
        return Math.max(1, (int) Math.ceil(chars / (14.0 * speed)));
    }

    /**
     * 中转流式请求 (SSE) - 最多重试 3 次，仅在响应未提交前可重试
     */
    public void relayStreamRequest(String tokenKey, String path, String requestBody,
                                    String model, HttpServletRequest httpRequest,
                                    HttpServletResponse httpResponse) throws IOException {
        RelaySupport.RelayContext ctx = support.validateAndPrepare(tokenKey, model);
        Set<Long> triedChannels = new HashSet<>();
        Long modelConfigId = ctx.modelConfig() != null ? ctx.modelConfig().getId() : null;
        long startTime = System.currentTimeMillis();
        String lastError = null;
        BusinessException lastFailure = null;
        int attempt = 0;

        while (attempt < RelaySupport.MAX_RETRIES) {
            Channel channel;
            try {
                channel = support.channelRouter.selectChannel(ctx.channelModelId(), triedChannels, ctx.userLevel());
            } catch (BusinessException e) {
                if (tryFallbackStream(ctx, path, requestBody, httpRequest, httpResponse, lastFailure)) return;
                if (!httpResponse.isCommitted()) {
                    RelayServiceUtils.writeOpenAiError(httpResponse, 502, "所有渠道均不可用: " + lastError);
                }
                return;
            }
            triedChannels.add(channel.getId());

            if (support.isChannelRateLimited(channel)) {
                lastError = "渠道 " + channel.getId() + " 请求频率超限";
                log.warn("跳过限流渠道 {}: {}", channel.getId(), lastError);
                continue;
            }

            attempt++;
            String modifiedBody = support.injectStreamOptions(requestBody, path);

            SseUtils.setSseHeaders(httpResponse);

            HttpURLConnection conn;
            try {
                if (support.isGeminiTypeChannel(channel)) {
                    String geminiBody = ProtocolConverter.convertOpenAiToGeminiRequest(modifiedBody);
                    conn = support.createSseConnection(channel, "/v1/models/" +
                            (model != null ? model : "default") + ":streamGenerateContent?alt=sse", geminiBody);
                } else {
                    conn = support.createSseConnection(channel, path, modifiedBody);
                }
            } catch (IOException e) {
                lastError = e.getMessage();
                lastFailure = new BusinessException(502, "渠道请求失败: " + e.getMessage(), e);
                log.error("渠道 {} 流式连接失败 (尝试 {}/{}): {}", channel.getId(), attempt, RelaySupport.MAX_RETRIES, e.getMessage());
                support.dispatchRelayFailure(channel.getId(), modelConfigId, lastFailure, () ->
                        support.channelHealthTracker.recordFailure(channel.getId(),
                                ChannelHealthTracker.ErrorCategory.fromException(e), e.getMessage()));
                if (attempt < RelaySupport.MAX_RETRIES && !httpResponse.isCommitted()) continue;
                if (tryFallbackStream(ctx, path, requestBody, httpRequest, httpResponse, lastFailure)) return;
                if (!httpResponse.isCommitted()) {
                    RelayServiceUtils.writeOpenAiError(httpResponse, 502, "渠道请求失败: " + e.getMessage());
                }
                return;
            }

            try {
                int code = conn.getResponseCode();
                log.info("HTTP请求返回, code: {}, channel: {}", code, channel.getId());
                if (code != 200) {
                    String errorBody = conn.getErrorStream() != null
                            ? new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8) : "";
                    lastError = "HTTP " + code + " - " + errorBody;
                    lastFailure = BusinessException.upstream(code, "上游 API 错误: " + errorBody,
                            errorBody, RelaySupport.resolveCooldownSeconds(conn, errorBody));
                    log.warn("渠道 {} 流式请求失败: {}", channel.getId(), lastError);
                    String streamError = lastError;
                    BusinessException streamFailure = lastFailure;
                    support.dispatchRelayFailure(channel.getId(), modelConfigId, streamFailure, () ->
                            support.channelHealthTracker.recordFailure(channel.getId(),
                                    ChannelHealthTracker.ErrorCategory.fromStatusCode(code), streamError));
                    conn.disconnect();
                    if (attempt < RelaySupport.MAX_RETRIES && !httpResponse.isCommitted()) continue;
                    if (tryFallbackStream(ctx, path, requestBody, httpRequest, httpResponse, lastFailure)) return;
                    httpResponse.setStatus(code);
                    httpResponse.getWriter().write(errorBody.isEmpty()
                            ? "{\"error\":{\"message\":\"上游返回 HTTP " + code + "\"}}" : errorBody);
                    return;
                }

                String lastUsageData;
                if (support.isGeminiTypeChannel(channel)) {
                    lastUsageData = streamGeminiResponseAsOpenAi(conn, httpResponse);
                } else {
                    lastUsageData = support.streamSseResponse(conn, httpResponse, null);
                }
                conn.disconnect();

                long duration = System.currentTimeMillis() - startTime;
                RelayServiceUtils.UsageInfo usage = RelayServiceUtils.parseOpenAiStreamUsage(support.objectMapper, lastUsageData);
                support.recordStreamUsage(ctx.token(), channel, model,
                        usage.promptTokens(), usage.completionTokens(),
                        usage.cachedTokens(), 0, usage.cachedTokens(),
                        duration, httpRequest, path);
                support.channelHealthTracker.recordSuccess(channel.getId());
                return;
            } catch (Exception e) {
                conn.disconnect();
                lastError = e.getMessage();
                lastFailure = new BusinessException(502, "渠道请求失败: " + e.getMessage(), e);
                log.error("渠道 {} 流式请求异常 (尝试 {}/{}): {}", channel.getId(), attempt, RelaySupport.MAX_RETRIES, e.getMessage());
                support.dispatchRelayFailure(channel.getId(), modelConfigId, lastFailure, () ->
                        support.channelHealthTracker.recordFailure(channel.getId(),
                                ChannelHealthTracker.ErrorCategory.fromException(e), e.getMessage()));
                if (attempt < RelaySupport.MAX_RETRIES && !httpResponse.isCommitted()) continue;
                if (tryFallbackStream(ctx, path, requestBody, httpRequest, httpResponse, lastFailure)) return;
                if (!httpResponse.isCommitted()) {
                    RelayServiceUtils.writeOpenAiError(httpResponse, 502, "渠道请求失败: " + e.getMessage());
                }
                return;
            }
        }
    }

    private boolean tryFallbackStream(RelaySupport.RelayContext ctx, String path, String requestBody,
                                      HttpServletRequest httpRequest, HttpServletResponse httpResponse,
                                      BusinessException failure) throws IOException {
        if (failure == null || httpResponse.isCommitted() || modelGroupFailoverExecutor == null
                || ctx.modelConfig() == null || ctx.modelConfig().getFallbackGroupId() == null
                || !FailureClassifier.isSwitchable(failure)) {
            return false;
        }
        var fallback = modelGroupFailoverExecutor.resolveFallbackGroup(ctx.modelConfig().getFallbackGroupId(),
                "text", "admin".equals(ctx.user().getRole()));
        if (fallback.isEmpty()) return false;
        modelGroupFailoverExecutor.relayStreamRequestWithContext(ctx, fallback.get(), path, requestBody,
                httpRequest, httpResponse);
        return true;
    }

    /** A failed refund must stop fallback before the group can pre-deduct the request again. */
    private void requireMediaRefundBeforeFallback(RelaySupport.RelayContext ctx, RelaySupport.MediaCharge charge) {
        if (!support.refundMediaCharge(ctx, charge)) {
            throw new BusinessException(500,
                    "原模型预扣积分退回失败，已记录待人工补偿，停止进入故障转移组");
        }
    }

    /**
     * 读取 Gemini SSE 响应并转换为 OpenAI SSE 格式输出
     * 返回最后包含 usage 的数据行
     */
    private String streamGeminiResponseAsOpenAi(HttpURLConnection conn, HttpServletResponse httpResponse) throws IOException {
        String lastUsageData = null;
        var writer = httpResponse.getWriter();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    try {
                        JsonNode json = support.objectMapper.readTree(data);
                        JsonNode usageMeta = json.get("usageMetadata");
                        if (usageMeta != null) {
                            lastUsageData = data;
                        }

                        String openAiChunk = RelayServiceUtils.convertGeminiStreamChunkToOpenAiSse(support.objectMapper, json);
                        if (openAiChunk != null) {
                            writer.write("data: " + openAiChunk + "\n\n");
                            writer.flush();
                        }
                    } catch (Exception parseEx) {
                        log.warn("转换 Gemini SSE 为 OpenAI 格式失败: {}", data);
                    }
                }
            }
        }
        writer.write("data: [DONE]\n\n");
        writer.flush();
        return lastUsageData;
    }
}
