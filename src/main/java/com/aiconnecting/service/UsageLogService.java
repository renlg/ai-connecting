package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;
import com.aiconnecting.dto.DashboardDailyStats;
import com.aiconnecting.entity.UsageLog;
import com.aiconnecting.entity.User;
import com.aiconnecting.repository.UsageLogRepository;
import com.aiconnecting.repository.UsageStatsRepository;
import com.aiconnecting.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class UsageLogService {

    private final UsageLogRepository usageLogRepository;
    private final UsageStatsRepository usageStatsRepository;
    private final ChannelService channelService;
    private final ModelConfigService modelConfigService;
    private final UserRepository userRepository;
    private final TokenService tokenService;

    /** 仪表盘按模型类型拆分积分消耗时展示的固定类型集合 */
    private static final List<String> MODEL_TYPES = List.of("text", "image", "video", "audio");

    /** 积分计算除数：每百万 token */
    private static final BigDecimal CREDIT_RATE_DIVISOR = new BigDecimal("1000000");

    /** 模型积分比例缓存，避免每次请求查库，缓存 2 分钟 */
    private final ConcurrentHashMap<String, CachedCreditRate> creditRateCache = new ConcurrentHashMap<>();
    private static final long CREDIT_RATE_CACHE_TTL_MS = 2 * 60 * 1000L;

    private record CachedCreditRate(int inputRate, int outputRate, BigDecimal cacheCreditRate, long cachedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > CREDIT_RATE_CACHE_TTL_MS;
        }
    }

    /** 仪表盘每日统计缓存，避免用户来回切换天数时重复全表 JOIN，缓存 30 秒 */
    private final ConcurrentHashMap<DailyStatsCacheKey, CachedDailyStats> dailyStatsCache = new ConcurrentHashMap<>();
    private static final long DAILY_STATS_CACHE_TTL_MS = 30 * 1000L;

    private record DailyStatsCacheKey(boolean isAdmin, List<Long> tokenIds, int days) {}

    private record CachedDailyStats(DashboardDailyStats stats, long cachedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > DAILY_STATS_CACHE_TTL_MS;
        }
    }

    public Long getTotalTokensSince(LocalDateTime since) {
        Long result = usageLogRepository.sumTotalTokensSince(since);
        return result != null ? result : 0L;
    }

    public Long getTotalRequests() {
        return usageLogRepository.count();
    }

    public Long getRequestsToday() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        return usageLogRepository.countRequestsSince(startOfDay);
    }

    public Long getTokensUsedToday() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        return getTotalTokensSince(startOfDay);
    }

    public long getTotalInputTokens() {
        return usageLogRepository.sumPromptTokens();
    }

    public long getTotalOutputTokens() {
        return usageLogRepository.sumCompletionTokens();
    }

    public long getInputTokensToday() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        return usageLogRepository.sumPromptTokensSince(startOfDay);
    }

    public long getOutputTokensToday() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        return usageLogRepository.sumCompletionTokensSince(startOfDay);
    }

    public long getTotalCachedPromptTokens() {
        return usageLogRepository.sumCachedPromptTokens();
    }

    public long getCachedPromptTokensToday() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        return usageLogRepository.sumCachedPromptTokensSince(startOfDay);
    }

    public BigDecimal getTotalCreditsConsumed() {
        return BigDecimal.valueOf(usageLogRepository.sumCreditCost());
    }

    public BigDecimal getCreditsConsumedToday() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        return BigDecimal.valueOf(usageLogRepository.sumCreditCostSince(startOfDay));
    }

    /**
     * 事务性记录使用日志并更新额度（由 RelayService 调用，确保 @Transactional 生效）
     */
    @Transactional
    public void recordUsageAndQuotas(UsageLog usageLog, Long tokenId, Long channelId, int totalTokens, Long userId) {
        usageLogRepository.save(usageLog);
        // 原子更新 token 额度
        if (totalTokens > 0) {
            channelService.addUsedQuota(channelId, totalTokens);
        }
        // 扣减用户积分
        if (usageLog.getCreditCost() != null && usageLog.getCreditCost().compareTo(BigDecimal.ZERO) > 0 && userId != null) {
            userRepository.deductCredits(userId, usageLog.getCreditCost());
        }
        // 更新 token 已用额度
        tokenService.addUsedQuota(tokenId, totalTokens);
    }

    /**
     * 记录已预扣积分的媒体使用日志（积分在调用上游前已原子扣减，此处仅落日志，不再重复扣减）
     *
     * @return 落库后的使用日志（含生成的 id），供视频任务保存该 id，以便结算阶段回写最终计费金额
     */
    @Transactional
    public UsageLog recordPrepaidUsage(UsageLog usageLog) {
        return usageLogRepository.save(usageLog);
    }

    /**
     * 计算积分消耗（含缓存折扣）
     */
    public BigDecimal calculateCreditCost(String model, int promptTokens, int completionTokens, int cachedTokens) {
        if (promptTokens == 0 && completionTokens == 0) return BigDecimal.ZERO;

        CachedCreditRate cached = creditRateCache.get(model);
        int inputRate, outputRate;
        BigDecimal cacheCreditRate;
        if (cached != null && !cached.isExpired()) {
            inputRate = cached.inputRate();
            outputRate = cached.outputRate();
            cacheCreditRate = cached.cacheCreditRate();
        } else {
            List<com.aiconnecting.entity.ModelConfig> models = modelConfigService.findByName(model);
            com.aiconnecting.entity.ModelConfig modelConfig = models.isEmpty() ? null : models.get(0);
            if (modelConfig == null) return BigDecimal.ZERO;
            inputRate = modelConfig.getInputCreditRate();
            outputRate = modelConfig.getOutputCreditRate();
            cacheCreditRate = modelConfig.getCacheCreditRate();
            creditRateCache.put(model, new CachedCreditRate(inputRate, outputRate, cacheCreditRate, System.currentTimeMillis()));
        }

        int clampedCachedTokens = Math.min(cachedTokens, promptTokens);
        int effectivePromptTokens = promptTokens - clampedCachedTokens;
        BigDecimal inputCost = BigDecimal.valueOf(effectivePromptTokens).divide(CREDIT_RATE_DIVISOR, 10, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(inputRate));
        BigDecimal cacheCost = BigDecimal.valueOf(clampedCachedTokens).divide(CREDIT_RATE_DIVISOR, 10, RoundingMode.HALF_UP).multiply(cacheCreditRate);
        BigDecimal outputCost = BigDecimal.valueOf(completionTokens).divide(CREDIT_RATE_DIVISOR, 10, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(outputRate));
        return inputCost.add(cacheCost).add(outputCost);
    }

    // ==================== 图片/视频按分辨率档位计费 ====================

    /**
     * 解析图片 size 参数（如 1024x1024、2048x2048）对应的分辨率档位。
     * 最长边 < 2048 → 1K, < 4096 → 2K, 否则 → 4K；无法识别时返回 null（由调用方拒绝请求，避免按低档位漏计费）。
     */
    public static String resolveImageTier(String size) {
        int[] dims = parseDimensions(size);
        if (dims == null) {
            if (size != null) {
                String s = size.trim().toLowerCase();
                if (s.equals("4k")) return "4K";
                if (s.equals("2k")) return "2K";
                if (s.equals("1k")) return "1K";
            }
            return null;
        }
        int maxDim = Math.max(dims[0], dims[1]);
        if (maxDim < 2048) return "1K";
        if (maxDim < 4096) return "2K";
        return "4K";
    }

    /**
     * 解析视频 size/resolution 参数（如 1280x720、720p）对应的分辨率档位。
     * 最短边 <= 480 → 480P, <= 720 → 720P, <= 1080 → 1080P, 否则 → 4K；无法识别时返回 null（由调用方拒绝请求，避免按低档位漏计费）。
     */
    public static String resolveVideoTier(String size) {
        if (size != null) {
            String s = size.trim().toLowerCase();
            switch (s) {
                case "480p": return "480P";
                case "720p": return "720P";
                case "1080p": return "1080P";
                case "4k", "2160p": return "4K";
            }
        }
        int[] dims = parseDimensions(size);
        if (dims == null) return null;
        int minDim = Math.min(dims[0], dims[1]);
        if (minDim <= 480) return "480P";
        if (minDim <= 720) return "720P";
        if (minDim <= 1080) return "1080P";
        return "4K";
    }

    private static int[] parseDimensions(String size) {
        if (size == null) return null;
        String[] parts = size.trim().toLowerCase().split("[x*×]");
        if (parts.length != 2) return null;
        try {
            int w = Integer.parseInt(parts[0].trim());
            int h = Integer.parseInt(parts[1].trim());
            if (w <= 0 || h <= 0) return null;
            return new int[]{w, h};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 图片请求未携带 size 时的默认分辨率（OpenAI 默认值，对应 1K 档） */
    public static final String DEFAULT_IMAGE_SIZE = "1024x1024";

    /**
     * 图片模型积分消耗 = 对应档位单价 × 图片数量。
     * size 缺省时按 {@link #DEFAULT_IMAGE_SIZE} 计；无法识别的 size 直接拒绝，不允许按低档位漏计费。
     */
    public BigDecimal calculateImageCreditCost(com.aiconnecting.entity.ModelConfig config, String size, int n) {
        return resolveImageUnitPrice(config, size).multiply(BigDecimal.valueOf(Math.max(n, 1)));
    }

    /**
     * 解析图片单张单价（按分辨率档位），供按实际返回张数结算时重新计价
     */
    public BigDecimal resolveImageUnitPrice(com.aiconnecting.entity.ModelConfig config, String size) {
        String tier = resolveImageTier(size == null || size.isBlank() ? DEFAULT_IMAGE_SIZE : size);
        if (tier == null) {
            throw new BusinessException(400, "不支持的图片分辨率: " + size + "，请使用如 1024x1024 的宽x高格式");
        }
        BigDecimal price = switch (tier) {
            case "2K" -> config.getImagePrice2k();
            case "4K" -> config.getImagePrice4k();
            default -> config.getImagePrice1k();
        };
        return price != null ? price : BigDecimal.ZERO;
    }

    /**
     * 解析视频档位单价（积分/秒），供预扣时随任务保存原始单价，及结算时按提交时价格重新计价，
     * 避免管理员事后改价影响已提交任务的结算
     */
    public BigDecimal resolveVideoUnitPrice(com.aiconnecting.entity.ModelConfig config, String size) {
        String tier = resolveVideoTier(size);
        if (tier == null) {
            throw new BusinessException(400, "视频请求缺少或包含不支持的分辨率参数 (size/resolution): " + size
                    + "，支持 480p/720p/1080p/4k 或宽x高格式");
        }
        BigDecimal price = switch (tier) {
            case "720P" -> config.getVideoPrice720p();
            case "1080P" -> config.getVideoPrice1080p();
            case "4K" -> config.getVideoPrice4k();
            default -> config.getVideoPrice480p();
        };
        return price != null ? price : BigDecimal.ZERO;
    }

    /**
     * 视频模型积分消耗 = 对应分辨率档位单价 × 时长（秒）。
     * 分辨率无法识别或时长非法时直接拒绝，不允许按低档位漏计费。
     */
    public BigDecimal calculateVideoCreditCost(com.aiconnecting.entity.ModelConfig config, String size, int durationSeconds) {
        if (durationSeconds <= 0) {
            throw new BusinessException(400, "视频请求缺少有效的时长参数 (duration/seconds)，需为正整数秒数");
        }
        return resolveVideoUnitPrice(config, size).multiply(BigDecimal.valueOf(durationSeconds));
    }

    /**
     * 解析音频 quality 参数对应的音质档位。standard/sd → STANDARD, hd/high → HD；
     * 未传（null）时按 STANDARD 档计（OpenAI 默认音质）；传了但为空或无法识别的值
     * 返回 null（由调用方拒绝请求），不把空字符串当作有效默认值。
     */
    public static String resolveAudioTier(String quality) {
        if (quality == null) return "STANDARD";
        String q = quality.trim().toLowerCase();
        if (q.isEmpty()) return null;
        switch (q) {
            case "standard", "sd", "default": return "STANDARD";
            case "hd", "high": return "HD";
        }
        return null;
    }

    /**
     * 音频模型积分消耗 = 对应音质档位单价 × 时长（秒）。
     * 音质无法识别或时长非法时直接拒绝，不允许按低档位漏计费。
     */
    public BigDecimal calculateAudioCreditCost(com.aiconnecting.entity.ModelConfig config, String quality, int durationSeconds) {
        String tier = resolveAudioTier(quality);
        if (tier == null) {
            throw new BusinessException(400, "不支持的音频音质参数 (quality): " + quality + "，支持 standard/hd");
        }
        if (durationSeconds <= 0) {
            throw new BusinessException(400, "音频请求缺少有效的时长参数 (duration/seconds)，需为正整数秒数");
        }
        BigDecimal price = "HD".equals(tier) ? config.getAudioPriceHd() : config.getAudioPriceStandard();
        if (price == null) price = BigDecimal.ZERO;
        return price.multiply(BigDecimal.valueOf(durationSeconds));
    }

    /**
     * 分页查询使用日志
     */
    public Page<UsageLog> getLogs(int page, int size) {
        return usageLogRepository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    /**
     * 按 Token ID 列表聚合查询全部指标
     */
    public Object[] sumAllMetricsByTokenIds(List<Long> tokenIds) {
        List<Object[]> result = usageLogRepository.sumAllMetricsByTokenIds(tokenIds);
        return result.isEmpty() ? new Object[]{0L, 0L, 0L, 0L, 0.0, 0L} : result.get(0);
    }

    /**
     * 按 Token ID 列表聚合查询今日指标
     */
    public Object[] sumAllMetricsByTokenIdsSince(List<Long> tokenIds, LocalDateTime since) {
        List<Object[]> result = usageLogRepository.sumAllMetricsByTokenIdsSince(tokenIds, since);
        return result.isEmpty() ? new Object[]{0L, 0L, 0L, 0L, 0.0, 0L} : result.get(0);
    }

    /**
     * 查询指定 Token 的每日消耗积分
     */
    public List<Object[]> getDailyCreditCost(Long tokenId, LocalDateTime since) {
        return usageLogRepository.findDailyCreditCostByTokenIdSince(tokenId, since);
    }

    /**
     * 查询指定 Token 在指定日期的消耗记录明细（含输入/输出 token 数）
     */
    public List<Object[]> getLogDetails(Long tokenId, String date) {
        return usageLogRepository.findLogDetailsByTokenIdAndDate(tokenId, date);
    }

    /**
     * 按 Token ID 列表查询缓存创建/读取 token 统计（全部时间）
     */
    public long[] getCacheStats(List<Long> tokenIds) {
        List<Object[]> result = usageLogRepository.sumCacheTokensByTokenIds(tokenIds);
        if (result.isEmpty()) return new long[]{0, 0};
        Object[] row = result.get(0);
        return new long[]{((Number) row[0]).longValue(), ((Number) row[1]).longValue()};
    }

    /**
     * 按 Token ID 列表查询缓存创建/读取 token 统计（指定时间起）
     */
    public long[] getCacheStatsSince(List<Long> tokenIds, LocalDateTime since) {
        List<Object[]> result = usageLogRepository.sumCacheTokensByTokenIdsSince(tokenIds, since);
        if (result.isEmpty()) return new long[]{0, 0};
        Object[] row = result.get(0);
        return new long[]{((Number) row[0]).longValue(), ((Number) row[1]).longValue()};
    }

    /**
     * 全局缓存创建/读取 token 统计（全部时间）
     */
    public long[] getGlobalCacheStats() {
        List<Object[]> result = usageLogRepository.sumCacheTokensGlobal();
        if (result.isEmpty()) return new long[]{0, 0};
        Object[] row = result.get(0);
        return new long[]{((Number) row[0]).longValue(), ((Number) row[1]).longValue()};
    }

    /**
     * 全局缓存创建/读取 token 统计（指定时间起）
     */
    public long[] getGlobalCacheStatsSince(LocalDateTime since) {
        List<Object[]> result = usageLogRepository.sumCacheTokensGlobalSince(since);
        if (result.isEmpty()) return new long[]{0, 0};
        Object[] row = result.get(0);
        return new long[]{((Number) row[0]).longValue(), ((Number) row[1]).longValue()};
    }

    /**
     * 仪表盘每日统计 - admin 看全局（每日积分从 usage_stats 汇总表），普通用户看自己的数据
     */
    public DashboardDailyStats getDailyStats(User currentUser, int days) {
        LocalDateTime since = LocalDate.now().minusDays(days).atStartOfDay();
        String sinceDate = LocalDate.now().minusDays(days).format(DateTimeFormatter.ISO_LOCAL_DATE);
        List<Long> tokenIds = null;
        boolean isAdmin = "admin".equalsIgnoreCase(currentUser.getRole());

        if (!isAdmin) {
            tokenIds = tokenService.getUserTokenIds(currentUser.getId());
            if (tokenIds.isEmpty()) {
                return DashboardDailyStats.builder()
                        .dailyCredits(List.of())
                        .dailyTokensByModel(List.of())
                        .build();
            }
        }

        DailyStatsCacheKey cacheKey = new DailyStatsCacheKey(isAdmin, tokenIds, days);
        CachedDailyStats cached = dailyStatsCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.stats();
        }

        // 每日积分消耗 —— admin 从 usage_stats 汇总表读取，用户从 usage_logs 读取
        List<Object[]> creditRows;
        if (isAdmin) {
            creditRows = usageStatsRepository.sumGroupByDateSince(sinceDate);
        } else {
            creditRows = usageLogRepository.findDailyCreditCostByTokenIdsSince(tokenIds, since);
        }

        // 每日按模型 token 消耗 —— 都需要从 usage_logs 读取（汇总表无 model 维度）
        List<Object[]> tokenRows;
        if (isAdmin) {
            tokenRows = usageLogRepository.findDailyTokenByModelSince(since);
        } else {
            tokenRows = usageLogRepository.findDailyTokenByModelByTokenIdsSince(tokenIds, since);
        }

        // 每日按模型名统计积分消耗，再在服务层合并为按类型（text/image/video/audio）—— 用于每日消耗积分柱状图 tooltip
        List<Object[]> creditByModelRows = isAdmin
                ? usageLogRepository.findDailyCreditByModelSince(since)
                : usageLogRepository.findDailyCreditByModelByTokenIdsSince(tokenIds, since);
        Map<String, Map<String, BigDecimal>> creditsByTypeByDate = mergeDailyCreditsByModelToType(creditByModelRows);

        List<DashboardDailyStats.DailyCreditStat> dailyCredits = creditRows.stream()
                .map(row -> {
                    String date = (String) row[0];
                    return DashboardDailyStats.DailyCreditStat.builder()
                            .date(date)
                            .credits(BigDecimal.valueOf(((Number) row[1]).doubleValue()))
                            .creditsByType(creditsByTypeByDate.getOrDefault(date, defaultCreditsByType()))
                            .build();
                })
                .toList();

        Map<String, ModelConfigService.ModelInfo> modelInfoMap = modelConfigService.getModelInfoMap();
        List<DashboardDailyStats.DailyTokenByModelStat> dailyTokensByModel = tokenRows.stream()
                .map(row -> {
                    String model = (String) row[1];
                    long inputTokens = ((Number) row[2]).longValue();
                    long cachedTokens = ((Number) row[3]).longValue();
                    long totalTokens = ((Number) row[4]).longValue();
                    ModelConfigService.ModelInfo info = modelInfoMap.get(model);
                    String displayName = (info != null && info.displayName() != null && !info.displayName().isBlank())
                            ? info.displayName() : model;
                    return DashboardDailyStats.DailyTokenByModelStat.builder()
                            .date((String) row[0])
                            .model(model)
                            .displayName(displayName)
                            .inputTokens(inputTokens)
                            .cachedTokens(cachedTokens)
                            .cacheMissTokens(inputTokens - cachedTokens)
                            .totalTokens(totalTokens)
                            .build();
                })
                .toList();

        DashboardDailyStats stats = DashboardDailyStats.builder()
                .dailyCredits(dailyCredits)
                .dailyTokensByModel(dailyTokensByModel)
                .build();
        dailyStatsCache.put(cacheKey, new CachedDailyStats(stats, System.currentTimeMillis()));
        return stats;
    }

    private static Map<String, BigDecimal> defaultCreditsByType() {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (String type : MODEL_TYPES) map.put(type, BigDecimal.ZERO);
        return map;
    }

    /**
     * 全局按模型类型统计积分消耗（今日/累计等，取决于 since），用于仪表盘卡片 tooltip。
     * 按模型名查询后在服务层合并为类型，避免 JOIN model_configs 产生的笛卡尔积。
     */
    public Map<String, BigDecimal> getCreditsByTypeSince(LocalDateTime since) {
        return mergeCreditsByModelToType(usageLogRepository.findCreditByModelSince(since));
    }

    /**
     * 按 Token ID 列表统计按模型类型积分消耗
     */
    public Map<String, BigDecimal> getCreditsByTypeByTokenIdsSince(List<Long> tokenIds, LocalDateTime since) {
        return mergeCreditsByModelToType(usageLogRepository.findCreditByModelByTokenIdsSince(tokenIds, since));
    }

    /**
     * 将按模型名分组的积分统计（model, credit）合并为按类型（text/image/video/audio）统计。
     * 未在 model_configs 中找到对应名称的模型会被忽略（与原先 INNER JOIN 行为一致）。
     */
    private Map<String, BigDecimal> mergeCreditsByModelToType(List<Object[]> modelRows) {
        Map<String, ModelConfigService.ModelInfo> modelInfoMap = modelConfigService.getModelInfoMap();
        Map<String, BigDecimal> result = defaultCreditsByType();
        for (Object[] row : modelRows) {
            String model = (String) row[0];
            ModelConfigService.ModelInfo info = modelInfoMap.get(model);
            if (info == null) continue;
            BigDecimal credit = BigDecimal.valueOf(((Number) row[1]).doubleValue());
            result.merge(info.type(), credit, BigDecimal::add);
        }
        return result;
    }

    /**
     * 将按 (date, model) 分组的积分统计合并为按 (date, type) 统计。
     * 未在 model_configs 中找到对应名称的模型会被忽略（与原先 INNER JOIN 行为一致）。
     */
    private Map<String, Map<String, BigDecimal>> mergeDailyCreditsByModelToType(List<Object[]> modelRows) {
        Map<String, ModelConfigService.ModelInfo> modelInfoMap = modelConfigService.getModelInfoMap();
        Map<String, Map<String, BigDecimal>> result = new HashMap<>();
        for (Object[] row : modelRows) {
            String date = (String) row[0];
            String model = (String) row[1];
            ModelConfigService.ModelInfo info = modelInfoMap.get(model);
            if (info == null) continue;
            BigDecimal credit = BigDecimal.valueOf(((Number) row[2]).doubleValue());
            result.computeIfAbsent(date, d -> defaultCreditsByType()).merge(info.type(), credit, BigDecimal::add);
        }
        return result;
    }

}
