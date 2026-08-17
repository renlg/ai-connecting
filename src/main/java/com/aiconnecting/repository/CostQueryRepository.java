package com.aiconnecting.repository;

import com.aiconnecting.config.DbDialectUtil;
import com.aiconnecting.dto.CostAggregateRow;
import com.aiconnecting.dto.CostSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 成本核算专用只读查询。时间条件始终直接作用于 usage_logs.created_at，避免在索引列上套函数。
 */
@Repository
@RequiredArgsConstructor
public class CostQueryRepository {
    private static final String DISPLAY_MODEL = "COALESCE(NULLIF(ul.actual_model, ''), ul.model)";
    private static final String FILTER_BASE = " FROM usage_logs ul "
            + "LEFT JOIN channels c ON c.id = ul.channel_id "
            + "WHERE ul.created_at BETWEEN :startTime AND :endTime ";

    private static final String VIDEO_SECONDS = "COALESCE(SUM(CASE WHEN ul.request_path LIKE '%videos%' THEN "
            + "COALESCE((SELECT SUM(vt.duration_seconds) FROM video_tasks vt WHERE vt.usage_log_id = ul.id), 0) "
            + "ELSE 0 END), 0)";

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    public List<CostAggregateRow> findRows(LocalDateTime startTime, LocalDateTime endTime,
                                           Long channelId, String modelName, Integer page, Integer size) {
        MapSqlParameterSource params = params(startTime, endTime, channelId, modelName);
        String sql = "SELECT ul.channel_id, MAX(c.name), " + DISPLAY_MODEL + ", "
                + "COALESCE(SUM(ul.prompt_tokens), 0), COALESCE(SUM(ul.completion_tokens), 0), "
                + "COALESCE(SUM(ul.cached_tokens_cache_creation), 0), "
                + "COALESCE(SUM(ul.cached_tokens_cache_read), 0), COUNT(*), "
                + "COALESCE(SUM(CASE WHEN ul.request_path LIKE '%images/generations%' THEN 1 ELSE 0 END), 0), "
                + "COALESCE(SUM(CASE WHEN ul.request_path LIKE '%videos%' THEN 1 ELSE 0 END), 0), "
                + VIDEO_SECONDS + ", COALESCE(SUM(ul.credit_cost), 0) AS total_credit_cost "
                + filter(channelId, modelName) + "GROUP BY ul.channel_id, " + DISPLAY_MODEL + " "
                + "ORDER BY total_credit_cost DESC, ul.channel_id ASC, " + DISPLAY_MODEL + " ASC";
        if (page != null && size != null) {
            sql += " LIMIT :limit OFFSET :offset";
            params.addValue("limit", size).addValue("offset", (long) page * size);
        }
        return namedJdbcTemplate.query(sql, params, this::mapRow);
    }

    public long countRows(LocalDateTime startTime, LocalDateTime endTime, Long channelId, String modelName) {
        String sql = "SELECT COUNT(*) FROM (SELECT 1" + filter(channelId, modelName)
                + "GROUP BY ul.channel_id, " + DISPLAY_MODEL + ") cost_groups";
        Long count = namedJdbcTemplate.queryForObject(sql, params(startTime, endTime, channelId, modelName), Long.class);
        return count == null ? 0 : count;
    }

    public CostSummary summarize(LocalDateTime startTime, LocalDateTime endTime,
                                 Long channelId, String modelName) {
        String sql = "SELECT COALESCE(SUM(ul.prompt_tokens), 0), "
                + "COALESCE(SUM(ul.completion_tokens), 0), "
                + "COALESCE(SUM(ul.cached_tokens_cache_creation), 0), "
                + "COALESCE(SUM(ul.cached_tokens_cache_read), 0), COUNT(*), "
                + "COALESCE(SUM(CASE WHEN ul.request_path LIKE '%images/generations%' THEN 1 ELSE 0 END), 0), "
                + VIDEO_SECONDS + ", COALESCE(SUM(ul.credit_cost), 0)" + filter(channelId, modelName);
        return namedJdbcTemplate.queryForObject(sql, params(startTime, endTime, channelId, modelName), (rs, rowNum) ->
                CostSummary.builder()
                        .totalPromptTokens(longValue(rs, 1))
                        .totalCompletionTokens(longValue(rs, 2))
                        .totalCacheCreation(longValue(rs, 3))
                        .totalCacheRead(longValue(rs, 4))
                        .requestCount(longValue(rs, 5))
                        .imageCount(longValue(rs, 6))
                        .videoSeconds(longValue(rs, 7))
                        .totalCreditCost(decimalValue(rs.getObject(8)))
                        .build());
    }

    public List<String> findModelOptions(LocalDateTime startTime, LocalDateTime endTime, Long channelId) {
        String sql = "SELECT DISTINCT " + DISPLAY_MODEL + filter(channelId, null)
                + "AND " + DISPLAY_MODEL + " IS NOT NULL "
                + "AND " + DISPLAY_MODEL + " <> '' ORDER BY " + DISPLAY_MODEL;
        return namedJdbcTemplate.queryForList(sql, params(startTime, endTime, channelId, null), String.class);
    }

    private MapSqlParameterSource params(LocalDateTime startTime, LocalDateTime endTime,
                                         Long channelId, String modelName) {
        boolean mysql = DbDialectUtil.isMysql(jdbcTemplate);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("startTime", mysql ? startTime
                        : startTime.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli())
                .addValue("endTime", mysql ? endTime
                        : endTime.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());
        if (channelId != null) params.addValue("channelId", channelId);
        if (modelName != null && !modelName.isBlank()) params.addValue("modelName", modelName.trim());
        return params;
    }

    private static String filter(Long channelId, String modelName) {
        StringBuilder sql = new StringBuilder(FILTER_BASE);
        if (channelId != null) sql.append("AND ul.channel_id = :channelId ");
        if (modelName != null && !modelName.isBlank()) {
            sql.append("AND ").append(DISPLAY_MODEL).append(" = :modelName ");
        }
        return sql.toString();
    }

    private CostAggregateRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        long imageCount = longValue(rs, 9);
        long videoRequestCount = longValue(rs, 10);
        long videoSeconds = longValue(rs, 11);
        String channelName = rs.getString(2);
        Long channelId = rs.getObject(1) == null ? null : rs.getLong(1);
        return CostAggregateRow.builder()
                .channelId(channelId)
                .channelName(channelName != null && !channelName.isBlank()
                        ? channelName : (channelId == null ? "未知渠道" : "渠道 #" + channelId))
                .model(rs.getString(3))
                .totalPromptTokens(longValue(rs, 4))
                .totalCompletionTokens(longValue(rs, 5))
                .totalCacheCreation(longValue(rs, 6))
                .totalCacheRead(longValue(rs, 7))
                .requestCount(longValue(rs, 8))
                .imageCount(imageCount)
                .videoSeconds(videoSeconds)
                .modelType(imageCount > 0 ? "image" : (videoRequestCount > 0 ? "video" : "text"))
                .totalCreditCost(decimalValue(rs.getObject(12)))
                .build();
    }

    private static long longValue(ResultSet rs, int column) throws SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? 0 : value.longValue();
    }

    private static BigDecimal decimalValue(Object value) {
        if (value == null) return BigDecimal.ZERO.setScale(4);
        BigDecimal decimal = value instanceof BigDecimal bd ? bd : new BigDecimal(value.toString());
        return decimal.setScale(4, RoundingMode.HALF_UP);
    }

}
