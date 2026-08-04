package com.aiconnecting.service;

import com.aiconnecting.common.AudioDurationUtil;
import com.aiconnecting.common.BusinessException;
import com.aiconnecting.common.ProtocolConverter;
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
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
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

    /**
     * 中转请求 (非流式) - 最多重试 3 次，每次选择不同渠道
     */
    public String relayRequest(String tokenKey, String path, String requestBody,
                               String model, HttpServletRequest httpRequest) {
        RelaySupport.RelayContext ctx = support.validateAndPrepare(tokenKey, model);
        Set<Long> triedChannels = new HashSet<>();
        long startTime = System.currentTimeMillis();
        String lastError = null;
        int attempt = 0;

        while (attempt < RelaySupport.MAX_RETRIES) {
            Channel channel;
            try {
                channel = support.channelRouter.selectChannel(ctx.channelModelId(), triedChannels, ctx.userLevel());
            } catch (BusinessException e) {
                if (lastError != null) {
                    throw new BusinessException(502, "所有渠道均不可用，最后错误: " + lastError);
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
                lastError = e.getMessage();
                log.error("渠道 {} 请求失败 (尝试 {}/{}): {}", channel.getId(), attempt, RelaySupport.MAX_RETRIES, e.getMessage());
                support.channelHealthTracker.recordFailure(channel.getId(),
                        ChannelHealthTracker.ErrorCategory.fromStatusCode(e.getCode()), e.getMessage());
                if (attempt == RelaySupport.MAX_RETRIES) {
                    throw new BusinessException(e.getCode(),
                            "所有渠道均不可用，最后错误: " + lastError);
                }
            }
        }
        throw new BusinessException(502, "所有渠道均不可用，最后错误: " + lastError);
    }

    /**
     * 图片/视频生成中转 (非流式)：价格在请求前完全可知，先原子校验余额并预扣积分，再转发上游；
     * 上游失败时退回预扣积分。视频响应保留上游原始 id，并持久化 id→渠道 映射，
     * 供 GET /v1/videos/{id} 通过中转向原始渠道轮询任务状态（不向客户端暴露渠道凭据）。
     *
     * @param mediaType image 或 video，入口处强制模型类型与端点匹配
     */
    public String relayMediaRequest(String tokenKey, String path, String requestBody,
                                    String model, HttpServletRequest httpRequest, String mediaType) {
        RelaySupport.RelayContext ctx = support.validateAndPrepare(tokenKey, model, mediaType);
        RelaySupport.MediaCharge charge = support.prepareMediaCharge(ctx, requestBody);

        long startTime = System.currentTimeMillis();
        ChannelResult<String> result;
        try {
            result = forwardWithRetry(ctx, channel -> support.forwardRequest(channel, path, requestBody));
        } catch (RuntimeException e) {
            support.refundMediaCharge(ctx, charge);
            throw e;
        }

        String response = result.value();
        if ("video".equals(mediaType)) {
            try {
                response = handleVideoResponse(response, result.channel().getId(), ctx.token().getUserId(), model);
            } catch (RuntimeException e) {
                // 任务映射保存失败时客户端拿到的 id 将无法轮询，退回预扣并报错，不返回不可用的任务 id
                support.refundMediaCharge(ctx, charge);
                throw e;
            }
        }
        long duration = System.currentTimeMillis() - startTime;
        recordPrepaidUsageSafely(ctx.token(), result.channel(), model, charge, duration, httpRequest, path);
        return response;
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
        String lastError = null;
        int attempt = 0;

        while (attempt < RelaySupport.MAX_RETRIES) {
            Channel channel;
            try {
                channel = support.channelRouter.selectChannel(ctx.channelModelId(), triedChannels, ctx.userLevel());
            } catch (BusinessException e) {
                if (lastError != null) {
                    throw new BusinessException(502, "所有渠道均不可用，最后错误: " + lastError);
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
                lastError = e.getMessage();
                log.error("渠道 {} 媒体请求失败 (尝试 {}/{}): {}", channel.getId(), attempt, RelaySupport.MAX_RETRIES, e.getMessage());
                support.channelHealthTracker.recordFailure(channel.getId(),
                        ChannelHealthTracker.ErrorCategory.fromStatusCode(e.getCode()), e.getMessage());
                if (attempt == RelaySupport.MAX_RETRIES) {
                    throw new BusinessException(e.getCode(),
                            "所有渠道均不可用，最后错误: " + lastError);
                }
            }
        }
        throw new BusinessException(502, "所有渠道均不可用，最后错误: " + lastError);
    }

    /**
     * 记录已预扣积分的媒体使用日志；上游已成功，日志落库失败不回退积分也不影响响应（仅记录错误）
     */
    private void recordPrepaidUsageSafely(Token token, Channel channel, String model,
                                          RelaySupport.MediaCharge charge, long duration,
                                          HttpServletRequest httpRequest, String path) {
        try {
            support.recordPrepaidMediaUsage(token, channel, model, charge, duration, httpRequest, path);
        } catch (Exception e) {
            log.error("媒体使用日志写入失败（积分已扣减，保持扣减，不影响响应）: model={}, cost={}",
                    model, charge.cost(), e);
        }
    }

    /**
     * 持久化上游视频任务 id 与渠道的映射，供轮询接口回查；
     * 响应保留上游原始 id，仅附加非敏感的 channel_id 字段（不暴露渠道凭据或地址）。
     * 映射保存失败时抛错（由调用方退回预扣积分），避免客户端付了费却拿到无法轮询的任务 id
     */
    private String handleVideoResponse(String response, Long channelId, Long userId, String model) {
        JsonNode json;
        try {
            json = support.objectMapper.readTree(response);
        } catch (Exception e) {
            log.warn("视频响应不是合法 JSON，跳过任务映射记录");
            return response;
        }
        if (!json.isObject() || !json.hasNonNull("id")) {
            log.warn("视频响应缺少 id 字段，无法记录任务映射，轮询接口将无法查询该任务");
            return response;
        }
        try {
            videoTaskRepository.save(VideoTask.builder()
                    .upstreamId(json.get("id").asText())
                    .channelId(channelId)
                    .userId(userId)
                    .model(model)
                    .build());
        } catch (Exception e) {
            log.error("视频任务映射保存失败: upstreamId={}", json.get("id").asText(), e);
            throw new BusinessException(500, "视频任务保存失败，预扣积分已退回，请重试");
        }
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).put("channel_id", channelId);
        try {
            return support.objectMapper.writeValueAsString(json);
        } catch (Exception e) {
            return response;
        }
    }

    /**
     * 视频任务状态查询：按上游任务 id 找到当初处理该任务的渠道，
     * 用渠道自身凭据向上游转发查询并透传结果；仅任务发起用户可查询
     */
    public String relayVideoStatusRequest(String tokenKey, String videoId) {
        Token token = support.validateToken(tokenKey);
        if (videoId == null || videoId.isBlank() || !videoId.matches("[A-Za-z0-9_\\-.:]+")) {
            throw new BusinessException(400, "无效的视频任务 id");
        }
        VideoTask task = videoTaskRepository
                .findFirstByUpstreamIdAndUserIdOrderByCreatedAtDesc(videoId, token.getUserId())
                .orElseThrow(() -> new BusinessException(404, "视频任务不存在: " + videoId));
        Channel channel = channelService.getById(task.getChannelId());
        return support.forwardGetRequest(channel, "/v1/videos/" + videoId);
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
            support.refundMediaCharge(ctx, charge);
            throw e;
        }
        byte[] audio = result.value().body();
        long duration = System.currentTimeMillis() - startTime;

        // 按实际生成音频时长结算（无法测量时保持预估计费并告警，绝不因本地解析失败报错）
        BigDecimal finalCost = charge.cost();
        double measured = "pcm".equalsIgnoreCase(responseFormat)
                ? AudioDurationUtil.pcmSeconds(audio)
                : AudioDurationUtil.measure(audio);
        if (measured > 0) {
            int actualSeconds = Math.max(1, (int) Math.ceil(measured));
            BigDecimal actualCost = usageLogService.calculateAudioCreditCost(ctx.modelConfig(), quality, actualSeconds);
            finalCost = support.settleMediaCharge(ctx, charge, actualCost);
        } else {
            log.warn("无法测量生成音频的实际时长 (format={})，按预估 {} 秒计费", responseFormat, estimatedSeconds);
        }
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
    public ResponseEntity<byte[]> relayAudioTranscription(String tokenKey, String path, MultipartFile file,
                                                          Map<String, String> formFields,
                                                          HttpServletRequest httpRequest) throws IOException {
        String model = formFields.get("model");
        RelaySupport.RelayContext ctx = support.validateAndPrepare(tokenKey, model, "audio");

        String quality = formFields.get("quality");
        if (quality != null && UsageLogService.resolveAudioTier(quality) == null) {
            throw new BusinessException(400, "不支持的音频音质参数 (quality): " + quality + "，支持 standard/hd");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请求缺少音频文件 (file)");
        }
        byte[] fileBytes = file.getBytes();
        double measured = AudioDurationUtil.measure(fileBytes);
        if (measured <= 0) {
            throw new BusinessException(400, "无法识别上传音频的时长（按秒计费所需），支持 wav/mp3/flac/ogg/opus/m4a/mp4/aac/webm 格式");
        }
        int seconds = Math.max(1, (int) Math.ceil(measured));
        BigDecimal cost = usageLogService.calculateAudioCreditCost(ctx.modelConfig(), quality, seconds);

        okhttp3.MultipartBody.Builder mb = new okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM);
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "audio";
        okhttp3.MediaType fileType = okhttp3.MediaType.parse(
                file.getContentType() != null ? file.getContentType() : "application/octet-stream");
        mb.addFormDataPart("file", fileName, okhttp3.RequestBody.create(fileBytes, fileType));
        for (Map.Entry<String, String> field : formFields.entrySet()) {
            String key = field.getKey();
            // 剥离非标准计费参数，其余表单字段原样转发
            if ("quality".equals(key) || "duration".equals(key) || "seconds".equals(key)) {
                continue;
            }
            mb.addFormDataPart(key, field.getValue());
        }
        okhttp3.MultipartBody multipartBody = mb.build();

        RelaySupport.MediaCharge charge = support.chargeMediaCredits(ctx, cost);
        long startTime = System.currentTimeMillis();
        ChannelResult<RelaySupport.BinaryResponse> result;
        try {
            result = forwardWithRetry(ctx, channel -> support.forwardMultipartRequest(channel, path, multipartBody));
        } catch (RuntimeException e) {
            support.refundMediaCharge(ctx, charge);
            throw e;
        }
        long duration = System.currentTimeMillis() - startTime;
        recordPrepaidUsageSafely(ctx.token(), result.channel(), model, charge, duration, httpRequest, path);

        String contentType = result.value().contentType();
        return ResponseEntity.ok()
                .header("Content-Type", contentType != null ? contentType : "application/json")
                .body(result.value().body());
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
        long startTime = System.currentTimeMillis();
        String lastError = null;
        int attempt = 0;

        while (attempt < RelaySupport.MAX_RETRIES) {
            Channel channel;
            try {
                channel = support.channelRouter.selectChannel(ctx.channelModelId(), triedChannels, ctx.userLevel());
            } catch (BusinessException e) {
                if (lastError != null && !httpResponse.isCommitted()) {
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
                log.error("渠道 {} 流式连接失败 (尝试 {}/{}): {}", channel.getId(), attempt, RelaySupport.MAX_RETRIES, e.getMessage());
                support.channelHealthTracker.recordFailure(channel.getId(),
                        ChannelHealthTracker.ErrorCategory.fromException(e), e.getMessage());
                if (attempt < RelaySupport.MAX_RETRIES && !httpResponse.isCommitted()) continue;
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
                    log.warn("渠道 {} 流式请求失败: {}", channel.getId(), lastError);
                    support.channelHealthTracker.recordFailure(channel.getId(),
                            ChannelHealthTracker.ErrorCategory.fromStatusCode(code), lastError);
                    conn.disconnect();
                    if (attempt < RelaySupport.MAX_RETRIES && !httpResponse.isCommitted()) continue;
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
                log.error("渠道 {} 流式请求异常 (尝试 {}/{}): {}", channel.getId(), attempt, RelaySupport.MAX_RETRIES, e.getMessage());
                support.channelHealthTracker.recordFailure(channel.getId(),
                        ChannelHealthTracker.ErrorCategory.fromException(e), e.getMessage());
                if (attempt < RelaySupport.MAX_RETRIES && !httpResponse.isCommitted()) continue;
                if (!httpResponse.isCommitted()) {
                    RelayServiceUtils.writeOpenAiError(httpResponse, 502, "渠道请求失败: " + e.getMessage());
                }
                return;
            }
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
