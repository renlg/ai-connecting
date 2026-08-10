package com.aiconnecting.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * usage_stats 聚合窗口去重迁移：为 (start_time, end_time) 补齐唯一索引 uk_usage_stats_window，
 * 防止多实例并发聚合在应用层去重失效时（{@link com.aiconnecting.common.RedisDistributedLock}
 * 锁失效/竞态）写入同一时间窗口的重复行。
 *
 * 存量库可能已经存在重复窗口的行（此前仅靠应用层 {@code existsByTimeRange} 去重，非数据库约束），
 * 因此启动时先探测重复：存在重复则只记录 WARN 并跳过建索引，避免把启动流程搞挂；
 * 数据干净时才创建唯一索引。
 *
 * 与 {@link ModelTypeMigrationRunner} 相同的原因，必须在 Hibernate 创建 EntityManagerFactory 之前运行：
 * usage_stats 实体已经在 {@code @Table(indexes = ...)} 中声明了这个唯一索引，SQLite 下
 * ddl-auto=update 会在容器刷新过程中尝试自动补建；本迁移需要先完成重复数据探测/幂等建索引，
 * 避免和 Hibernate 的自动建索引互相竞争。MySQL 下 ddl-auto=validate 不会自建索引，索引创建
 * 完全由本 Runner 负责（schema-mysql.sql 的 UNIQUE KEY 定义只对全新建表生效，不会给已存在的表补索引）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@DependsOn("mysqlSchemaInitializer")
public class UsageStatsIndexMigrationRunner {

    private static final int MYSQL_DUPLICATE_KEY_NAME_ERROR_CODE = 1061;
    private static final String INDEX_NAME = "uk_usage_stats_window";

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        boolean mysql = DbDialectUtil.isMysql(jdbcTemplate);
        Set<String> existingColumns = DbDialectUtil.existingColumns(jdbcTemplate, mysql, "usage_stats");
        if (existingColumns.isEmpty()) {
            // 表尚不存在，交由 Hibernate ddl-auto / schema-mysql.sql 按最新定义（含唯一索引）创建全新表
            return;
        }

        List<Map<String, Object>> duplicates = jdbcTemplate.queryForList(
                "SELECT start_time, end_time, COUNT(*) as c FROM usage_stats " +
                        "GROUP BY start_time, end_time HAVING COUNT(*) > 1");
        if (!duplicates.isEmpty()) {
            log.warn("Skipping unique index {} on usage_stats(start_time, end_time): found {} duplicate window(s): {}. " +
                            "Resolve duplicates before the unique index can be created; scheduled aggregation will keep " +
                            "relying on application-layer dedup in the meantime.",
                    INDEX_NAME, duplicates.size(), duplicates);
            return;
        }

        if (mysql) {
            createMysqlIndexIfAbsent();
        } else {
            jdbcTemplate.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " + INDEX_NAME + " ON usage_stats (start_time, end_time)");
        }
        log.info("usage_stats window unique index migration complete");
    }

    /**
     * MySQL 的 CREATE INDEX 不支持 IF NOT EXISTS，需先查 information_schema.statistics；
     * 多实例同时启动时先查后建仍存在竞态，后完成的一方会收到 1061 "Duplicate key name"，
     * 此时视为索引已被对方创建成功，忽略即可（与 {@link DashboardIndexMigrationRunner} 相同的处理方式）。
     */
    private void createMysqlIndexIfAbsent() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics " +
                        "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                Integer.class, "usage_stats", INDEX_NAME);
        if (count != null && count > 0) {
            return;
        }
        try {
            jdbcTemplate.execute(
                    "CREATE UNIQUE INDEX " + INDEX_NAME + " ON usage_stats (start_time, end_time)");
        } catch (DataAccessException e) {
            if (DbDialectUtil.mysqlErrorCode(e) == MYSQL_DUPLICATE_KEY_NAME_ERROR_CODE) {
                log.debug("Index {} already created by another instance, skip: {}", INDEX_NAME, e.getMessage());
            } else {
                throw e;
            }
        }
    }

    @Configuration
    static class UsageStatsIndexMigrationDependsOnConfig {

        @Bean
        static BeanFactoryPostProcessor usageStatsIndexMigrationEntityManagerFactoryDependsOnPostProcessor() {
            return new EntityManagerFactoryDependsOnPostProcessor("usageStatsIndexMigrationRunner");
        }
    }
}
