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
 * 仪表盘查询性能索引迁移：为存量数据库补齐 model_configs.name 唯一索引，
 * 以及 usage_logs (model, created_at) 复合索引
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashboardIndexMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        List<Map<String, Object>> duplicates = jdbcTemplate.queryForList(
                "SELECT name, COUNT(*) as c FROM model_configs GROUP BY name HAVING COUNT(*) > 1");
        if (duplicates.isEmpty()) {
            jdbcTemplate.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_model_configs_name ON model_configs (name)");
        } else {
            log.warn("Skipping unique index on model_configs.name: found {} duplicate name(s): {}. " +
                            "Resolve duplicates (e.g. deactivate/rename older rows) before the unique index can be created.",
                    duplicates.size(), duplicates);
        }

        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_usage_logs_model_created ON usage_logs (model, created_at)");

        log.info("Dashboard index migration complete");
    }
}
