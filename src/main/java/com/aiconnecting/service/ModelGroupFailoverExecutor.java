package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.ProtocolConverter;
import com.aiconnecting.common.SseUtils;
import com.aiconnecting.entity.Channel;
import com.aiconnecting.entity.ModelGroup;
import com.aiconnecting.entity.Token;
import com.aiconnecting.entity.UsageLog;
import com.aiconnecting.entity.VideoTask;
import com.aiconnecting.repository.VideoTaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 模型组请求的独立处理路径：解析组内成员顺序，逐个尝试并在上游失败时自动切换成员重试，
 * 使用模型组自身价格计费，usage_logs.model 写入组名、actualModel 写入实际调用的成员模型名。
 * 与既有单模型转发路径（{@link OpenAiRelayService}）完全独立，仅复用 {@link RelaySupport} 中的
 * 渠道选择/上游转发/预扣计费等与具体模型无关的基础能力，不改动既有单模型的行为。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModelGroupFailoverExecutor {

    private final RelaySupport support;
    private final ModelGroupService modelGroupService;
    private final ModelGroupRoutingService routingService;
    private final ModelGroupBillingService billingService;
    private final ModelHealthTracker modelHealthTracker;
    private final UsageLogService usageLogService;
    private final VideoTaskRepository videoTaskRepository;
    private final VideoTaskUsageLogService videoTaskUsageLogService;

    /** 总耗时预算（毫秒），介于业务约定的 120-150s 之间 */
    private static final long WALL_CLOCK_BUDGET_MS = 130_000L;

    public Optional<ModelGroup> findEnabledGroup(String name) {
        return modelGroupService.findByName(name).filter(g -> Boolean.TRUE.equals(g.getEnabled()));
    }

    private ModelGroup requireGroup(String groupName, String expectedType) {
        ModelGroup group = findEnabledGroup(groupName)
                .orElseThrow(() -> new BusinessException(404, "模型组不存在或未启用: " + groupName));
        if (!expectedType.equals(group.getType())) {
            throw new BusinessException(400, "模型组 " + groupName + " 类型为 " + group.getType()
                    + "，不能用于 " + expectedType + " 端点");
        }
        return group;
    }

    /**
     * admin_only 的模型组只用于在 /v1/models 中对普通用户隐藏，本身不能替代直接调用的权限校验：
     * 客户端只要知道组名（如 Token 未设置 allowed_models 白名单）即可直接请求，因此必须在
     * 每个直连组名的入口显式拒绝非管理员用户，与单模型 adminOnly 在 {@link RelaySupport#validateAndPrepare}
     * 中的校验方式保持一致
     */
    private void checkGroupAdminOnly(ModelGroup group, boolean isAdmin) {
        if (Boolean.TRUE.equals(group.getAdminOnly()) && !isAdmin) {
            throw new BusinessException(403, "该模型组仅限管理员使用: " + group.getName());
        }
    }

    /** 按 id 解析故障转移组（fallback_group_id），类型不符或未启用时返回空，调用方应放弃转入组、直接抛出原错误 */
    public Optional<ModelGroup> resolveFallbackGroup(Long groupId, String expectedType) {
        if (groupId == null) {
            return Optional.empty();
        }
        return modelGroupService.findById(groupId)
                .filter(g -> Boolean.TRUE.equals(g.getEnabled()))
                .filter(g -> expectedType.equals(g.getType()));
    }

    private boolean isFastFail(BusinessException e) {
        return !FailureClassifier.isSwitchable(e);
    }

    /**
     * 剩余总耗时预算（毫秒），作为本次尝试的读超时上限：固定 120s 读超时叠加多次尝试会让总耗时
     * 远超 {@link #WALL_CLOCK_BUDGET_MS}，因此每次尝试的超时必须收窄到"不超过剩余预算"
     */
    private long remainingBudgetMs(long deadline) {
        return Math.max(deadline - System.currentTimeMillis(), 1000L);
    }

    private String rewriteModelField(String requestBody, String model) {
        try {
            JsonNode node = support.objectMapper.readTree(requestBody);
            if (node instanceof ObjectNode obj) {
                obj.put("model", model);
                return support.objectMapper.writeValueAsString(obj);
            }
        } catch (Exception e) {
            log.warn("重写模型组请求体 model 字段失败，使用原始请求体: {}", e.getMessage());
        }
        return requestBody;
    }

    private void recordModelFailure(Long channelId, Long modelConfigId, int code) {
        ModelHealthTracker.FailureType type;
        if (code == 429) {
            type = ModelHealthTracker.FailureType.RATE_LIMIT;
        } else if (code == 404) {
            type = ModelHealthTracker.FailureType.MODEL_NOT_FOUND;
        } else {
            type = ModelHealthTracker.FailureType.RATE_LIMIT;
        }
        modelHealthTracker.recordFailure(channelId, modelConfigId, type);
    }

    // ==================== 文本（非流式） ====================

    public String relayRequest(String tokenKey, String path, String requestBody, String groupName,
                               HttpServletRequest httpRequest) {
        ModelGroup group = requireGroup(groupName, "text");
        RelaySupport.RelayContext ctx = support.prepareGroupContext(tokenKey, groupName);
        checkGroupAdminOnly(group, "admin".equals(ctx.user().getRole()));
        return relayRequestWithContext(ctx, group, path, requestBody, httpRequest);
    }

    /**
     * 供单模型故障转移组（fallback_group_id）复用：Token/账号/余额/权限已在原模型请求时对原模型名
     * 校验过，此处直接使用调用方已构建的 ctx，不再重复校验，也不要求组名本身在 Token 白名单中
     * （即便原模型可用而组名未被显式授权，故障转移仍应生效）
     */
    String relayRequestWithContext(RelaySupport.RelayContext ctx, ModelGroup group, String path,
                                   String requestBody, HttpServletRequest httpRequest) {
        String groupName = group.getName();
        boolean isAdmin = "admin".equals(ctx.user().getRole());
        List<ModelGroupRoutingService.Candidate> candidates = routingService.resolveOrderedCandidates(group, isAdmin);
        if (candidates.isEmpty()) {
            throw new BusinessException(503, "模型组无可用成员: " + groupName);
        }

        long startTime = System.currentTimeMillis();
        long deadline = startTime + WALL_CLOCK_BUDGET_MS;
        int maxAttempts = Math.min(group.getMaxAttempts() != null ? group.getMaxAttempts() : 5, 8);
        String lastError = null;
        int attempts = 0;
        int spins = 0;
        int maxSpins = maxAttempts * 4;
        int idx = 0;

        while (attempts < maxAttempts && spins < maxSpins && System.currentTimeMillis() < deadline) {
            spins++;
            ModelGroupRoutingService.Candidate candidate = candidates.get(idx % candidates.size());
            idx++;
            Long modelConfigId = candidate.modelConfig().getId();

            Channel channel;
            try {
                channel = support.channelRouter.selectChannel(candidate.channelModelId(), Set.of(), ctx.userLevel());
            } catch (BusinessException e) {
                lastError = e.getMessage();
                continue;
            }
            if (modelHealthTracker.isInCooldown(channel.getId(), modelConfigId)) {
                lastError = "成员 " + candidate.modelConfig().getName() + " 渠道 " + channel.getId() + " 处于冷却期";
                continue;
            }
            if (support.isChannelRateLimited(channel)) {
                lastError = "渠道 " + channel.getId() + " 请求频率超限";
                continue;
            }

            String memberModel = candidate.modelConfig().getName();
            String upstreamBody = rewriteModelField(requestBody, memberModel);
            attempts++;
            try {
                String response;
                long attemptTimeoutMs = remainingBudgetMs(deadline);
                if (support.isGeminiTypeChannel(channel)) {
                    String geminiBody = ProtocolConverter.convertOpenAiToGeminiRequest(upstreamBody);
                    response = support.forwardGeminiRequest(channel, geminiBody, attemptTimeoutMs);
                    response = ProtocolConverter.convertGeminiToOpenAiResponse(response);
                } else {
                    response = support.forwardRequest(channel, path, upstreamBody, attemptTimeoutMs);
                }
                long duration = System.currentTimeMillis() - startTime;
                recordGroupTextUsage(ctx.token(), channel, group, memberModel, response, duration, httpRequest, path);
                support.channelHealthTracker.recordSuccess(channel.getId());
                modelHealthTracker.recordSuccess(channel.getId(), modelConfigId);
                return response;
            } catch (BusinessException e) {
                lastError = e.getMessage();
                log.error("模型组 {} 成员 {} (渠道 {}) 请求失败 (尝试 {}/{}): {}",
                        groupName, memberModel, channel.getId(), attempts, maxAttempts, e.getMessage());
                if (isFastFail(e)) {
                    throw e;
                }
                support.channelHealthTracker.recordFailure(channel.getId(),
                        ChannelHealthTracker.ErrorCategory.fromStatusCode(e.getCode()), e.getMessage());
                recordModelFailure(channel.getId(), modelConfigId, e.getCode());
            }
        }
        throw new BusinessException(502, "模型组所有成员均不可用，最后错误: " + lastError);
    }

    private void recordGroupTextUsage(Token token, Channel channel, ModelGroup group, String actualModel,
                                      String response, long duration, HttpServletRequest httpRequest, String path) {
        int promptTokens = 0, completionTokens = 0, totalTokens = 0;
        int cachedTokens = 0, cacheCreationTokens = 0, cacheReadTokens = 0;
        try {
            JsonNode jsonNode = support.objectMapper.readTree(response);
            JsonNode usage = jsonNode.get("usage");
            if (usage != null) {
                promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : 0;
                completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : 0;
                totalTokens = usage.has("total_tokens") ? usage.get("total_tokens").asInt() : 0;
                JsonNode promptDetails = usage.path("prompt_tokens_details");
                if (!promptDetails.isMissingNode()) {
                    cachedTokens = promptDetails.has("cached_tokens") ? promptDetails.get("cached_tokens").asInt() : 0;
                }
                cacheCreationTokens = usage.has("cache_creation_input_tokens") ? usage.get("cache_creation_input_tokens").asInt() : 0;
                cacheReadTokens = usage.has("cache_read_input_tokens") ? usage.get("cache_read_input_tokens").asInt() : 0;
                if (cachedTokens == 0 && cacheReadTokens > 0) {
                    cachedTokens = cacheReadTokens;
                }
            }
        } catch (Exception e) {
            log.warn("模型组文本响应 usage 解析失败: {}", e.getMessage());
        }
        BigDecimal creditCost = billingService.calculateTextCreditCost(group, promptTokens, completionTokens, cachedTokens);
        UsageLog usageLog = UsageLog.builder()
                .tokenId(token.getId())
                .channelId(channel.getId())
                .model(group.getName())
                .actualModel(actualModel)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .promptTokensCacheHit(cachedTokens)
                .cachedTokensCacheCreation(cacheCreationTokens)
                .cachedTokensCacheRead(cacheReadTokens)
                .creditCost(creditCost)
                .ip(support.getClientIp(httpRequest))
                .duration(duration)
                .requestPath(path)
                .build();
        usageLogService.recordUsageAndQuotas(usageLog, token.getId(), channel.getId(), totalTokens, token.getUserId());
    }

    // ==================== 文本（流式 SSE） ====================

    /**
     * 流式模型组请求：仅在向客户端写出首字节前允许切换成员；一旦响应已提交（isCommitted），
     * 后续失败只能在协议层写 SSE error 事件并结束，绝不拼接另一个成员的输出。
     */
    public void relayStreamRequest(String tokenKey, String path, String requestBody, String groupName,
                                   HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException {
        ModelGroup group = requireGroup(groupName, "text");
        RelaySupport.RelayContext ctx = support.prepareGroupContext(tokenKey, groupName);
        boolean isAdmin = "admin".equals(ctx.user().getRole());
        checkGroupAdminOnly(group, isAdmin);
        List<ModelGroupRoutingService.Candidate> candidates = routingService.resolveOrderedCandidates(group, isAdmin);
        if (candidates.isEmpty()) {
            RelayServiceUtils.writeOpenAiError(httpResponse, 503, "模型组无可用成员: " + groupName);
            return;
        }

        long startTime = System.currentTimeMillis();
        long deadline = startTime + WALL_CLOCK_BUDGET_MS;
        int maxAttempts = Math.min(group.getMaxAttempts() != null ? group.getMaxAttempts() : 5, 8);
        String lastError = null;
        int attempts = 0;
        int spins = 0;
        int maxSpins = maxAttempts * 4;
        int idx = 0;

        while (attempts < maxAttempts && spins < maxSpins && System.currentTimeMillis() < deadline) {
            spins++;
            ModelGroupRoutingService.Candidate candidate = candidates.get(idx % candidates.size());
            idx++;
            Long modelConfigId = candidate.modelConfig().getId();

            Channel channel;
            try {
                channel = support.channelRouter.selectChannel(candidate.channelModelId(), Set.of(), ctx.userLevel());
            } catch (BusinessException e) {
                lastError = e.getMessage();
                continue;
            }
            if (modelHealthTracker.isInCooldown(channel.getId(), modelConfigId)
                    || support.isChannelRateLimited(channel)) {
                lastError = "成员 " + candidate.modelConfig().getName() + " 暂不可用";
                continue;
            }

            String memberModel = candidate.modelConfig().getName();
            String modifiedBody = support.injectStreamOptions(rewriteModelField(requestBody, memberModel), path);
            attempts++;

            HttpURLConnection conn;
            long attemptTimeoutMs = remainingBudgetMs(deadline);
            try {
                if (support.isGeminiTypeChannel(channel)) {
                    String geminiBody = ProtocolConverter.convertOpenAiToGeminiRequest(modifiedBody);
                    conn = support.createSseConnection(channel, "/v1/models/" + memberModel + ":streamGenerateContent?alt=sse", geminiBody, attemptTimeoutMs);
                } else {
                    conn = support.createSseConnection(channel, path, modifiedBody, attemptTimeoutMs);
                }
            } catch (IOException e) {
                lastError = e.getMessage();
                support.channelHealthTracker.recordFailure(channel.getId(), ChannelHealthTracker.ErrorCategory.fromException(e), e.getMessage());
                recordModelFailure(channel.getId(), modelConfigId, 502);
                continue;
            }

            try {
                int code = conn.getResponseCode();
                if (code != 200) {
                    String errorBody = conn.getErrorStream() != null
                            ? new String(conn.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) : "";
                    lastError = "HTTP " + code + " - " + errorBody;
                    conn.disconnect();
                    support.channelHealthTracker.recordFailure(channel.getId(), ChannelHealthTracker.ErrorCategory.fromStatusCode(code), lastError);
                    if (!FailureClassifier.isSwitchable(code, errorBody)) {
                        RelayServiceUtils.writeOpenAiError(httpResponse, code, "上游返回错误: " + errorBody);
                        return;
                    }
                    recordModelFailure(channel.getId(), modelConfigId, code);
                    continue; // 首字节尚未发出，可以安全切换成员
                }

                // 首字节即将开始写出：本次尝试之后（若确有数据写出）不再允许切换成员
                SseUtils.setSseHeaders(httpResponse);
                RelaySupport.SseStreamResult streamResult = support.streamSseResponseTracked(conn, httpResponse, null);
                conn.disconnect();

                if (!streamResult.bytesWritten()) {
                    // 上游返回 200 但立即 EOF，客户端从未收到任何数据：视为失败，仍可安全切换成员
                    lastError = "上游返回空响应（HTTP 200 但无数据流）";
                    support.channelHealthTracker.recordFailure(channel.getId(), ChannelHealthTracker.ErrorCategory.CONNECTION_ERROR, lastError);
                    recordModelFailure(channel.getId(), modelConfigId, 502);
                    continue;
                }

                long duration = System.currentTimeMillis() - startTime;
                RelayServiceUtils.UsageInfo usage = RelayServiceUtils.parseOpenAiStreamUsage(support.objectMapper, streamResult.lastUsageData());
                recordGroupStreamUsage(ctx.token(), channel, group, memberModel, usage, duration, httpRequest, path);
                support.channelHealthTracker.recordSuccess(channel.getId());
                modelHealthTracker.recordSuccess(channel.getId(), modelConfigId);
                return;
            } catch (Exception e) {
                conn.disconnect();
                lastError = e.getMessage();
                log.error("模型组 {} 成员 {} (渠道 {}) 流式请求异常: {}", groupName, memberModel, channel.getId(), e.getMessage());
                support.channelHealthTracker.recordFailure(channel.getId(), ChannelHealthTracker.ErrorCategory.fromException(e), e.getMessage());
                if (httpResponse.isCommitted()) {
                    // 已经开始向客户端写出数据：不能再切换成员，只能在协议层补写 SSE error 事件后结束
                    SseUtils.writeSseErrorEvent(httpResponse, "模型组 " + groupName + " 流式响应中断: " + lastError);
                    return;
                }
                recordModelFailure(channel.getId(), modelConfigId, 502);
            }
        }
        if (!httpResponse.isCommitted()) {
            RelayServiceUtils.writeOpenAiError(httpResponse, 502, "模型组所有成员均不可用: " + lastError);
        }
    }

    private void recordGroupStreamUsage(Token token, Channel channel, ModelGroup group, String actualModel,
                                        RelayServiceUtils.UsageInfo usage, long duration,
                                        HttpServletRequest httpRequest, String path) {
        BigDecimal creditCost = billingService.calculateTextCreditCost(
                group, usage.promptTokens(), usage.completionTokens(), usage.cachedTokens());
        UsageLog usageLog = UsageLog.builder()
                .tokenId(token.getId()).channelId(channel.getId())
                .model(group.getName()).actualModel(actualModel)
                .promptTokens(usage.promptTokens()).completionTokens(usage.completionTokens())
                .totalTokens(usage.promptTokens() + usage.completionTokens())
                .promptTokensCacheHit(usage.cachedTokens())
                .cachedTokensCacheCreation(0)
                .cachedTokensCacheRead(usage.cachedTokens())
                .creditCost(creditCost).ip(support.getClientIp(httpRequest)).duration(duration)
                .requestPath(path).build();
        usageLogService.recordUsageAndQuotas(usageLog, token.getId(), channel.getId(),
                usage.promptTokens() + usage.completionTokens(), token.getUserId());
    }

    // ==================== 图片 ====================

    public String relayImageRequest(String tokenKey, String path, String requestBody, String groupName,
                                    HttpServletRequest httpRequest) {
        ModelGroup group = requireGroup(groupName, "image");
        RelaySupport.RelayContext ctx = support.prepareGroupContext(tokenKey, groupName);
        checkGroupAdminOnly(group, "admin".equals(ctx.user().getRole()));
        RelaySupport.MediaParams params = support.parseMediaParams(requestBody);
        BigDecimal creditCost = billingService.calculateImageCreditCost(group, params.size(), params.n());
        RelaySupport.MediaCharge charge = support.chargeMediaCredits(ctx, creditCost);

        boolean isAdmin = "admin".equals(ctx.user().getRole());
        List<ModelGroupRoutingService.Candidate> candidates = routingService.resolveOrderedCandidates(group, isAdmin);
        if (candidates.isEmpty()) {
            support.refundMediaCharge(ctx, charge);
            throw new BusinessException(503, "模型组无可用成员: " + groupName);
        }

        MediaAttemptResult result;
        try {
            result = attemptMedia(ctx, group, candidates, path, requestBody);
        } catch (RuntimeException e) {
            support.refundMediaCharge(ctx, charge);
            throw e;
        }

        int actualCount = countReturnedImages(result.response());
        int requestedN = support.extractRequestedCount(requestBody);
        int billedCount = Math.min(Math.max(actualCount, 0), requestedN);
        BigDecimal unitPrice = billingService.resolveImageUnitPrice(group, support.extractMediaSize(requestBody));
        BigDecimal actualCost = unitPrice.multiply(BigDecimal.valueOf(billedCount));
        BigDecimal finalCost = support.settleMediaCharge(ctx, charge, actualCost);

        long duration = System.currentTimeMillis() - result.startTime();
        recordGroupPrepaidUsage(ctx.token(), result.channel(), group, result.actualModel(),
                new RelaySupport.MediaCharge(finalCost, charge.deducted()), duration, httpRequest, path);
        return result.response();
    }

    private int countReturnedImages(String response) {
        try {
            JsonNode json = support.objectMapper.readTree(response);
            JsonNode data = json.get("data");
            return data != null && data.isArray() ? data.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== 音频（语音合成） ====================

    public void relayAudioSpeech(String tokenKey, String requestBody, String groupName,
                                 HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException {
        ModelGroup group = requireGroup(groupName, "audio");
        RelaySupport.RelayContext ctx = support.prepareGroupContext(tokenKey, groupName);
        checkGroupAdminOnly(group, "admin".equals(ctx.user().getRole()));

        JsonNode body = support.objectMapper.readTree(requestBody);
        if (!body.isObject()) {
            throw new BusinessException(400, "请求体必须是 JSON 对象");
        }
        JsonNode inputNode = body.get("input");
        if (inputNode == null || !inputNode.isTextual() || inputNode.asText().isEmpty()) {
            throw new BusinessException(400, "语音合成请求缺少有效的 input 文本参数");
        }
        String quality = body.hasNonNull("quality") && body.get("quality").isTextual() ? body.get("quality").asText() : null;
        if (quality != null && UsageLogService.resolveAudioTier(quality) == null) {
            throw new BusinessException(400, "不支持的音频音质参数 (quality): " + quality);
        }
        double speed = body.hasNonNull("speed") ? body.get("speed").asDouble() : 1.0;
        int estimatedSeconds = Math.max(1, (int) Math.ceil(
                inputNode.asText().codePointCount(0, inputNode.asText().length()) / (14.0 * speed)));
        BigDecimal estimatedCost = billingService.calculateAudioCreditCost(group, quality, estimatedSeconds);
        RelaySupport.MediaCharge charge = support.chargeMediaCredits(ctx, estimatedCost);

        ObjectNode upstreamBody = ((ObjectNode) body).deepCopy();
        upstreamBody.remove("quality");
        upstreamBody.remove("duration");
        upstreamBody.remove("seconds");

        boolean isAdmin = "admin".equals(ctx.user().getRole());
        List<ModelGroupRoutingService.Candidate> candidates = routingService.resolveOrderedCandidates(group, isAdmin);
        if (candidates.isEmpty()) {
            support.refundMediaCharge(ctx, charge);
            throw new BusinessException(503, "模型组无可用成员: " + groupName);
        }

        String baseSpeechBody = support.objectMapper.writeValueAsString(upstreamBody);
        MediaBinaryAttemptResult result;
        try {
            result = attemptBinaryMedia(ctx, group, candidates, "/v1/audio/speech", baseSpeechBody);
        } catch (RuntimeException e) {
            support.refundMediaCharge(ctx, charge);
            throw e;
        }

        byte[] audio = result.binary().body();
        String responseFormat = body.hasNonNull("response_format") ? body.get("response_format").asText() : "mp3";
        double measured = "pcm".equalsIgnoreCase(responseFormat)
                ? com.aiconnecting.common.AudioDurationUtil.pcmSeconds(audio)
                : com.aiconnecting.common.AudioDurationUtil.measure(audio);
        if (measured <= 0) {
            boolean refunded = support.refundMediaCharge(ctx, charge);
            throw new BusinessException(400, "生成的音频无法测量有效时长" + (refunded ? "，预扣积分已退回" : "，退回预扣积分失败，已记录待人工补偿"));
        }
        int actualSeconds = Math.max(1, (int) Math.ceil(measured));
        BigDecimal actualCost = billingService.calculateAudioCreditCost(group, quality, actualSeconds);
        BigDecimal finalCost = support.settleMediaCharge(ctx, charge, actualCost);

        long duration = System.currentTimeMillis() - result.startTime();
        recordGroupPrepaidUsage(ctx.token(), result.channel(), group, result.actualModel(),
                new RelaySupport.MediaCharge(finalCost, charge.deducted()), duration, httpRequest, "/v1/audio/speech");

        String contentType = result.binary().contentType();
        httpResponse.setStatus(200);
        httpResponse.setContentType(contentType != null ? contentType : "application/octet-stream");
        httpResponse.setContentLength(audio.length);
        httpResponse.getOutputStream().write(audio);
        httpResponse.getOutputStream().flush();
    }

    // ==================== 音频（转写/翻译，仅供故障转移组场景复用） ====================

    /**
     * 音频转写/翻译的故障转移组回退：仅供单模型 fallback_group_id 场景复用，不支持客户端直接以
     * 组名调用转写端点（转写涉及重建 multipart 请求体，直接组名调用暂不在本次范围内）。
     * 上传音频的真实时长已由调用方测量完毕，与切换到哪个成员无关，因此按组价一次性预扣，
     * 成员切换只影响转发渠道与表单 model 字段，不重新测量/结算。
     */
    public ResponseEntity<byte[]> relayAudioTranscriptionWithContext(RelaySupport.RelayContext ctx, ModelGroup group,
                                                                      String path, byte[] fileBytes, String fileName,
                                                                      String contentType, MultiValueMap<String, String> otherFormFields,
                                                                      boolean rawPassThrough, int seconds, String quality,
                                                                      HttpServletRequest httpRequest) {
        BigDecimal creditCost = billingService.calculateAudioCreditCost(group, quality, seconds);
        RelaySupport.MediaCharge charge = support.chargeMediaCredits(ctx, creditCost);
        boolean isAdmin = "admin".equals(ctx.user().getRole());
        List<ModelGroupRoutingService.Candidate> candidates = routingService.resolveOrderedCandidates(group, isAdmin);
        if (candidates.isEmpty()) {
            support.refundMediaCharge(ctx, charge);
            throw new BusinessException(503, "模型组无可用成员: " + group.getName());
        }

        long startTime = System.currentTimeMillis();
        long deadline = startTime + WALL_CLOCK_BUDGET_MS;
        int maxAttempts = Math.min(group.getMaxAttempts() != null ? group.getMaxAttempts() : 5, 8);
        String lastError = null;
        int attempts = 0;
        int spins = 0;
        int maxSpins = maxAttempts * 4;
        int idx = 0;

        while (attempts < maxAttempts && spins < maxSpins && System.currentTimeMillis() < deadline) {
            spins++;
            ModelGroupRoutingService.Candidate candidate = candidates.get(idx % candidates.size());
            idx++;
            Long modelConfigId = candidate.modelConfig().getId();
            Channel channel;
            try {
                channel = support.channelRouter.selectChannel(candidate.channelModelId(), Set.of(), ctx.userLevel());
            } catch (BusinessException e) {
                lastError = e.getMessage();
                continue;
            }
            if (modelHealthTracker.isInCooldown(channel.getId(), modelConfigId) || support.isChannelRateLimited(channel)) {
                lastError = "成员 " + candidate.modelConfig().getName() + " 暂不可用";
                continue;
            }
            String memberModel = candidate.modelConfig().getName();
            attempts++;
            try {
                okhttp3.MultipartBody.Builder mb = new okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM);
                okhttp3.MediaType fileType = okhttp3.MediaType.parse(contentType != null ? contentType : "application/octet-stream");
                mb.addFormDataPart("file", fileName, okhttp3.RequestBody.create(fileBytes, fileType));
                mb.addFormDataPart("model", memberModel);
                for (var field : otherFormFields.entrySet()) {
                    if ("model".equals(field.getKey())) {
                        continue;
                    }
                    for (String value : field.getValue()) {
                        mb.addFormDataPart(field.getKey(), value);
                    }
                }
                RelaySupport.BinaryResponse response = support.forwardMultipartRequest(channel, path, mb.build());
                if (!rawPassThrough && !isValidTranscriptionJson(response.body())) {
                    throw new BusinessException(502, "上游返回无法解析的转写结果");
                }
                support.channelHealthTracker.recordSuccess(channel.getId());
                modelHealthTracker.recordSuccess(channel.getId(), modelConfigId);
                long duration = System.currentTimeMillis() - startTime;
                recordGroupPrepaidUsage(ctx.token(), channel, group, memberModel, charge, duration, httpRequest, path);
                String responseContentType = response.contentType();
                return ResponseEntity.ok()
                        .header("Content-Type", responseContentType != null ? responseContentType : "application/json")
                        .body(response.body());
            } catch (BusinessException e) {
                lastError = e.getMessage();
                if (isFastFail(e)) {
                    support.refundMediaCharge(ctx, charge);
                    throw e;
                }
                support.channelHealthTracker.recordFailure(channel.getId(),
                        ChannelHealthTracker.ErrorCategory.fromStatusCode(e.getCode()), e.getMessage());
                recordModelFailure(channel.getId(), modelConfigId, e.getCode());
            }
        }
        support.refundMediaCharge(ctx, charge);
        throw new BusinessException(502, "模型组所有成员均不可用，最后错误: " + lastError);
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

    // ==================== 视频 ====================

    /**
     * 视频组请求：预扣按组价，创建成功后仅落地 video_tasks 行（model 字段写成员真实模型，
     * 供既有轮询/对账逻辑按渠道自身协议查询状态），usage_logs 写组名+实际成员模型；
     * 终态退款/结算复用既有的 {@link VideoTaskSettlementService} 与轮询/对账任务，逻辑对任务来源无感知。
     */
    public String relayVideoRequest(String tokenKey, String path, String requestBody, String groupName,
                                    HttpServletRequest httpRequest) {
        ModelGroup group = requireGroup(groupName, "video");
        RelaySupport.RelayContext ctx = support.prepareGroupContext(tokenKey, groupName);
        checkGroupAdminOnly(group, "admin".equals(ctx.user().getRole()));
        RelaySupport.MediaParams params = support.parseMediaParams(requestBody);
        BigDecimal unitPrice = billingService.resolveVideoUnitPrice(group, params.size());
        BigDecimal creditCost = billingService.calculateVideoCreditCost(group, params.size(), params.durationSeconds());
        RelaySupport.MediaCharge charge = support.chargeMediaCredits(ctx, creditCost);

        boolean isAdmin = "admin".equals(ctx.user().getRole());
        List<ModelGroupRoutingService.Candidate> candidates = routingService.resolveOrderedCandidates(group, isAdmin);
        if (candidates.isEmpty()) {
            support.refundMediaCharge(ctx, charge);
            throw new BusinessException(503, "模型组无可用成员: " + groupName);
        }

        MediaAttemptResult result;
        try {
            result = attemptMedia(ctx, group, candidates, path, requestBody);
        } catch (RuntimeException e) {
            support.refundMediaCharge(ctx, charge);
            throw e;
        }

        JsonNode json;
        try {
            json = support.objectMapper.readTree(result.response());
        } catch (Exception e) {
            support.refundMediaCharge(ctx, charge);
            throw new BusinessException(502, "上游视频响应无效（非 JSON），预扣积分已退回");
        }
        String upstreamVideoId = json != null && json.isObject() ? firstText(json.get("video_id"), json.get("id")) : null;
        if (upstreamVideoId == null) {
            support.refundMediaCharge(ctx, charge);
            throw new BusinessException(502, "上游视频响应缺少有效的任务 id，预扣积分已退回");
        }

        VideoTask saved;
        try {
            saved = videoTaskRepository.save(VideoTask.builder()
                    .upstreamId(upstreamVideoId)
                    .channelId(result.channel().getId())
                    .tokenId(ctx.token().getId())
                    .userId(ctx.token().getUserId())
                    .model(result.actualModel())
                    .prepaidCost(charge.cost())
                    .deducted(charge.deducted())
                    .size(params.size())
                    .durationSeconds(params.durationSeconds())
                    .unitPrice(unitPrice)
                    .settled(false)
                    .build());
        } catch (Exception e) {
            boolean refunded = support.refundMediaCharge(ctx, charge);
            throw new BusinessException(500, "视频任务保存失败" + (refunded ? "，预扣积分已退回" : "，退回预扣积分失败，已记录待人工补偿"));
        }

        ObjectNode responseObject = (ObjectNode) json;
        responseObject.put("id", upstreamVideoId);
        responseObject.put("channel_id", result.channel().getId());

        long duration = System.currentTimeMillis() - result.startTime();
        UsageLog usageLog = UsageLog.builder()
                .tokenId(ctx.token().getId()).channelId(result.channel().getId())
                .model(group.getName()).actualModel(result.actualModel())
                .promptTokens(0).completionTokens(0).totalTokens(0)
                .creditCost(charge.deducted() ? charge.cost() : BigDecimal.ZERO)
                .ip(support.getClientIp(httpRequest)).duration(duration).requestPath(path)
                .build();
        try {
            videoTaskUsageLogService.recordAndLink(saved.getId(), usageLog);
        } catch (Exception e) {
            log.error("模型组视频使用日志写入/关联失败（积分已扣减，保持扣减，不影响响应）: group={}, cost={}", groupName, charge.cost(), e);
        }
        try {
            return support.objectMapper.writeValueAsString(json);
        } catch (Exception e) {
            return result.response();
        }
    }

    private String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && node.isTextual() && !node.asText().isBlank()) {
                return node.asText();
            }
        }
        return null;
    }

    private void recordGroupPrepaidUsage(Token token, Channel channel, ModelGroup group, String actualModel,
                                         RelaySupport.MediaCharge charge, long duration,
                                         HttpServletRequest httpRequest, String path) {
        UsageLog usageLog = UsageLog.builder()
                .tokenId(token.getId()).channelId(channel.getId())
                .model(group.getName()).actualModel(actualModel)
                .promptTokens(0).completionTokens(0).totalTokens(0)
                .creditCost(charge.deducted() ? charge.cost() : BigDecimal.ZERO)
                .ip(support.getClientIp(httpRequest)).duration(duration).requestPath(path)
                .build();
        usageLogService.recordPrepaidUsage(usageLog);
    }

    // ==================== 媒体请求的通用成员切换骨架 ====================

    private record MediaAttemptResult(String response, Channel channel, String actualModel, long startTime) {}
    private record MediaBinaryAttemptResult(RelaySupport.BinaryResponse binary, Channel channel, String actualModel, long startTime) {}

    /**
     * 媒体（图片/视频）请求的成员切换骨架：预扣已在调用方完成，本方法只负责选成员+选渠道+转发，
     * 失败时按快速失败/切换分类处理；调用方负责失败时退款
     */
    private MediaAttemptResult attemptMedia(RelaySupport.RelayContext ctx, ModelGroup group,
                                            List<ModelGroupRoutingService.Candidate> candidates,
                                            String path, String requestBody) {
        long startTime = System.currentTimeMillis();
        long deadline = startTime + WALL_CLOCK_BUDGET_MS;
        int maxAttempts = Math.min(group.getMaxAttempts() != null ? group.getMaxAttempts() : 5, 8);
        String lastError = null;
        int attempts = 0;
        int spins = 0;
        int maxSpins = maxAttempts * 4;
        int idx = 0;

        while (attempts < maxAttempts && spins < maxSpins && System.currentTimeMillis() < deadline) {
            spins++;
            ModelGroupRoutingService.Candidate candidate = candidates.get(idx % candidates.size());
            idx++;
            Long modelConfigId = candidate.modelConfig().getId();
            Channel channel;
            try {
                channel = support.channelRouter.selectChannel(candidate.channelModelId(), Set.of(), ctx.userLevel());
            } catch (BusinessException e) {
                lastError = e.getMessage();
                continue;
            }
            if (modelHealthTracker.isInCooldown(channel.getId(), modelConfigId) || support.isChannelRateLimited(channel)) {
                lastError = "成员 " + candidate.modelConfig().getName() + " 暂不可用";
                continue;
            }
            String memberModel = candidate.modelConfig().getName();
            attempts++;
            try {
                String upstreamBody = "image".equals(group.getType())
                        ? support.prepareImageRequestBody(channel, rewriteModelField(requestBody, memberModel))
                        : rewriteModelField(requestBody, memberModel);
                String response = support.forwardRequest(channel, path, upstreamBody, remainingBudgetMs(deadline));
                support.channelHealthTracker.recordSuccess(channel.getId());
                modelHealthTracker.recordSuccess(channel.getId(), modelConfigId);
                return new MediaAttemptResult(response, channel, memberModel, startTime);
            } catch (BusinessException e) {
                lastError = e.getMessage();
                log.error("模型组 {} 成员 {} (渠道 {}) 媒体请求失败 (尝试 {}/{}): {}",
                        group.getName(), memberModel, channel.getId(), attempts, maxAttempts, e.getMessage());
                if (isFastFail(e)) {
                    throw e;
                }
                support.channelHealthTracker.recordFailure(channel.getId(),
                        ChannelHealthTracker.ErrorCategory.fromStatusCode(e.getCode()), e.getMessage());
                recordModelFailure(channel.getId(), modelConfigId, e.getCode());
            }
        }
        throw new BusinessException(502, "模型组所有成员均不可用，最后错误: " + lastError);
    }

    private MediaBinaryAttemptResult attemptBinaryMedia(RelaySupport.RelayContext ctx, ModelGroup group,
                                                         List<ModelGroupRoutingService.Candidate> candidates,
                                                         String path, String baseBodyJson) {
        long startTime = System.currentTimeMillis();
        long deadline = startTime + WALL_CLOCK_BUDGET_MS;
        int maxAttempts = Math.min(group.getMaxAttempts() != null ? group.getMaxAttempts() : 5, 8);
        String lastError = null;
        int attempts = 0;
        int spins = 0;
        int maxSpins = maxAttempts * 4;
        int idx = 0;

        while (attempts < maxAttempts && spins < maxSpins && System.currentTimeMillis() < deadline) {
            spins++;
            ModelGroupRoutingService.Candidate candidate = candidates.get(idx % candidates.size());
            idx++;
            Long modelConfigId = candidate.modelConfig().getId();
            Channel channel;
            try {
                channel = support.channelRouter.selectChannel(candidate.channelModelId(), Set.of(), ctx.userLevel());
            } catch (BusinessException e) {
                lastError = e.getMessage();
                continue;
            }
            if (modelHealthTracker.isInCooldown(channel.getId(), modelConfigId) || support.isChannelRateLimited(channel)) {
                lastError = "成员 " + candidate.modelConfig().getName() + " 暂不可用";
                continue;
            }
            String memberModel = candidate.modelConfig().getName();
            attempts++;
            try {
                String upstreamBody = rewriteModelField(baseBodyJson, memberModel);
                RelaySupport.BinaryResponse response = support.forwardBinaryRequest(channel, path, upstreamBody, remainingBudgetMs(deadline));
                support.channelHealthTracker.recordSuccess(channel.getId());
                modelHealthTracker.recordSuccess(channel.getId(), modelConfigId);
                return new MediaBinaryAttemptResult(response, channel, memberModel, startTime);
            } catch (BusinessException e) {
                lastError = e.getMessage();
                if (isFastFail(e)) {
                    throw e;
                }
                support.channelHealthTracker.recordFailure(channel.getId(),
                        ChannelHealthTracker.ErrorCategory.fromStatusCode(e.getCode()), e.getMessage());
                recordModelFailure(channel.getId(), modelConfigId, e.getCode());
            }
        }
        throw new BusinessException(502, "模型组所有成员均不可用，最后错误: " + lastError);
    }
}
