package com.aiconnecting.repository;

import com.aiconnecting.config.DbDialectUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link UsageLogRepository} 中按日期分桶的原生查询实现：SQLite 用 datetime()/unixepoch 函数把
 * 毫秒时间戳转换为 "+8 hours" 偏移的日期，MySQL 没有这两个函数，改用 DATE_ADD/DATE_FORMAT 达到
 * 同样的分桶效果（分组桶和四舍五入语义与 SQLite 分支保持一致）。两个分支返回的行结构完全相同，
 * 由 {@link com.aiconnecting.service.UsageLogService} 统一按 Object[] 下标消费。
 */
@Repository
@RequiredArgsConstructor
public class UsageLogRepositoryImpl implements UsageLogRepositoryCustom {

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Override
    public List<Object[]> findDailyCreditCostByTokenIdSince(Long tokenId, LocalDateTime since) {
        String sql = DbDialectUtil.isMysql(jdbcTemplate)
                ? "SELECT DATE_FORMAT(DATE_ADD(created_at, INTERVAL 8 HOUR), '%Y-%m-%d') as date, "
                        + "COALESCE(SUM(credit_cost), 0) as credits, "
                        + "COALESCE(SUM(prompt_tokens), 0) as input_tokens, "
                        + "COALESCE(SUM(completion_tokens), 0) as output_tokens "
                        + "FROM usage_logs WHERE token_id = :tokenId AND created_at >= :since "
                        + "GROUP BY date ORDER BY date DESC"
                : "SELECT DATE(datetime(created_at / 1000, 'unixepoch', '+8 hours')) as date, "
                        + "COALESCE(SUM(credit_cost), 0) as credits, "
                        + "COALESCE(SUM(prompt_tokens), 0) as input_tokens, "
                        + "COALESCE(SUM(completion_tokens), 0) as output_tokens "
                        + "FROM usage_logs WHERE token_id = :tokenId AND created_at >= :since "
                        + "GROUP BY DATE(datetime(created_at / 1000, 'unixepoch', '+8 hours')) ORDER BY date DESC";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tokenId", tokenId)
                .addValue("since", since);
        return query(sql, params);
    }

    @Override
    public List<Object[]> findLogDetailsByTokenIdAndDate(Long tokenId, String date) {
        String sql = DbDialectUtil.isMysql(jdbcTemplate)
                ? "SELECT id, model, prompt_tokens, completion_tokens, credit_cost, "
                        + "DATE_FORMAT(DATE_ADD(created_at, INTERVAL 8 HOUR), '%Y-%m-%d %H:%i:%s') as created_at "
                        + "FROM usage_logs WHERE token_id = :tokenId "
                        + "AND DATE_FORMAT(DATE_ADD(created_at, INTERVAL 8 HOUR), '%Y-%m-%d') = :date "
                        + "ORDER BY created_at DESC"
                : "SELECT id, model, prompt_tokens, completion_tokens, credit_cost, "
                        + "datetime(created_at / 1000, 'unixepoch', '+8 hours') as created_at "
                        + "FROM usage_logs WHERE token_id = :tokenId "
                        + "AND DATE(datetime(created_at / 1000, 'unixepoch', '+8 hours')) = :date "
                        + "ORDER BY created_at DESC";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tokenId", tokenId)
                .addValue("date", date);
        return query(sql, params);
    }

    @Override
    public List<Object[]> findDailyCreditCostByTokenIdsSince(List<Long> tokenIds, LocalDateTime since) {
        String sql = DbDialectUtil.isMysql(jdbcTemplate)
                ? "SELECT DATE_FORMAT(DATE_ADD(created_at, INTERVAL 8 HOUR), '%Y-%m-%d') as date, "
                        + "COALESCE(SUM(credit_cost), 0) as credits "
                        + "FROM usage_logs WHERE token_id IN (:tokenIds) AND created_at >= :since GROUP BY date ORDER BY date ASC"
                : "SELECT DATE(datetime(created_at / 1000, 'unixepoch', '+8 hours')) as date, "
                        + "COALESCE(SUM(credit_cost), 0) as credits "
                        + "FROM usage_logs WHERE token_id IN (:tokenIds) AND created_at >= :since GROUP BY date ORDER BY date ASC";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tokenIds", tokenIds)
                .addValue("since", since);
        return query(sql, params);
    }

    @Override
    public List<Object[]> findDailyTokenByModelSince(LocalDateTime since) {
        String sql = DbDialectUtil.isMysql(jdbcTemplate)
                ? "SELECT DATE_FORMAT(DATE_ADD(created_at, INTERVAL 8 HOUR), '%Y-%m-%d') as date, model, "
                        + "COALESCE(SUM(prompt_tokens), 0), COALESCE(SUM(prompt_tokens_cache_hit), 0), COALESCE(SUM(total_tokens), 0) "
                        + "FROM usage_logs WHERE created_at >= :since AND model IN (SELECT name FROM model_configs WHERE type = 'text') "
                        + "GROUP BY date, model ORDER BY date ASC, model ASC"
                : "SELECT DATE(datetime(created_at / 1000, 'unixepoch', '+8 hours')) as date, model, "
                        + "COALESCE(SUM(prompt_tokens), 0), COALESCE(SUM(prompt_tokens_cache_hit), 0), COALESCE(SUM(total_tokens), 0) "
                        + "FROM usage_logs WHERE created_at >= :since AND model IN (SELECT name FROM model_configs WHERE type = 'text') "
                        + "GROUP BY date, model ORDER BY date ASC, model ASC";
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("since", since);
        return query(sql, params);
    }

    @Override
    public List<Object[]> findDailyTokenByModelByTokenIdsSince(List<Long> tokenIds, LocalDateTime since) {
        String sql = DbDialectUtil.isMysql(jdbcTemplate)
                ? "SELECT DATE_FORMAT(DATE_ADD(created_at, INTERVAL 8 HOUR), '%Y-%m-%d') as date, model, "
                        + "COALESCE(SUM(prompt_tokens), 0), COALESCE(SUM(prompt_tokens_cache_hit), 0), COALESCE(SUM(total_tokens), 0) "
                        + "FROM usage_logs WHERE token_id IN (:tokenIds) AND created_at >= :since "
                        + "AND model IN (SELECT name FROM model_configs WHERE type = 'text') "
                        + "GROUP BY date, model ORDER BY date ASC, model ASC"
                : "SELECT DATE(datetime(created_at / 1000, 'unixepoch', '+8 hours')) as date, model, "
                        + "COALESCE(SUM(prompt_tokens), 0), COALESCE(SUM(prompt_tokens_cache_hit), 0), COALESCE(SUM(total_tokens), 0) "
                        + "FROM usage_logs WHERE token_id IN (:tokenIds) AND created_at >= :since "
                        + "AND model IN (SELECT name FROM model_configs WHERE type = 'text') "
                        + "GROUP BY date, model ORDER BY date ASC, model ASC";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tokenIds", tokenIds)
                .addValue("since", since);
        return query(sql, params);
    }

    @Override
    public List<Object[]> findDailyCreditByModelSince(LocalDateTime since) {
        String sql = DbDialectUtil.isMysql(jdbcTemplate)
                ? "SELECT DATE_FORMAT(DATE_ADD(created_at, INTERVAL 8 HOUR), '%Y-%m-%d') as date, model, "
                        + "COALESCE(SUM(credit_cost), 0) "
                        + "FROM usage_logs WHERE created_at >= :since GROUP BY date, model ORDER BY date ASC"
                : "SELECT DATE(datetime(created_at / 1000, 'unixepoch', '+8 hours')) as date, model, "
                        + "COALESCE(SUM(credit_cost), 0) "
                        + "FROM usage_logs WHERE created_at >= :since GROUP BY date, model ORDER BY date ASC";
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("since", since);
        return query(sql, params);
    }

    @Override
    public List<Object[]> findDailyCreditByModelByTokenIdsSince(List<Long> tokenIds, LocalDateTime since) {
        String sql = DbDialectUtil.isMysql(jdbcTemplate)
                ? "SELECT DATE_FORMAT(DATE_ADD(created_at, INTERVAL 8 HOUR), '%Y-%m-%d') as date, model, "
                        + "COALESCE(SUM(credit_cost), 0) "
                        + "FROM usage_logs WHERE token_id IN (:tokenIds) AND created_at >= :since GROUP BY date, model ORDER BY date ASC"
                : "SELECT DATE(datetime(created_at / 1000, 'unixepoch', '+8 hours')) as date, model, "
                        + "COALESCE(SUM(credit_cost), 0) "
                        + "FROM usage_logs WHERE token_id IN (:tokenIds) AND created_at >= :since GROUP BY date, model ORDER BY date ASC";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tokenIds", tokenIds)
                .addValue("since", since);
        return query(sql, params);
    }

    private List<Object[]> query(String sql, MapSqlParameterSource params) {
        return namedParameterJdbcTemplate.query(sql, params, this::mapRow);
    }

    private Object[] mapRow(ResultSet rs, int rowNum) throws SQLException {
        int columnCount = rs.getMetaData().getColumnCount();
        Object[] row = new Object[columnCount];
        for (int i = 0; i < columnCount; i++) {
            row[i] = rs.getObject(i + 1);
        }
        return row;
    }
}
