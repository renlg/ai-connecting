package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
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
    private final UsageLogService usageLogService;
    private final VideoTaskRepository videoTaskRepository;
    private final VideoTaskUsageLogService videoTaskUsageLogService;
    private final PassthroughRelayService passthroughRelayService;

    /** Field injection keeps the long-standing constructor used by focused unit tests source-compatible. */
    @Autowired(required = false)
    private RelayProtocolAdapter protocolAdapter;

    @Autowired(required = false)
    private FailureLogService failureLogService;

    @Autowired(required = false)
    private ChannelFailureRecorder channelFailureRecorder;

    /** 总耗时预算（毫秒），介于业务约定的 120-150s 之间 */
    private static final long WALL_CLOCK_BUDGET_MS = 130_000L;

    public Optional<ModelGroup> findEnabledGroup(String name) {
        return modelGroupService.findByName(name).filter(g -> Boolean.TRUE.equals(g.getEnabled()));
    }

    private ModelGroup requireGroup(String groupName, String expectedType) {
        ModelGroup group = findEnabledGroup(groupName)
                .orElseThrow(() -> new BusinessException(404, "模型组不存在或未启用: " + groupName,
                        "Model group not found or disabled: " + groupName));
        if (!expectedType.equals(group.getType())) {
            throw new BusinessException(400, "模型组 " + groupName + " 类型为 " + group.getType()
                    + "，不能用于 " + expectedType + " 端点",
                    "Model group " + groupName + " of type " + group.getType()
                            + " cannot be used on the " + expectedType + " endpoint");
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
            throw new BusinessException(403, "该模型组仅限管理员使用: " + group.getName(),
                    "This model group is admin-only: " + group.getName());
        }
    }

    /**
     * 用户等级不满足模型组 supportedLevels 时必须表现得与"模型组不存在"完全一致（同状态码/同错误体），
     * 不能暴露组的存在性，故复用 {@link #requireGroup} 的未找到错误文案，而非 403
     */
    private void checkGroupLevel(ModelGroup group, Integer userLevel, boolean isAdmin) {
        if (!isAdmin && !com.aiconnecting.common.LevelUtils.isAllowed(group.getSupportedLevels(), userLevel)) {
            throw new BusinessException(404, "模型组不存在或未启用: " + group.getName(),
                    "Model group not found or disabled: " + group.getName());
        }
    }

    /** 按 id 解析故障转移组（fallback_group_id），类型不符或未启用时返回空，调用方应放弃转入组、直接抛出原错误 */
    public Optional<ModelGroup> resolveFallbackGroup(Long groupId, String expectedType, boolean isAdmin, Integer userLevel) {
        if (groupId == null) {
            return Optional.empty();
        }
        Optional<ModelGroup> resolved = modelGroupService.findById(groupId)
                .filter(g -> Boolean.TRUE.equals(g.getEnabled()))
                .filter(g -> expectedType.equals(g.getType()));
        resolved.ifPresent(group -> checkGroupAdminOnly(group, isAdmin));
        resolved.ifPresent(group -> checkGroupLevel(group, userLevel, isAdmin));
        return resolved;
    }

    /**
     * 剩余总耗时预算（毫秒），作为本次尝试的读超时上限：固定 120s 读超时叠加多次尝试会让总耗时
     * 远超 {@link #WALL_CLOCK_BUDGET_MS}，因此每次尝试的超时必须收窄到"不超过剩余预算"
     */
    private long remainingBudgetMs(long deadline) {
        return Math.max(0L, deadline - System.currentTimeMillis());
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

    private void recordFailure(Channel channel, Long modelConfigId, BusinessException error) {
        recordChannelFailure(channel, modelConfigId, null, error);
        if (error.isUpstreamResponse() && support.riskManagerProvider != null) {
            RiskManagerService riskManager = support.riskManagerProvider.getIfAvailable();
            if (riskManager != null) {
                String modelIdStr = modelConfigId != null ? String.valueOf(modelConfigId) : null;
                riskManager.recordFailureEventByModelId(channel.getId(), modelIdStr, error.getCode());
            }
        }
        FailureClassifier.Classification classification = FailureClassifier.classifyForGroup(error);
        if (channelFailureRecorder != null) {
            String rawBody = error.getUpstreamResponseBody();
            String detail = rawBody != null
                    ? "Upstream API error: " + error.getCode() + " - " + rawBody
                    : error.getMessage();
            channelFailureRecorder.record(channel.getId(),
                    modelConfigId != null ? String.valueOf(modelConfigId) : null,
                    error.getCode(), detail);
        }
    }

    private void recordChannelFailure(Channel channel, Long memberId, String channelModel,
                                      BusinessException error) {
        if (failureLogService == null || channel == null) return;
        HttpServletRequest request = null;
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes attributes) {
            request = attributes.getRequest();
        }
        failureLogService.recordChannelFailure(request, channel.getId(), memberId, channelModel, error);
    }

    private BusinessException upstreamFailure(int code, String body, Long retryAfterSeconds) {
        return BusinessException.upstream(code, "上游 API 错误: " + body,
                "Upstream API error: " + body, body, retryAfterSeconds);
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 500) {
            return value;
        }
        return value.substring(0, 500) + "...";
    }

    /** 包裹重试耗尽后的汇总错误，保留原始异常的 upstreamResponse 标记，避免真实上游错误被误判为本地错误而向终端用户泄露细节 */
    private BusinessException wrapFailure(BusinessException cause, String message) {
        boolean upstream = cause.isUpstreamResponse() || FailureClassifier.isSwitchable(cause);
        return new BusinessException(cause.getCode(), message,
                "All members of the model group are unavailable, please try again later",
                cause, cause.getUpstreamResponseBody(),
                cause.getRetryAfterSeconds(), upstream);
    }

    /**
     * 成员切换耗尽后的终端用户可见消息：绝不能包含成员模型名 / 渠道 id（这些是内部实现细节，
     * 只应出现在日志里），因此固定为不带 lastError 细节的通用文案；真实的失败细节交给
     * 调用处的 log.error/log.warn 记录
     */
    private String exhaustedGroupMessage(String groupName) {
        return "模型组 " + groupName + " 所有成员均不可用，请稍后重试";
    }

    private String exhaustedGroupEnglishMessage(String groupName) {
        return "All members of model group " + groupName + " are unavailable, please try again later";
    }

    // ==================== 文本（非流式） ====================

    public String relayRequest(String tokenKey, String path, String requestBody, String groupName,
                               HttpServletRequest httpRequest) {
        return relayRequest(tokenKey, path, requestBody, groupName, httpRequest, null);
    }

    public String relayRequest(String tokenKey, String path, String requestBody, String groupName,
                               HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        ModelGroup group = requireGroup(groupName, "text");
        RelaySupport.RelayContext ctx = support.prepareGroupContext(tokenKey, groupName);
        boolean isAdmin = "admin".equals(ctx.user().getRole());
        checkGroupAdminOnly(group, isAdmin);
        checkGroupLevel(group, ctx.userLevel(), isAdmin);
        UnifiedRelayRequest request = adapter().adaptRequest(
                RelayProtocol.OPENAI, path, requestBody, groupName);
        return relayRequestWithContext(ctx, group, request, httpRequest, httpResponse);
    }

    public String relayRequest(String tokenKey, UnifiedRelayRequest request,
                               HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String groupName = request.model();
        ModelGroup group = requireGroup(groupName, "text");
        RelaySupport.RelayContext ctx = support.prepareGroupContext(tokenKey, groupName);
        checkGroupAdminOnly(group, "admin".equals(ctx.user().getRole()));
        return relayRequestWithContext(ctx, group, request, httpRequest, httpResponse);
    }

    /**
     * 供单模型故障转移组（fallback_group_id）复用：Token/账号/余额/权限已在原模型请求时对原模型名
     * 校验过，此处直接使用调用方已构建的 ctx，不再重复校验，也不要求组名本身在 Token 白名单中
     * （即便原模型可用而组名未被显式授权，故障转移仍应生效）
     */
    String relayRequestWithContext(RelaySupport.RelayContext ctx, ModelGroup group, String path,
                                   String requestBody, HttpServletRequest httpRequest) {
        return relayRequestWithContext(ctx, group, path, requestBody, httpRequest, null);
    }

    String relayRequestWithContext(RelaySupport.RelayContext ctx, ModelGroup group, String path,
                                   String requestBody, HttpServletRequest httpRequest,
                                   HttpServletResponse httpResponse) {
        UnifiedRelayRequest request = adapter().adaptRequest(
                RelayProtocol.OPENAI, path, requestBody, group.getName());
        return relayRequestWithContext(ctx, group, request, httpRequest, httpResponse);
    }

    private String relayRequestWithContext(RelaySupport.RelayContext ctx, ModelGroup group,
                                           UnifiedRelayRequest request,
                                           HttpServletRequest httpRequest,
                                           HttpServletResponse httpResponse) {
        String path = request.path();
        String requestBody = request.rawBody();
        String groupName = group.getName();
        boolean isAdmin = "admin".equals(ctx.user().getRole());
        checkGroupAdminOnly(group, isAdmin);
        checkGroupLevel(group, ctx.userLevel(), isAdmin);
        List<ModelGroupRoutingService.Candidate> candidates = routingService.resolveOrderedCandidates(
                group, isAdmin, ctx.userLevel());
        if (candidates.isEmpty()) {
            throw new BusinessException(503, "模型组无可用成员: " + groupName,
                    "No available members in model group: " + groupName);
        }
        long startTime = System.currentTimeMillis();
        long deadline = startTime + WALL_CLOCK_BUDGET_MS;
        int maxAttempts = Math.min(group.getMaxAttempts() != null ? group.getMaxAttempts() : 5, 8);
        BusinessException lastFailure = null;
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
                lastFailure = null;
                continue;
            }
            if (support.isChannelRateLimited(channel)) {
                log.debug("模型组 {} 成员 {} (渠道 {}) 触发限流，跳过", groupName, candidate.modelConfig().getName(), channel.getId());
                continue;
            }

            String memberModel = candidate.modelConfig().getName();
            attempts++;
            try {
                long attemptTimeoutMs = remainingBudgetMs(deadline);
                if (attemptTimeoutMs <= 0) break;
                if (isCustomChannel(channel)) {
                    if (httpResponse == null) {
                        throw new BusinessException(500, "透传模型组请求缺少响应上下文",
                                "Passthrough model-group request is missing its response context");
                    }
                    String upstreamModel = passthroughRelayService.mappedModel(channel, memberModel);
                    FailureLogContext.setChannelModel(upstreamModel);
                    String passthroughBody = request.protocol() == RelayProtocol.GEMINI
                            ? requestBody : passthroughRelayService.rewriteTopLevelModelVerbatim(
                                    requestBody, upstreamModel);
                    String passthroughPath = request.protocol() == RelayProtocol.GEMINI
                            ? adapter().upstreamPath(RelayProtocol.GEMINI, upstreamModel, request.stream())
                            : null;
                    Request upstreamRequest = passthroughRelayService.buildPassthroughRequest(
                            channel, httpRequest, passthroughBody, passthroughPath);
                    Response upstreamResponse;
                    try {
                        upstreamResponse = passthroughRelayService.executePassthrough(upstreamRequest, attemptTimeoutMs);
                    } catch (IOException connectionFailure) {
                        throw new PassthroughConnectionException(connectionFailure);
                    }
                    requireSuccessfulPassthrough(upstreamResponse);
                    if (copyGroupPassthroughResponse(ctx, group, channel, modelConfigId, upstreamModel,
                            upstreamResponse, startTime, httpRequest, httpResponse, path)) {
                        return null;
                    }
                    lastFailure = new BusinessException(502, "渠道连接失败", "Upstream connection failed");
                    continue;
                }

                RelayProtocol upstreamProtocol = adapter().channelProtocol(channel);
                String upstreamBody = adapter().toUpstreamBody(request, memberModel, upstreamProtocol);
                String upstreamResponse = switch (upstreamProtocol) {
                    case OPENAI -> support.forwardRequest(channel,
                            request.protocol() == RelayProtocol.OPENAI ? request.path()
                                    : adapter().upstreamPath(upstreamProtocol, memberModel, false),
                            upstreamBody, attemptTimeoutMs);
                    case CLAUDE -> support.forwardClaudeRequest(channel, upstreamBody, attemptTimeoutMs);
                    case GEMINI -> support.forwardGeminiRequest(channel, upstreamBody, attemptTimeoutMs);
                };
                String response = adapter().fromUpstreamResponse(
                        upstreamResponse, upstreamProtocol, request.protocol());
                long duration = System.currentTimeMillis() - startTime;
                recordGroupTextUsage(ctx.token(), channel, group, memberModel, response,
                        request.protocol(), duration, httpRequest, path);

                return response;
            } catch (PassthroughConnectionException e) {
                lastFailure = new BusinessException(502, "渠道连接失败: " + e.getCause().getMessage(),
                        "Upstream connection failed: " + e.getCause().getMessage(), e.getCause());
                recordChannelFailure(channel, modelConfigId, memberModel, lastFailure);
                log.warn("模型组 {} 透传成员 {} (渠道 {}) 连接失败 (尝试 {}/{}): {}",
                        groupName, memberModel, channel.getId(), attempts, maxAttempts, e.getCause().getMessage());
                if (remainingBudgetMs(deadline) <= 0) break;
            } catch (BusinessException e) {
                lastFailure = e;
                log.error("模型组 {} 成员 {} (渠道 {}) 请求失败 (尝试 {}/{}): {}",
                        groupName, memberModel, channel.getId(), attempts, maxAttempts, e.getMessage());
                recordFailure(channel, modelConfigId, e);
                if (remainingBudgetMs(deadline) <= 0) break;
            }
        }
        throw lastFailure != null
                ? wrapFailure(lastFailure, exhaustedGroupMessage(groupName))
                : new BusinessException(502, exhaustedGroupMessage(groupName), exhaustedGroupEnglishMessage(groupName));
    }

    private boolean isCustomChannel(Channel channel) {
        return "custom".equalsIgnoreCase(channel.getType());
    }

    private void requireSuccessfulPassthrough(Response response) {
        if (response.isSuccessful()) {
            return;
        }
        int code = response.code();
        String body = "";
        try (response) {
            if (response.body() != null) {
                body = response.body().string();
            }
        } catch (IOException e) {
            log.warn("读取模型组透传错误响应失败: {}", e.getMessage());
        }
        FailureLogContext.setChannelError(code, body);
        throw upstreamFailure(code, body, null);
    }

    private boolean copyGroupPassthroughResponse(RelaySupport.RelayContext ctx, ModelGroup group,
                                                 Channel channel, Long modelConfigId, String upstreamModel,
                                                 Response upstreamResponse, long startTime,
                                                 HttpServletRequest httpRequest,
                                                 HttpServletResponse httpResponse, String path) {
        try (upstreamResponse) {
            PassthroughRelayService.UsageObserver observer = new PassthroughRelayService.UsageObserver(
                    upstreamResponse.header("Content-Type"), support.objectMapper);
            boolean completed = false;
            try {
                passthroughRelayService.copyUpstreamResponse(upstreamResponse, httpResponse, observer);
                completed = true;
            } catch (IOException streamFailure) {
                if (!observer.bytesObserved() && !httpResponse.isCommitted()) {
                    httpResponse.reset();
                    return false;
                }
                log.warn("模型组透传响应流中断，已输出字节后不再切换: group={}, channel={}, path={}, error={}",
                        group.getName(), channel.getId(), path, streamFailure.getMessage());
            } finally {
                if (completed || observer.bytesObserved()) {
                    PassthroughRelayService.Usage usage = observer.finish();
                    try {
                        recordGroupPassthroughUsage(ctx.token(), channel, group, upstreamModel, usage,
                                System.currentTimeMillis() - startTime, httpRequest, path);
                    } catch (Exception billingError) {
                        log.error("模型组透传用量记录失败（响应不受影响）: group={}, channel={}, model={}",
                                group.getName(), channel.getId(), upstreamModel, billingError);
                    }
                }
            }
            return true;
        }
    }

    private void recordGroupPassthroughUsage(Token token, Channel channel, ModelGroup group,
                                             String actualModel, PassthroughRelayService.Usage usage,
                                             long duration, HttpServletRequest httpRequest, String path) {
        BigDecimal creditCost = billingService.calculateTextCreditCost(
                group, usage.promptTokens(), usage.completionTokens(), usage.cachedTokens());
        UsageLog usageLog = UsageLog.builder()
                .tokenId(token.getId()).channelId(channel.getId())
                .model(group.getName()).actualModel(actualModel)
                .promptTokens(usage.promptTokens()).completionTokens(usage.completionTokens())
                .totalTokens(usage.totalTokens()).promptTokensCacheHit(usage.cachedTokens())
                .cachedTokensCacheCreation(usage.cacheCreationTokens())
                .cachedTokensCacheRead(usage.cacheReadTokens())
                .creditCost(creditCost).ip(support.getClientIp(httpRequest)).duration(duration)
                .requestPath(path).build();
        usageLogService.recordUsageAndQuotas(usageLog, token.getId(), channel.getId(),
                usage.totalTokens(), token.getUserId());
    }

    private static final class PassthroughConnectionException extends RuntimeException {
        private PassthroughConnectionException(IOException cause) {
            super(cause);
        }

        @Override
        public synchronized IOException getCause() {
            return (IOException) super.getCause();
        }
    }

    private void recordGroupTextUsage(Token token, Channel channel, ModelGroup group, String actualModel,
                                      String response, RelayProtocol protocol, long duration,
                                      HttpServletRequest httpRequest, String path) {
        RelayServiceUtils.UsageInfo parsed = adapter().parseUsage(protocol, response);
        int promptTokens = parsed.promptTokens();
        int completionTokens = parsed.completionTokens();
        int totalTokens = parsed.totalTokens();
        int cachedTokens = parsed.cachedTokens();
        int cacheCreationTokens = parsed.cacheCreationTokens();
        int cacheReadTokens = parsed.cacheReadTokens();
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

    private RelayProtocolAdapter adapter() {
        return protocolAdapter != null ? protocolAdapter : new RelayProtocolAdapter(support.objectMapper);
    }

    // ==================== 文本（流式 SSE） ====================

    /**
     * 流式模型组请求：仅在向客户端写出首字节前允许切换成员；一旦响应已提交（isCommitted），
     * 后续失败只能在协议层写 SSE error 事件并结束，绝不拼接另一个成员的输出。
     */
    public void relayStreamRequest(String tokenKey, String path, String requestBody, String groupName,
                                   HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException {
        UnifiedRelayRequest request = adapter().adaptRequest(
                RelayProtocol.OPENAI, path, requestBody, groupName);
        relayStreamRequest(tokenKey, request, httpRequest, httpResponse);
    }

    public void relayStreamRequest(String tokenKey, UnifiedRelayRequest request,
                                   HttpServletRequest httpRequest,
                                   HttpServletResponse httpResponse) throws IOException {
        String groupName = request.model();
        ModelGroup group = requireGroup(groupName, "text");
        RelaySupport.RelayContext ctx = support.prepareGroupContext(tokenKey, groupName);
        relayStreamRequestWithContext(ctx, group, request, httpRequest, httpResponse);
    }

    void relayStreamRequestWithContext(RelaySupport.RelayContext ctx, ModelGroup group, String path,
                                       String requestBody, HttpServletRequest httpRequest,
                                       HttpServletResponse httpResponse) throws IOException {
        UnifiedRelayRequest request = adapter().adaptRequest(
                RelayProtocol.OPENAI, path, requestBody, group.getName());
        relayStreamRequestWithContext(ctx, group, request, httpRequest, httpResponse);
    }

    private void relayStreamRequestWithContext(RelaySupport.RelayContext ctx, ModelGroup group,
                                               UnifiedRelayRequest request,
                                               HttpServletRequest httpRequest,
                                               HttpServletResponse httpResponse) throws IOException {
        String path = request.path();
        String requestBody = request.rawBody();
        String groupName = group.getName();
        boolean isAdmin = "admin".equals(ctx.user().getRole());
        checkGroupAdminOnly(group, isAdmin);
        checkGroupLevel(group, ctx.userLevel(), isAdmin);
        List<ModelGroupRoutingService.Candidate> candidates = routingService.resolveOrderedCandidates(
                group, isAdmin, ctx.userLevel());
        if (candidates.isEmpty()) {
            adapter().writeError(request.protocol(), httpResponse, 503,
                    SseUtils.clientErrorMessage("模型组无可用成员: " + groupName,
                            "No available members in model group: " + groupName, false), false);
            return;
        }
        long startTime = System.currentTimeMillis();
        long deadline = startTime + WALL_CLOCK_BUDGET_MS;
        int maxAttempts = Math.min(group.getMaxAttempts() != null ? group.getMaxAttempts() : 5, 8);
        String lastError = null;
        int lastStatus = 502;
        boolean lastErrorUpstream = false;
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
                lastErrorUpstream = false;
                continue;
            }
            if (support.isChannelRateLimited(channel)) {
                log.debug("模型组 {} 成员 {} (渠道 {}) 冷却/限流中，跳过", groupName, candidate.modelConfig().getName(), channel.getId());
                if (lastError == null) {
                    lastError = "该成员暂不可用";
                    lastErrorUpstream = false;
                }
                continue;
            }

            String memberModel = candidate.modelConfig().getName();
            attempts++;

            long attemptTimeoutMs = remainingBudgetMs(deadline);
            if (attemptTimeoutMs <= 0) break;

            if (isCustomChannel(channel)) {
                try {
                    String upstreamModel = passthroughRelayService.mappedModel(channel, memberModel);
                    FailureLogContext.setChannelModel(upstreamModel);
                    String passthroughBody = request.protocol() == RelayProtocol.GEMINI
                            ? requestBody : passthroughRelayService.rewriteTopLevelModelVerbatim(
                                    requestBody, upstreamModel);
                    String passthroughPath = request.protocol() == RelayProtocol.GEMINI
                            ? adapter().upstreamPath(RelayProtocol.GEMINI, upstreamModel, true) : null;
                    Request upstreamRequest = passthroughRelayService.buildPassthroughRequest(
                            channel, httpRequest, passthroughBody, passthroughPath);
                    Response upstreamResponse = passthroughRelayService.executePassthrough(
                            upstreamRequest, attemptTimeoutMs);
                    requireSuccessfulPassthrough(upstreamResponse);
                    if (copyGroupPassthroughResponse(ctx, group, channel, modelConfigId, upstreamModel,
                            upstreamResponse, startTime, httpRequest, httpResponse, path)) {
                        return;
                    }
                    lastError = "上游响应在首字节前中断";
                    lastErrorUpstream = true;
                    continue;
                } catch (BusinessException memberError) {
                    lastError = memberError.getMessage();
                    lastStatus = memberError.getCode();
                    lastErrorUpstream = memberError.isUpstreamResponse();
                    log.warn("模型组 {} 透传成员 {} (渠道 {}) 请求失败 (尝试 {}/{}): {}",
                            groupName, memberModel, channel.getId(), attempts, maxAttempts, memberError.getMessage());
                    recordFailure(channel, modelConfigId, memberError);
                    if (remainingBudgetMs(deadline) <= 0) break;
                    continue;
                } catch (IOException connectionFailure) {
                    lastError = connectionFailure.getMessage();
                    lastErrorUpstream = true;
                    recordChannelFailure(channel, modelConfigId, memberModel,
                            new BusinessException(502, "渠道请求失败: " + connectionFailure.getMessage(),
                                    "Channel request failed: " + connectionFailure.getMessage(), connectionFailure));
                    if (remainingBudgetMs(deadline) <= 0) break;
                    continue;
                }
            }

            RelayProtocol upstreamProtocol = adapter().channelProtocol(channel);
            String modifiedBody = adapter().toUpstreamBody(request, memberModel, upstreamProtocol);
            if (upstreamProtocol == RelayProtocol.OPENAI) {
                modifiedBody = support.injectStreamOptions(modifiedBody, "/v1/chat/completions");
            }

            HttpURLConnection conn;
            try {
                conn = support.createSseConnection(channel,
                        upstreamProtocol == RelayProtocol.OPENAI && request.protocol() == RelayProtocol.OPENAI
                                ? request.path() : adapter().upstreamPath(upstreamProtocol, memberModel, true),
                        modifiedBody, attemptTimeoutMs);
            } catch (IOException e) {
                lastError = e.getMessage();
                lastErrorUpstream = true;
                log.warn("模型组 {} 成员 {} (渠道 {}) 建立上游连接失败: {}",
                        groupName, memberModel, channel.getId(), e.getMessage());
                recordFailure(channel, modelConfigId, new BusinessException(502, "渠道请求失败: " + e.getMessage(),
                        "Channel request failed: " + e.getMessage(), e));
                if (remainingBudgetMs(deadline) <= 0) break;
                continue;
            }

            try {
                int code = conn.getResponseCode();
                if (code != 200) {
                    String errorBody = conn.getErrorStream() != null
                            ? new String(conn.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) : "";
                    FailureLogContext.setChannelError(code, errorBody);
                    lastError = "HTTP " + code + " - " + errorBody;
                    lastStatus = code;
                    lastErrorUpstream = true;
                    BusinessException upstreamError = upstreamFailure(code, errorBody,
                            RelaySupport.resolveCooldownSeconds(conn, errorBody));
                    log.warn("模型组 {} 成员 {} (渠道 {}) 上游返回 HTTP {}: {}",
                            groupName, memberModel, channel.getId(), code, truncate(errorBody));
                    conn.disconnect();
                    recordFailure(channel, modelConfigId, upstreamError);
                    if (remainingBudgetMs(deadline) <= 0) break;
                    continue; // 首字节尚未发出，可以安全切换成员
                }

                // 首字节即将开始写出：本次尝试之后（若确有数据写出）不再允许切换成员
                SseUtils.setSseHeaders(httpResponse);
                GroupStreamResult streamResult = streamGroupResponse(
                        conn, httpResponse, request.protocol(), upstreamProtocol, memberModel);
                conn.disconnect();

                if (!streamResult.bytesWritten()) {
                    // 上游返回 200 但立即 EOF，客户端从未收到任何数据：视为失败，仍可安全切换成员
                    lastError = "上游返回空响应（HTTP 200 但无数据流）";
                    lastErrorUpstream = true;
                    recordFailure(channel, modelConfigId, new BusinessException(502, lastError,
                            "Upstream returned an empty response (HTTP 200 with no data stream)",
                            new IOException(lastError)));
                    if (remainingBudgetMs(deadline) <= 0) break;
                    continue;
                }

                long duration = System.currentTimeMillis() - startTime;
                RelayServiceUtils.UsageInfo usage = streamResult.usage();
                recordGroupStreamUsage(ctx.token(), channel, group, memberModel, usage, duration, httpRequest, path);

                return;
            } catch (RelaySupport.SseStreamingException e) {
                conn.disconnect();
                lastError = e.getMessage();
                lastErrorUpstream = true;
                log.error("模型组 {} 成员 {} (渠道 {}) 流式请求异常: {}", groupName, memberModel, channel.getId(), e.getMessage());
                recordFailure(channel, modelConfigId, new BusinessException(502, "渠道请求失败: " + e.getMessage(),
                        "Channel request failed: " + e.getMessage(), e));
                if (e.partialResult().bytesWritten()) {
                    finishBrokenSse(request.protocol(), httpResponse, "模型组流式响应中断，请稍后重试",
                            "Model group streaming response was interrupted, please try again later", true);
                    return;
                }
                if (remainingBudgetMs(deadline) <= 0) break;
            } catch (Exception e) {
                conn.disconnect();
                lastError = e.getMessage();
                lastErrorUpstream = true;
                log.error("模型组 {} 成员 {} (渠道 {}) 流式请求异常: {}", groupName, memberModel, channel.getId(), e.getMessage());
                recordFailure(channel, modelConfigId, new BusinessException(502, "渠道请求失败: " + e.getMessage(),
                        "Channel request failed: " + e.getMessage(), e));
                if (httpResponse.isCommitted()) {
                    finishBrokenSse(request.protocol(), httpResponse, "模型组流式响应中断，请稍后重试",
                            "Model group streaming response was interrupted, please try again later", true);
                    return;
                }
                if (remainingBudgetMs(deadline) <= 0) break;
            }
        }
        if (!httpResponse.isCommitted()) {
            log.error("模型组 {} 流式请求所有成员均不可用，最后错误: {}", groupName, lastError);
            adapter().writeError(request.protocol(), httpResponse, lastStatus,
                    SseUtils.clientErrorMessage(exhaustedGroupMessage(groupName),
                            exhaustedGroupEnglishMessage(groupName), lastErrorUpstream), lastErrorUpstream);
        }
    }

    private record GroupStreamResult(RelayServiceUtils.UsageInfo usage, boolean bytesWritten) {}

    /** Converts complete SSE data events at the protocol boundary and tracks the first client byte. */
    private GroupStreamResult streamGroupResponse(HttpURLConnection conn, HttpServletResponse response,
                                                  RelayProtocol clientProtocol,
                                                  RelayProtocol upstreamProtocol,
                                                  String memberModel) throws IOException {
        RelayProtocolAdapter.StreamState state = adapter().newStreamState(
                memberModel, upstreamProtocol, clientProtocol);
        boolean bytesWritten = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            var writer = response.getWriter();
            List<String> prefix = adapter().streamPrefix(state);
            boolean prefixWritten = prefix.isEmpty();
            boolean sawDataEvent = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (upstreamProtocol == clientProtocol) {
                    writer.write(line);
                    writer.write("\n");
                    writer.flush();
                    bytesWritten = true;
                    if (line.startsWith("data:")) {
                        adapter().convertStreamData(line.substring(5).stripLeading(), state);
                    }
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).stripLeading();
                sawDataEvent = true;
                if (!prefixWritten && !"[DONE]".equals(data)) {
                    for (String event : prefix) writer.write("data: " + event + "\n\n");
                    writer.flush();
                    bytesWritten = true;
                    prefixWritten = true;
                }
                for (String converted : adapter().convertStreamData(data, state)) {
                    writer.write("data: " + converted + "\n\n");
                    writer.flush();
                    bytesWritten = true;
                }
            }
            if (sawDataEvent) {
                if (!prefixWritten) {
                    for (String event : prefix) writer.write("data: " + event + "\n\n");
                    writer.flush();
                    bytesWritten = true;
                }
                for (String event : adapter().streamSuffix(state)) {
                    writer.write("data: " + event + "\n\n");
                    writer.flush();
                    bytesWritten = true;
                }
            }
            return new GroupStreamResult(adapter().streamUsage(state), bytesWritten);
        } catch (IOException e) {
            throw new RelaySupport.SseStreamingException(e,
                    new RelaySupport.SseStreamResult(null, bytesWritten));
        }
    }

    private void finishBrokenSse(RelayProtocol protocol, HttpServletResponse response,
                                 String zhMessage, String enMessage,
                                 boolean isUpstreamError) {
        try {
            adapter().writeSseError(protocol, response, 502, zhMessage, enMessage, isUpstreamError);
            response.getWriter().flush();
        } catch (Exception ignored) {
        }
        try {
            response.flushBuffer();
        } catch (Exception ignored) {
        }
        try {
            response.getWriter().close();
        } catch (Exception ignored) {
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
        boolean isAdmin = "admin".equals(ctx.user().getRole());
        checkGroupAdminOnly(group, isAdmin);
        checkGroupLevel(group, ctx.userLevel(), isAdmin);
        RelaySupport.MediaParams params = support.parseMediaParams(requestBody);
        BigDecimal creditCost = billingService.calculateImageCreditCost(group, params.size(), params.n());

        List<ModelGroupRoutingService.Candidate> candidates = routingService.resolveOrderedCandidates(
                group, isAdmin, ctx.userLevel());
        if (candidates.isEmpty()) {
            throw new BusinessException(503, "模型组无可用成员: " + groupName,
                    "No available members in model group: " + groupName);
        }

        MediaAttemptResult result = attemptMedia(ctx, group, candidates, path, requestBody, creditCost);
        RelaySupport.MediaCharge charge = result.charge();

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
        relayAudioSpeechWithContext(ctx, group, requestBody, httpRequest, httpResponse);
    }

    void relayAudioSpeechWithContext(RelaySupport.RelayContext ctx, ModelGroup group, String requestBody,
                                     HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException {
        String groupName = group.getName();
        boolean isAdmin = "admin".equals(ctx.user().getRole());
        checkGroupAdminOnly(group, isAdmin);
        checkGroupLevel(group, ctx.userLevel(), isAdmin);
        JsonNode body = support.objectMapper.readTree(requestBody);
        if (!body.isObject()) {
            throw new BusinessException(400, "请求体必须是 JSON 对象", "Request body must be a JSON object");
        }
        JsonNode inputNode = body.get("input");
        if (inputNode == null || !inputNode.isTextual() || inputNode.asText().isEmpty()) {
            throw new BusinessException(400, "语音合成请求缺少有效的 input 文本参数", "Text-to-speech request is missing a valid input text parameter");
        }
        String quality = body.hasNonNull("quality") && body.get("quality").isTextual() ? body.get("quality").asText() : null;
        if (quality != null && UsageLogService.resolveAudioTier(quality) == null) {
            throw new BusinessException(400, "不支持的音频音质参数 (quality): " + quality,
                    "Unsupported audio quality parameter (quality): " + quality);
        }
        double speed = body.hasNonNull("speed") ? body.get("speed").asDouble() : 1.0;
        int estimatedSeconds = Math.max(1, (int) Math.ceil(
                inputNode.asText().codePointCount(0, inputNode.asText().length()) / (14.0 * speed)));
        BigDecimal estimatedCost = billingService.calculateAudioCreditCost(group, quality, estimatedSeconds);

        ObjectNode upstreamBody = ((ObjectNode) body).deepCopy();
        upstreamBody.remove("quality");
        upstreamBody.remove("duration");
        upstreamBody.remove("seconds");

        List<ModelGroupRoutingService.Candidate> candidates = routingService.resolveOrderedCandidates(
                group, isAdmin, ctx.userLevel());
        if (candidates.isEmpty()) {
            throw new BusinessException(503, "模型组无可用成员: " + groupName,
                    "No available members in model group: " + groupName);
        }

        String baseSpeechBody = support.objectMapper.writeValueAsString(upstreamBody);
        MediaBinaryAttemptResult result = attemptBinaryMedia(ctx, group, candidates,
                "/v1/audio/speech", baseSpeechBody, estimatedCost);
        RelaySupport.MediaCharge charge = result.charge();

        byte[] audio = result.binary().body();
        String responseFormat = body.hasNonNull("response_format") ? body.get("response_format").asText() : "mp3";
        double measured = "pcm".equalsIgnoreCase(responseFormat)
                ? com.aiconnecting.common.AudioDurationUtil.pcmSeconds(audio)
                : com.aiconnecting.common.AudioDurationUtil.measure(audio);
        if (measured <= 0) {
            boolean refunded = support.refundMediaCharge(ctx, charge);
            throw new BusinessException(400,
                    "生成的音频无法测量有效时长" + (refunded ? "，预扣积分已退回" : "，退回预扣积分失败，已记录待人工补偿"),
                    "Unable to measure a valid duration for the generated audio"
                            + (refunded ? ", prepaid credits were refunded"
                            : ", failed to refund prepaid credits; manual compensation is pending"));
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
        boolean isAdmin = "admin".equals(ctx.user().getRole());
        checkGroupAdminOnly(group, isAdmin);
        checkGroupLevel(group, ctx.userLevel(), isAdmin);
        BigDecimal creditCost = billingService.calculateAudioCreditCost(group, quality, seconds);
        RelaySupport.MediaCharge charge = support.chargeMediaCredits(ctx, creditCost);
        List<ModelGroupRoutingService.Candidate> candidates = routingService.resolveOrderedCandidates(
                group, isAdmin, ctx.userLevel());
        if (candidates.isEmpty()) {
            support.refundMediaCharge(ctx, charge);
            throw new BusinessException(503, "模型组无可用成员: " + group.getName(),
                    "No available members in model group: " + group.getName());
        }
        long startTime = System.currentTimeMillis();
        long deadline = startTime + WALL_CLOCK_BUDGET_MS;
        int maxAttempts = Math.min(group.getMaxAttempts() != null ? group.getMaxAttempts() : 5, 8);
        BusinessException lastFailure = null;
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
                lastFailure = null;
                continue;
            }
            if (support.isChannelRateLimited(channel)) {
                log.debug("模型组 {} 成员 {} (渠道 {}) 冷却/限流中，跳过", group.getName(), candidate.modelConfig().getName(), channel.getId());
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
                long timeoutMs = remainingBudgetMs(deadline);
                if (timeoutMs <= 0) break;
                FailureLogContext.setChannelModel(memberModel);
                RelaySupport.BinaryResponse response = support.forwardMultipartRequest(channel, path, mb.build(), timeoutMs);
                if (!rawPassThrough && !isValidTranscriptionJson(response.body())) {
                    throw new BusinessException(502, "上游返回无法解析的转写结果",
                            "Upstream returned an unparseable transcription result");
                }

                long duration = System.currentTimeMillis() - startTime;
                recordGroupPrepaidUsage(ctx.token(), channel, group, memberModel, charge, duration, httpRequest, path);
                String responseContentType = response.contentType();
                return ResponseEntity.ok()
                        .header("Content-Type", responseContentType != null ? responseContentType : "application/json")
                        .body(response.body());
            } catch (BusinessException e) {
                lastFailure = e;
                log.error("模型组 {} 成员 {} (渠道 {}) 转写请求失败 (尝试 {}/{}): {}",
                        group.getName(), memberModel, channel.getId(), attempts, maxAttempts, e.getMessage());
                recordFailure(channel, modelConfigId, e);
                if (remainingBudgetMs(deadline) <= 0) break;
            }
        }
        support.refundMediaCharge(ctx, charge);
        throw lastFailure != null
                ? wrapFailure(lastFailure, exhaustedGroupMessage(group.getName()))
                : new BusinessException(502, exhaustedGroupMessage(group.getName()),
                        exhaustedGroupEnglishMessage(group.getName()));
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
        boolean isAdmin = "admin".equals(ctx.user().getRole());
        checkGroupAdminOnly(group, isAdmin);
        checkGroupLevel(group, ctx.userLevel(), isAdmin);
        RelaySupport.MediaParams params = support.parseMediaParams(requestBody);
        BigDecimal unitPrice = billingService.resolveVideoUnitPrice(group, params.size());
        BigDecimal creditCost = billingService.calculateVideoCreditCost(group, params.size(), params.durationSeconds());

        List<ModelGroupRoutingService.Candidate> candidates = routingService.resolveOrderedCandidates(
                group, isAdmin, ctx.userLevel());
        if (candidates.isEmpty()) {
            throw new BusinessException(503, "模型组无可用成员: " + groupName,
                    "No available members in model group: " + groupName);
        }

        MediaAttemptResult result = attemptMedia(ctx, group, candidates, path, requestBody, creditCost);
        RelaySupport.MediaCharge charge = result.charge();

        JsonNode json;
        try {
            json = support.objectMapper.readTree(result.response());
        } catch (Exception e) {
            support.refundMediaCharge(ctx, charge);
            throw new BusinessException(502, "上游视频响应无效（非 JSON），预扣积分已退回",
                    "Invalid upstream video response (not JSON), prepaid credits were refunded");
        }
        String upstreamVideoId = json != null && json.isObject() ? firstText(json.get("video_id"), json.get("id")) : null;
        if (upstreamVideoId == null) {
            support.refundMediaCharge(ctx, charge);
            throw new BusinessException(502, "上游视频响应缺少有效的任务 id，预扣积分已退回",
                    "Upstream video response is missing a valid task ID, prepaid credits were refunded");
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
            throw new BusinessException(500,
                    "视频任务保存失败" + (refunded ? "，预扣积分已退回" : "，退回预扣积分失败，已记录待人工补偿"),
                    "Failed to save video task" + (refunded ? ", prepaid credits were refunded"
                            : ", failed to refund prepaid credits; manual compensation is pending"));
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

    private void releaseAttemptCharge(RelaySupport.RelayContext ctx, RelaySupport.MediaCharge charge) {
        if (!support.refundMediaCharge(ctx, charge)) {
            throw new BusinessException(500, "本次失败成员的预扣积分退回失败，已记录待人工补偿，停止切换成员",
                    "Failed to refund prepaid credits for the failed member; manual compensation is pending, so member failover was stopped");
        }
    }

    // ==================== 媒体请求的通用成员切换骨架 ====================

    private record MediaAttemptResult(String response, Channel channel, String actualModel, long startTime,
                                      RelaySupport.MediaCharge charge) {}
    private record MediaBinaryAttemptResult(RelaySupport.BinaryResponse binary, Channel channel, String actualModel,
                                            long startTime, RelaySupport.MediaCharge charge) {}

    /**
     * 媒体（图片/视频）请求的成员切换骨架：预扣已在调用方完成，本方法只负责选成员+选渠道+转发，
     * 失败时按快速失败/切换分类处理；调用方负责失败时退款
     */
    private MediaAttemptResult attemptMedia(RelaySupport.RelayContext ctx, ModelGroup group,
                                            List<ModelGroupRoutingService.Candidate> candidates,
                                            String path, String requestBody, BigDecimal creditCost) {
        long startTime = System.currentTimeMillis();
        long deadline = startTime + WALL_CLOCK_BUDGET_MS;
        int maxAttempts = Math.min(group.getMaxAttempts() != null ? group.getMaxAttempts() : 5, 8);
        BusinessException lastFailure = null;
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
                lastFailure = null;
                continue;
            }
            if (support.isChannelRateLimited(channel)) {
                log.debug("模型组 {} 成员 {} (渠道 {}) 冷却/限流中，跳过", group.getName(), candidate.modelConfig().getName(), channel.getId());
                continue;
            }
            String memberModel = candidate.modelConfig().getName();
            attempts++;
            long timeoutMs = remainingBudgetMs(deadline);
            if (timeoutMs <= 0) break;
            RelaySupport.MediaCharge charge = support.chargeMediaCredits(ctx, creditCost);
            try {
                String upstreamBody = "image".equals(group.getType())
                        ? support.prepareImageRequestBody(channel, rewriteModelField(requestBody, memberModel))
                        : rewriteModelField(requestBody, memberModel);
                String response = support.forwardRequest(channel, path, upstreamBody, timeoutMs);

                return new MediaAttemptResult(response, channel, memberModel, startTime, charge);
            } catch (BusinessException e) {
                releaseAttemptCharge(ctx, charge);
                lastFailure = e;
                log.error("模型组 {} 成员 {} (渠道 {}) 媒体请求失败 (尝试 {}/{}): {}",
                        group.getName(), memberModel, channel.getId(), attempts, maxAttempts, e.getMessage());
                recordFailure(channel, modelConfigId, e);
                if (remainingBudgetMs(deadline) <= 0) break;
            } catch (RuntimeException e) {
                releaseAttemptCharge(ctx, charge);
                throw e;
            }
        }
        throw lastFailure != null
                ? wrapFailure(lastFailure, exhaustedGroupMessage(group.getName()))
                : new BusinessException(502, exhaustedGroupMessage(group.getName()),
                        exhaustedGroupEnglishMessage(group.getName()));
    }

    private MediaBinaryAttemptResult attemptBinaryMedia(RelaySupport.RelayContext ctx, ModelGroup group,
                                                         List<ModelGroupRoutingService.Candidate> candidates,
                                                         String path, String baseBodyJson, BigDecimal creditCost) {
        long startTime = System.currentTimeMillis();
        long deadline = startTime + WALL_CLOCK_BUDGET_MS;
        int maxAttempts = Math.min(group.getMaxAttempts() != null ? group.getMaxAttempts() : 5, 8);
        BusinessException lastFailure = null;
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
                lastFailure = null;
                continue;
            }
            if (support.isChannelRateLimited(channel)) {
                log.debug("模型组 {} 成员 {} (渠道 {}) 冷却/限流中，跳过", group.getName(), candidate.modelConfig().getName(), channel.getId());
                continue;
            }
            String memberModel = candidate.modelConfig().getName();
            attempts++;
            long timeoutMs = remainingBudgetMs(deadline);
            if (timeoutMs <= 0) break;
            RelaySupport.MediaCharge charge = support.chargeMediaCredits(ctx, creditCost);
            try {
                String upstreamBody = rewriteModelField(baseBodyJson, memberModel);
                RelaySupport.BinaryResponse response = support.forwardBinaryRequest(channel, path, upstreamBody, timeoutMs);

                return new MediaBinaryAttemptResult(response, channel, memberModel, startTime, charge);
            } catch (BusinessException e) {
                releaseAttemptCharge(ctx, charge);
                lastFailure = e;
                log.error("模型组 {} 成员 {} (渠道 {}) 二进制媒体请求失败 (尝试 {}/{}): {}",
                        group.getName(), memberModel, channel.getId(), attempts, maxAttempts, e.getMessage());
                recordFailure(channel, modelConfigId, e);
                if (remainingBudgetMs(deadline) <= 0) break;
            } catch (RuntimeException e) {
                releaseAttemptCharge(ctx, charge);
                throw e;
            }
        }
        throw lastFailure != null
                ? wrapFailure(lastFailure, exhaustedGroupMessage(group.getName()))
                : new BusinessException(502, exhaustedGroupMessage(group.getName()),
                        exhaustedGroupEnglishMessage(group.getName()));
    }
}
