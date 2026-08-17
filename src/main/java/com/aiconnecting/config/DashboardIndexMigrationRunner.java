package com.aiconnecting.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 仪表盘查询性能索引迁移：为存量数据库补齐 model_configs.display_name 唯一索引
 * （display_name 是平台侧模型唯一标识，name 是发往上游供应商的模型 ID，允许重复），
 * 以及 usage_logs (model, created_at) 复合索引；同时清理旧版建库脚本遗留的
 * model_configs.name 唯一索引（当前建库脚本已不再创建，但已初始化过的存量库不会自动补删，
 * 需要主动 DROP 一次，且要保证多次启动重复执行是幂等的）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashboardIndexMigrationRunner implements ApplicationRunner {

    private static final int MYSQL_DUPLICATE_KEY_NAME_ERROR_CODE = 1061;
    private static final int MYSQL_CANT_DROP_FIELD_OR_KEY_ERROR_CODE = 1091;

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

        if (mysql) {
            dropLegacyModelConfigsNameUniqueIndex();
        } else {
            jdbcTemplate.execute("DROP INDEX IF EXISTS idx_model_configs_name");
        }

        log.info("Dashboard index migration complete");
    }

    /**
     * 58d7b8a 初次上线时 schema-mysql.sql 还带着 model_configs.name 的唯一索引，后来发现 name
     * 允许重复（发往上游供应商的模型 ID 可以对应多个平台侧配置）而被移除；但 MysqlSchemaInitializer
     * 只会 CREATE TABLE IF NOT EXISTS，不会给已建过表的存量库删旧索引，所以这里主动清理一次。
     */
    private void dropLegacyModelConfigsNameUniqueIndex() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics " +
                        "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                Integer.class, "model_configs", "idx_model_configs_name");
        if (count == null || count == 0) {
            return;
        }
        try {
            jdbcTemplate.execute("DROP INDEX idx_model_configs_name ON model_configs");
            log.info("Dropped legacy unique index idx_model_configs_name on model_configs");
        } catch (DataAccessException e) {
            if (DbDialectUtil.mysqlErrorCode(e) == MYSQL_CANT_DROP_FIELD_OR_KEY_ERROR_CODE) {
                log.debug("idx_model_configs_name already dropped by another instance, skip: {}", e.getMessage());
            } else {
                throw e;
            }
        }
    }

    /**
     * MySQL 的 CREATE INDEX 不支持 IF NOT EXISTS，需先查 information_schema.statistics 判断索引是否已存在；
     * SQLite 继续使用原有的 IF NOT EXISTS 语句，逻辑不变。
     *
     * 先查后建存在多实例同时启动时的竞态：两个实例都可能查到 count=0 后同时尝试建索引，
     * 后完成的一方会收到 MySQL 1061 "Duplicate key name" 错误，此时视为索引已被对方创建成功，忽略即可。
     */
    private void createIndexIfAbsent(boolean mysql, String table, String indexName, String sqliteSql, String mysqlSql) {
        if (mysql) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.statistics " +
                            "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                    Integer.class, table, indexName);
            if (count == null || count == 0) {
                try {
                    jdbcTemplate.execute(mysqlSql);
                } catch (DataAccessException e) {
                    if (DbDialectUtil.mysqlErrorCode(e) == MYSQL_DUPLICATE_KEY_NAME_ERROR_CODE) {
                        log.debug("Index {} already created by another instance, skip: {}", indexName, e.getMessage());
                    } else {
                        throw e;
                    }
                }
            }
        } else {
            jdbcTemplate.execute(sqliteSql);
        }
    }
}
