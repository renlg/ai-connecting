package com.aiconnecting.service;

import com.aiconnecting.common.RedisDistributedLock;
import com.aiconnecting.entity.ChannelFailureRecord;
import com.aiconnecting.entity.Token;
import com.aiconnecting.repository.ChannelFailureRecordRepository;
import com.aiconnecting.repository.TokenRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class RiskAiAnalyzerService {

    private final ChannelFailureRecordRepository repository;
    private final TokenRepository tokenRepository;
    private final RiskManagerService riskManagerService;
    private final RedisDistributedLock distributedLock;
    private final TransactionTemplate transactionTemplate;

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${app.risk-ai.enabled:true}")
    private boolean enabled;

    @Value("${app.risk-ai.model-group:free}")
    private String modelGroup;

    @Value("${app.risk-ai.token:}")
    private String envToken;

    @Value("${app.risk-ai.scan-window-ms:3600000}")
    private long scanWindowMs;

    @Value("${app.risk-ai.max-records:200}")
    private int maxRecords;

    @Value("${app.risk-ai.fuse-days:360}")
    private int fuseDays;

    private static final String LOCK_KEY = "job:riskAiAnalysis";
    private static final long LOCK_TTL_SECONDS = 300;
    private static final String CLEAN_LOCK_KEY = "job:channelFailureRecordsClean";
    private static final long CLEAN_LOCK_TTL_SECONDS = 120;
    private static final int RETENTION_DAYS = 3;

    private final OkHttpClient aiClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    @Scheduled(fixedRateString = "${app.risk-ai.interval-ms:600000}", initialDelay = 120000)
    public void runAnalysis() {
        if (!enabled) return;
        distributedLock.runIfLocked(LOCK_KEY, LOCK_TTL_SECONDS, this::doAnalysis);
    }

    void doAnalysis() {
        long now = System.currentTimeMillis();
        long since = now - scanWindowMs;
        List<ChannelFailureRecord> records = repository.findUnanalyzedSince(since, PageRequest.of(0, maxRecords));
        if (records.isEmpty()) {
            log.debug("AI分析: 无待分析失败记录，跳过");
            return;
        }

        List<Long> recordIds = records.stream().map(ChannelFailureRecord::getId).toList();

        List<String> lines = new ArrayList<>(records.size());
        for (ChannelFailureRecord r : records) {
            String msg = r.getErrorMessage() != null
                    ? r.getErrorMessage().replace("\n", " ").replace("\r", "")
                    : "";
            if (msg.length() > 150) msg = msg.substring(0, 150);
            lines.add(r.getId() + "|" + r.getChannelId() + "|" + nullSafe(r.getModelName())
                    + "|" + nullSafe(r.getErrorCode()) + "|" + msg);
        }

        Set<ChannelModel> fuseTargets;
        try {
            fuseTargets = callAi(lines);
        } catch (Exception e) {
            log.warn("AI分析调用失败，本轮跳过: {}", e.getMessage());
            markAnalyzed(recordIds);
            return;
        }

        markAnalyzed(recordIds);

        if (fuseTargets.isEmpty()) {
            log.info("AI分析完成: 分析{}条记录，未识别出免费额度耗尽或模型不存在", records.size());
            return;
        }

        int fuseDurationSeconds = Math.toIntExact(fuseDays * 24L * 60 * 60);
        for (ChannelModel cm : fuseTargets) {
            try {
                String reason = "AI识别免费额度耗尽/模型不存在(渠道=" + cm.channelId + ", 模型=" + cm.model + ")";
                riskManagerService.createAutoQuotaCircuitBreaker(cm.channelId, cm.model,
                        fuseDurationSeconds, reason);
                log.warn("AI自动熔断: channelId={}, model={}, 熔断{}天", cm.channelId, cm.model, fuseDays);
            } catch (Exception e) {
                log.warn("AI自动熔断写入失败: channelId={}, model={}, error={}", cm.channelId, cm.model, e.getMessage());
            }
        }
        log.info("AI分析完成: 分析{}条记录，熔断{}个渠道+模型", records.size(), fuseTargets.size());
    }

    private Set<ChannelModel> callAi(List<String> lines) throws IOException {
        String token = resolveToken();
        if (token == null || token.isBlank()) {
            throw new IOException("AI分析token未配置(设置RISK_AI_TOKEN或创建name=risk-ai-analyzer的token)");
        }

        String dataBlock = String.join("\n", lines);
        String systemPrompt = """
                你是一个分析渠道API失败日志的助手。只判断哪些"渠道+模型"组合属于以下两类：**免费额度耗尽**或**模型不存在**。
                免费额度耗尽特征（不区分大小写）：insufficient_quota, Free quota exhausted, quota exhausted, 免费额度, \
                quota exceeded, You exceeded your current quota, free tier, no remaining free, insufficient_user_quota.
                模型不存在特征（不区分大小写）：model_not_found, model not found, 模型不存在, unknown model, no such model.
                除这两类明确特征外，其它情况一律不识别为熔断目标。
                只返回JSON数组，格式：[{"channelId":数字,"model":"模型名"}]
                如果没有识别到，返回空数组 []。不要返回其他内容。""";

        String userPrompt = "以下是最近1小时内的" + lines.size() + "条渠道失败记录（格式：ID|渠道ID|模型|错误码|错误信息）：\n"
                + dataBlock;

        var messages = new ArrayList<Object>();
        messages.add(java.util.Map.of("role", "system", "content", systemPrompt));
        messages.add(java.util.Map.of("role", "user", "content", userPrompt));

        var requestBody = java.util.Map.of(
                "model", modelGroup,
                "messages", messages,
                "temperature", 0.0,
                "max_tokens", 1024
        );

        String url = "http://127.0.0.1:" + serverPort + "/v1/chat/completions";
        RequestBody body = RequestBody.create(
                mapper.writeValueAsString(requestBody),
                MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = aiClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("AI分析HTTP失败: " + response.code());
            }
            String responseBody = response.body() != null ? response.body().string() : "";
            return parseAiResponse(responseBody);
        }
    }

    Set<ChannelModel> parseAiResponse(String responseBody) {
        Set<ChannelModel> result = new LinkedHashSet<>();
        try {
            JsonNode root = mapper.readTree(responseBody);
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) return result;

            String json = extractJson(content);
            JsonNode arr = mapper.readTree(json);
            if (arr.isArray()) {
                for (JsonNode item : arr) {
                    long channelId = item.path("channelId").asLong(0);
                    String model = item.path("model").asText("").trim();
                    if (channelId > 0) {
                        result.add(new ChannelModel(channelId, model.isEmpty() ? null : model));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("AI响应解析失败: {}", e.getMessage());
        }
        return result;
    }

    private String extractJson(String content) {
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return "[]";
    }

    private String resolveToken() {
        if (envToken != null && !envToken.isBlank()) return envToken;
        List<Token> tokens = tokenRepository.findByUserId(7L);
        for (Token t : tokens) {
            if ("risk-ai-analyzer".equals(t.getName()) && t.getStatus() != null && t.getStatus() == 1) {
                return t.getTokenKey();
            }
        }
        return null;
    }

    private void markAnalyzed(List<Long> ids) {
        try {
            transactionTemplate.executeWithoutResult(status -> repository.markAnalyzed(ids));
        } catch (Exception e) {
            log.warn("标记已分析失败: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 5 * * ?")
    public void cleanOldRecords() {
        distributedLock.runIfLocked(CLEAN_LOCK_KEY, CLEAN_LOCK_TTL_SECONDS, () ->
                transactionTemplate.executeWithoutResult(status -> {
                    long cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24L * 60 * 60 * 1000;
                    long deleted = repository.deleteByCreatedAtLessThan(cutoff);
                    if (deleted > 0) {
                        log.info("清理渠道失败记录: retentionDays={}, deleted={}", RETENTION_DAYS, deleted);
                    }
                }));
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }

    record ChannelModel(long channelId, String model) {}
}
