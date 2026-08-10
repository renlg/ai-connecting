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
 * 必须在 Hibernate 完成 schema 创建（EntityManagerFactory 已就绪）之后运行，而不是之前：
 * usage_stats 实体已经在 {@code @Table(indexes = ...)} 中声明了这个唯一索引，但 SQLite 下全新库
 * 首次启动时，ddl-auto=update 建表这一步本身也是在容器刷新过程中才发生的——如果本 Runner
 * 在那之前运行，会看到表还不存在而直接跳过，导致首次启动完全没有创建唯一索引（要等第二次启动
 * 表已存在才会补建），完全起不到防止应用层去重失效时重复写入的作用。因此改用
 * {@link ApplicationRunner}，在 {@code SpringApplication.run} 完成上下文刷新（含 Hibernate
 * 建表）之后才执行，此时无论 SQLite 全新建表还是 MySQL 存量表，目标表必然已经存在。
 * MySQL 下 ddl-auto=validate 不会自建索引，索引创建完全由本 Runner 负责（schema-mysql.sql 的
 * UNIQUE KEY 定义只对全新建表生效，不会给已存在的表补索引）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UsageStatsIndexMigrationRunner implements ApplicationRunner {

    private static final int MYSQL_DUPLICATE_KEY_NAME_ERROR_CODE = 1061;
    private static final String INDEX_NAME = "uk_usage_stats_window";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        boolean mysql = DbDialectUtil.isMysql(jdbcTemplate);
        Set<String> existingColumns = DbDialectUtil.existingColumns(jdbcTemplate, mysql, "usage_stats");
        if (existingColumns.isEmpty()) {
            // 防御性判断：正常情况下 Hibernate/schema-mysql.sql 已在上下文刷新阶段建表，这里不应该发生。
            log.warn("usage_stats table not found when running index migration after context refresh, skip");
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
}
