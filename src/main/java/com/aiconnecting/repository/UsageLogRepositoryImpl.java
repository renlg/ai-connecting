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
import java.time.ZoneOffset;
import java.util.List;

/**
 * {@link UsageLogRepository} 中按日期分桶的原生查询实现：SQLite 用 datetime()/unixepoch 函数把
 * 毫秒时间戳转换为 "+8 hours" 偏移的日期，MySQL 的 created_at 是 DATETIME 且写入时已经是本地
 * （Asia/Shanghai）挂钟时间（JDBC URL 的 serverTimezone 只影响驱动的时区解释，不代表存储值需要
 * 再偏移），因此 MySQL 分支直接在 created_at 上分桶，不做 +8 小时偏移；SQLite 分支存储的是
 * UTC 基准的 epoch 毫秒整数，所以需要 +8 小时把 UTC 转成本地日期。两个分支返回的行结构完全相同，
 * 由 {@link com.aiconnecting.service.UsageLogService} 统一按 Object[] 下标消费。
 *
 * `since` 参数在两个分支下的绑定类型不同：MySQL 的 created_at 是 DATETIME，按 LocalDateTime 绑定
 * 即可被 JDBC 驱动正确转换；SQLite 的 created_at 底层是 INTEGER（epoch 毫秒，UTC 基准），如果按
 * LocalDateTime 绑定，sqlite-jdbc 会把它转成 TEXT，导致 INTEGER >= TEXT 恒为 false、查询返回空结果，
 * 所以 SQLite 分支必须显式把 `since` 转成 epoch 毫秒（同一套 UTC 基准，与 JVM 写入 created_at 时的
 * 转换方式保持一致）。
 */
@Repository
@RequiredArgsConstructor
public class UsageLogRepositoryImpl implements UsageLogRepositoryCustom {

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Override
    public List<Object[]> findDailyCreditCostByTokenIdSince(Long tokenId, LocalDateTime since) {
        boolean mysql = DbDialectUtil.isMysql(jdbcTemplate);
        String sql = mysql
                ? "SELECT DATE_FORMAT(created_at, '%Y-%m-%d') as date, "
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
                .addValue("since", sinceParam(mysql, since));
        return query(sql, params);
    }

    @Override
    public List<Object[]> findLogDetailsByTokenIdAndDate(Long tokenId, String date) {
        String sql = DbDialectUtil.isMysql(jdbcTemplate)
                ? "SELECT id, model, prompt_tokens, completion_tokens, credit_cost, "
                        + "DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') as created_at "
                        + "FROM usage_logs WHERE token_id = :tokenId "
                        + "AND DATE_FORMAT(created_at, '%Y-%m-%d') = :date "
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
        boolean mysql = DbDialectUtil.isMysql(jdbcTemplate);
        String sql = mysql
                ? "SELECT DATE_FORMAT(created_at, '%Y-%m-%d') as date, "
                        + "COALESCE(SUM(credit_cost), 0) as credits "
                        + "FROM usage_logs WHERE token_id IN (:tokenIds) AND created_at >= :since GROUP BY date ORDER BY date ASC"
                : "SELECT DATE(datetime(created_at / 1000, 'unixepoch', '+8 hours')) as date, "
                        + "COALESCE(SUM(credit_cost), 0) as credits "
                        + "FROM usage_logs WHERE token_id IN (:tokenIds) AND created_at >= :since GROUP BY date ORDER BY date ASC";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tokenIds", tokenIds)
                .addValue("since", sinceParam(mysql, since));
        return query(sql, params);
    }

    @Override
    public List<Object[]> findDailyTokenByModelSince(LocalDateTime since) {
        boolean mysql = DbDialectUtil.isMysql(jdbcTemplate);
        String sql = mysql
                ? "SELECT DATE_FORMAT(created_at, '%Y-%m-%d') as date, model, "
                        + "COALESCE(SUM(prompt_tokens), 0), COALESCE(SUM(prompt_tokens_cache_hit), 0), COALESCE(SUM(total_tokens), 0) "
                        + "FROM usage_logs WHERE created_at >= :since AND model IN (SELECT name FROM model_configs WHERE type = 'text') "
                        + "GROUP BY date, model ORDER BY date ASC, model ASC"
                : "SELECT DATE(datetime(created_at / 1000, 'unixepoch', '+8 hours')) as date, model, "
                        + "COALESCE(SUM(prompt_tokens), 0), COALESCE(SUM(prompt_tokens_cache_hit), 0), COALESCE(SUM(total_tokens), 0) "
                        + "FROM usage_logs WHERE created_at >= :since AND model IN (SELECT name FROM model_configs WHERE type = 'text') "
                        + "GROUP BY date, model ORDER BY date ASC, model ASC";
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("since", sinceParam(mysql, since));
        return query(sql, params);
    }

    @Override
    public List<Object[]> findDailyTokenByModelByTokenIdsSince(List<Long> tokenIds, LocalDateTime since) {
        boolean mysql = DbDialectUtil.isMysql(jdbcTemplate);
        String sql = mysql
                ? "SELECT DATE_FORMAT(created_at, '%Y-%m-%d') as date, model, "
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
                .addValue("since", sinceParam(mysql, since));
        return query(sql, params);
    }

    @Override
    public List<Object[]> findDailyCreditByModelSince(LocalDateTime since) {
        boolean mysql = DbDialectUtil.isMysql(jdbcTemplate);
        String sql = mysql
                ? "SELECT DATE_FORMAT(created_at, '%Y-%m-%d') as date, model, "
                        + "COALESCE(SUM(credit_cost), 0) "
                        + "FROM usage_logs WHERE created_at >= :since GROUP BY date, model ORDER BY date ASC"
                : "SELECT DATE(datetime(created_at / 1000, 'unixepoch', '+8 hours')) as date, model, "
                        + "COALESCE(SUM(credit_cost), 0) "
                        + "FROM usage_logs WHERE created_at >= :since GROUP BY date, model ORDER BY date ASC";
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("since", sinceParam(mysql, since));
        return query(sql, params);
    }

    @Override
    public List<Object[]> findDailyCreditByModelByTokenIdsSince(List<Long> tokenIds, LocalDateTime since) {
        boolean mysql = DbDialectUtil.isMysql(jdbcTemplate);
        String sql = mysql
                ? "SELECT DATE_FORMAT(created_at, '%Y-%m-%d') as date, model, "
                        + "COALESCE(SUM(credit_cost), 0) "
                        + "FROM usage_logs WHERE token_id IN (:tokenIds) AND created_at >= :since GROUP BY date, model ORDER BY date ASC"
                : "SELECT DATE(datetime(created_at / 1000, 'unixepoch', '+8 hours')) as date, model, "
                        + "COALESCE(SUM(credit_cost), 0) "
                        + "FROM usage_logs WHERE token_id IN (:tokenIds) AND created_at >= :since GROUP BY date, model ORDER BY date ASC";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tokenIds", tokenIds)
                .addValue("since", sinceParam(mysql, since));
        return query(sql, params);
    }

    /**
     * MySQL 的 created_at 是 DATETIME，按 LocalDateTime 绑定即可；SQLite 的 created_at 底层是
     * INTEGER（epoch 毫秒，UTC 基准），必须转成 epoch 毫秒 long 绑定，否则 sqlite-jdbc 会把
     * LocalDateTime 转成 TEXT，导致 INTEGER >= TEXT 比较恒为 false。
     */
    private Object sinceParam(boolean mysql, LocalDateTime since) {
        return mysql ? since : since.toInstant(ZoneOffset.UTC).toEpochMilli();
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
