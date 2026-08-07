package com.aiconnecting.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 仪表盘查询性能索引迁移：为存量数据库补齐 model_configs.display_name 唯一索引
 * （display_name 是平台侧模型唯一标识，name 是发往上游供应商的模型 ID，允许重复），
 * 以及 usage_logs (model, created_at) 复合索引
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashboardIndexMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        boolean mysql = DbDialectUtil.isMysql(jdbcTemplate);

        List<Map<String, Object>> duplicates = jdbcTemplate.queryForList(
                "SELECT display_name, COUNT(*) as c FROM model_configs " +
                        "WHERE display_name IS NOT NULL AND display_name != '' " +
                        "GROUP BY display_name HAVING COUNT(*) > 1");
        if (duplicates.isEmpty()) {
            createIndexIfAbsent(mysql, "model_configs", "idx_model_configs_display_name",
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_model_configs_display_name ON model_configs (display_name)",
                    "CREATE UNIQUE INDEX idx_model_configs_display_name ON model_configs (display_name)");
        } else {
            log.warn("Skipping unique index on model_configs.display_name: found {} duplicate display_name(s): {}. " +
                            "Resolve duplicates before the unique index can be created.",
                    duplicates.size(), duplicates);
        }

        createIndexIfAbsent(mysql, "usage_logs", "idx_usage_logs_model_created",
                "CREATE INDEX IF NOT EXISTS idx_usage_logs_model_created ON usage_logs (model, created_at)",
                "CREATE INDEX idx_usage_logs_model_created ON usage_logs (model, created_at)");

        log.info("Dashboard index migration complete");
    }

    /**
     * MySQL 的 CREATE INDEX 不支持 IF NOT EXISTS，需先查 information_schema.statistics 判断索引是否已存在；
     * SQLite 继续使用原有的 IF NOT EXISTS 语句，逻辑不变。
     */
    private void createIndexIfAbsent(boolean mysql, String table, String indexName, String sqliteSql, String mysqlSql) {
        if (mysql) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.statistics " +
                            "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                    Integer.class, table, indexName);
            if (count == null || count == 0) {
                jdbcTemplate.execute(mysqlSql);
            }
        } else {
            jdbcTemplate.execute(sqliteSql);
        }
    }
}
