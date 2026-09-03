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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class RiskAiAnalyzerService {

    private final ChannelFailureRecordRepository repository;
    private final TokenRepository tokenRepository;
    private final RiskManagerService riskManagerService;
    private final RedisDistributedLock distributedLock;
    private final TransactionTemplate transactionTemplate;
    private final TokenService tokenService;
    private final RelayService relayService;

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

    @Value("${app.risk-ai.fuse-years:10}")
    private int fuseYears;

    @Value("${app.risk-ai.min-hits:2}")
    private int minHits;

    private static final String LOCK_KEY = "job:riskAiAnalysis";
    private static final long LOCK_TTL_SECONDS = 300;
    private static final String CLEAN_LOCK_KEY = "job:channelFailureRecordsClean";
    private static final long CLEAN_LOCK_TTL_SECONDS = 120;
    private static final int RETENTION_DAYS = 3;
    private static final Set<String> AI_ANALYZABLE_ERROR_CODES = Set.of(
            "400", "401", "402", "403", "404", "429");

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
        List<ChannelFailureRecord> analyzableRecords = records.stream()
                .filter(this::isAiAnalyzable)
                .toList();
        if (analyzableRecords.isEmpty()) {
            markAnalyzed(recordIds);
            log.info("AI分析: 过滤后无4xx记录，跳过");
            return;
        }

        List<String> lines = new ArrayList<>(analyzableRecords.size());
        for (ChannelFailureRecord r : analyzableRecords) {
            String msg = r.getErrorMessage() != null
                    ? r.getErrorMessage().replace("\n", " ").replace("\r", "")
                    : "";
            if (msg.length() > 150) msg = msg.substring(0, 150);
            lines.add(r.getId() + "|" + r.getChannelId() + "|" + nullSafe(r.getModelName())
                    + "|" + nullSafe(r.getErrorCode()) + "|" + msg);
        }

        List<AiFuseTarget> aiTargets;
        try {
            aiTargets = callAi(lines);
        } catch (Exception e) {
            log.warn("AI分析调用失败，本轮跳过: {}", e.getMessage());
            markAnalyzed(recordIds);
            return;
        }

        markAnalyzed(recordIds);

        if (aiTargets.isEmpty()) {
            log.info("AI分析完成: 分析{}条4xx记录，未识别出免费额度耗尽或模型不存在", analyzableRecords.size());
            return;
        }

        Map<ChannelModel, Set<Long>> validatedTargets = validateAndGroupTargets(aiTargets, analyzableRecords);
        int fuseDurationSeconds = Math.toIntExact(fuseYears * 365L * 24 * 60 * 60);
        int fusedCount = 0;
        for (Map.Entry<ChannelModel, Set<Long>> entry : validatedTargets.entrySet()) {
            ChannelModel cm = entry.getKey();
            int hitCount = entry.getValue().size();
            if (hitCount < minHits) {
                log.info("AI分析: channelId={}, model={}, 命中数{}不足{}，暂不熔断",
                        cm.channelId, cm.model, hitCount, minHits);
                continue;
            }
            try {
                String reason = "AI识别免费额度耗尽/模型不存在(渠道=" + cm.channelId + ", 模型=" + cm.model + ")";
                riskManagerService.createAutoQuotaCircuitBreaker(cm.channelId, cm.model,
                        fuseDurationSeconds, reason);
                fusedCount++;
                log.warn("AI自动熔断: channelId={}, model={}, 命中数={}, 熔断{}年",
                        cm.channelId, cm.model, hitCount, fuseYears);
            } catch (Exception e) {
                log.warn("AI自动熔断写入失败: channelId={}, model={}, error={}", cm.channelId, cm.model, e.getMessage());
            }
        }
        log.info("AI分析完成: 分析{}条4xx记录，熔断{}个渠道+模型", analyzableRecords.size(), fusedCount);
    }

    private List<AiFuseTarget> callAi(List<String> lines) {
        Token token = resolveToken();
        if (token == null) {
            throw new IllegalStateException("AI分析token未配置(设置RISK_AI_TOKEN或创建name=risk-ai-analyzer的token)");
        }

        String dataBlock = String.join("\n", lines);
        String systemPrompt = """
                你是一个分析渠道API失败日志的助手。只判断哪些"渠道+模型"组合属于以下两类：**免费额度耗尽**或**模型不存在**。
                免费额度耗尽特征（不区分大小写）：insufficient_quota, Free quota exhausted, quota exhausted, 免费额度, \
                quota exceeded, You exceeded your current quota, free tier, no remaining free, insufficient_user_quota.
                模型不存在特征（不区分大小写）：model_not_found, model not found, 模型不存在, unknown model, no such model.
                除这两类明确特征外，其它情况一律不识别为熔断目标。
                只依据输入中实际符合上述特征的记录返回目标，并提供证据。recordIds必须是输入批次中属于该渠道+模型且实际命中上述特征的真实记录ID，matchedKeyword填写分类依据或匹配到的关键字。
                只返回JSON数组，格式：[{"channelId":数字,"model":"模型名","recordIds":[1,2,3],"matchedKeyword":"quota"}]
                如果没有识别到，返回空数组 []。不要返回其他内容。""";

        String userPrompt = "以下是最近1小时内的" + lines.size() + "条渠道失败记录（格式：ID|渠道ID|模型|错误码|错误信息）。"
                + "只依据实际命中免费额度耗尽或模型不存在特征的记录返回目标；每个目标必须返回输入中的真实recordIds和matchedKeyword，"
                + "格式为[{\"channelId\":数字,\"model\":\"模型名\",\"recordIds\":[1,2],\"matchedKeyword\":\"匹配关键字\"}]：\n"
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

        // Token 明文不再落库（仅存哈希），改为按实体走进程内等价中转链路，不再回环 HTTP 端点
        try {
            String responseBody = relayService.relayRequestForToken(token, "/v1/chat/completions",
                    mapper.writeValueAsString(requestBody), modelGroup, null, null);
            return parseAiResponse(responseBody);
        } catch (Exception e) {
            throw new IllegalStateException("AI分析请求失败: " + e.getMessage(), e);
        }
    }

    List<AiFuseTarget> parseAiResponse(String responseBody) {
        List<AiFuseTarget> result = new ArrayList<>();
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
                        List<Long> evidenceRecordIds = new ArrayList<>();
                        JsonNode recordIdsNode = item.get("recordIds");
                        boolean recordIdsProvided = recordIdsNode != null;
                        boolean recordIdsValidFormat = !recordIdsProvided || recordIdsNode.isArray();
                        if (recordIdsNode != null && recordIdsNode.isArray()) {
                            for (JsonNode recordIdNode : recordIdsNode) {
                                if (recordIdNode.canConvertToLong() && recordIdNode.asLong() > 0) {
                                    evidenceRecordIds.add(recordIdNode.asLong());
                                } else {
                                    recordIdsValidFormat = false;
                                }
                            }
                        }
                        String matchedKeyword = item.path("matchedKeyword").asText("").trim();
                        result.add(new AiFuseTarget(channelId, model.isEmpty() ? null : model,
                                evidenceRecordIds, recordIdsProvided, recordIdsValidFormat, matchedKeyword));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("AI响应解析失败: {}", e.getMessage());
        }
        return result;
    }

    private Map<ChannelModel, Set<Long>> validateAndGroupTargets(
            List<AiFuseTarget> aiTargets, List<ChannelFailureRecord> analyzableRecords) {
        Map<Long, ChannelFailureRecord> recordsById = new LinkedHashMap<>();
        Set<ChannelModel> inputChannelModels = new LinkedHashSet<>();
        for (ChannelFailureRecord record : analyzableRecords) {
            recordsById.put(record.getId(), record);
            inputChannelModels.add(new ChannelModel(record.getChannelId(), normalizeModel(record.getModelName())));
        }

        Map<ChannelModel, Set<Long>> validated = new LinkedHashMap<>();
        for (AiFuseTarget target : aiTargets) {
            ChannelModel channelModel = new ChannelModel(target.channelId, normalizeModel(target.model));
            if (!target.recordIdsProvided) {
                if (inputChannelModels.contains(channelModel)) {
                    log.info("AI分析: channelId={}, model={} 返回旧格式且无recordIds，宽容接受但命中数按0计",
                            target.channelId, target.model);
                    validated.computeIfAbsent(channelModel, ignored -> new LinkedHashSet<>());
                } else {
                    log.warn("AI分析丢弃无证据目标: channelId={}, model={} 不在输入4xx记录中",
                            target.channelId, target.model);
                }
                continue;
            }
            if (!target.recordIdsValidFormat) {
                log.warn("AI分析丢弃证据格式无效目标: channelId={}, model={}",
                        target.channelId, target.model);
                continue;
            }
            if (target.recordIds.isEmpty()) {
                log.warn("AI分析丢弃无证据目标: channelId={}, model={} 的recordIds为空",
                        target.channelId, target.model);
                continue;
            }

            Set<Long> distinctRecordIds = new LinkedHashSet<>(target.recordIds);
            boolean valid = true;
            for (Long recordId : distinctRecordIds) {
                ChannelFailureRecord record = recordsById.get(recordId);
                if (record == null || !isAiAnalyzable(record)
                        || !channelModel.equals(new ChannelModel(record.getChannelId(), normalizeModel(record.getModelName())))) {
                    valid = false;
                    break;
                }
            }
            if (!valid) {
                log.warn("AI分析丢弃证据无效目标: channelId={}, model={}, recordIds={}",
                        target.channelId, target.model, target.recordIds);
                continue;
            }
            validated.computeIfAbsent(channelModel, ignored -> new LinkedHashSet<>()).addAll(distinctRecordIds);
        }
        return validated;
    }

    private boolean isAiAnalyzable(ChannelFailureRecord record) {
        String errorCode = record.getErrorCode();
        return errorCode != null && AI_ANALYZABLE_ERROR_CODES.contains(errorCode.trim());
    }

    private static String normalizeModel(String model) {
        if (model == null || model.isBlank()) return null;
        return model.trim();
    }

    private String extractJson(String content) {
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return "[]";
    }

    /** 环境变量优先（明文经完整校验）；否则查库按名称取 Token 实体（库中仅存哈希，无法恢复明文） */
    private Token resolveToken() {
        if (envToken != null && !envToken.isBlank()) {
            try {
                return tokenService.validateTokenKey(envToken);
            } catch (Exception e) {
                log.warn("RISK_AI_TOKEN 校验失败: {}", e.getMessage());
                return null;
            }
        }
        List<Token> tokens = tokenRepository.findByUserId(7L);
        for (Token t : tokens) {
            if ("risk-ai-analyzer".equals(t.getName()) && t.getStatus() != null && t.getStatus() == 1) {
                return t;
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

    record AiFuseTarget(long channelId, String model, List<Long> recordIds,
                        boolean recordIdsProvided, boolean recordIdsValidFormat, String matchedKeyword) {}
}
