package com.aiconnecting.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 视频计费字段迁移：video_tasks 表在计费改造前已存在（不含预扣/结算相关列），
 * Hibernate ddl-auto=update 对已有非空表新增 NOT NULL 列可能在 SQLite 上失败，
 * 因此显式补齐列并给出安全默认值；幂等，可重复执行。
 * 存量的进行中任务补列后 deducted/settled 均为默认值 0，
 * 结算逻辑在 deducted=false 时不触碰余额，故对这些任务无财务影响，交由后续轮询/对账任务处理其终态。
 *
 * 必须在 Hibernate 初始化 EntityManagerFactory（此时会执行 ddl-auto=update）之前运行，
 * 否则 Hibernate 可能先对存量表尝试新增 NOT NULL 列而在 SQLite 上启动失败，本迁移永远得不到执行的机会。
 * ApplicationRunner/CommandLineRunner 均在容器刷新完成、EntityManagerFactory 已创建之后才执行，无法满足此要求，
 * 因此改为在 @PostConstruct 中立即执行迁移，并通过 {@link EntityManagerFactoryDependsOnPostProcessor} 让
 * entityManagerFactory bean 显式 dependsOn 本 bean，强制其在 Hibernate 之前完成初始化。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoTaskMigrationRunner {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        Set<String> existing = jdbcTemplate.queryForList("PRAGMA table_info(video_tasks)").stream()
                .map(row -> String.valueOf(row.get("name")).toLowerCase())
                .collect(Collectors.toSet());
        if (existing.isEmpty()) {
            // 表尚不存在，交由 Hibernate ddl-auto 按实体定义创建全新表
            return;
        }

        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("prepaid_cost", "DECIMAL(10,2)");
        columns.put("deducted", "BOOLEAN NOT NULL DEFAULT 0");
        columns.put("size", "VARCHAR(50)");
        columns.put("duration_seconds", "INTEGER");
        columns.put("unit_price", "DECIMAL(10,4)");
        columns.put("usage_log_id", "BIGINT");
        columns.put("settled", "BOOLEAN NOT NULL DEFAULT 0");
        columns.put("next_reconcile_at", "TIMESTAMP");

        int added = 0;
        for (Map.Entry<String, String> entry : columns.entrySet()) {
            if (!existing.contains(entry.getKey())) {
                jdbcTemplate.execute("ALTER TABLE video_tasks ADD COLUMN " + entry.getKey() + " " + entry.getValue());
                added++;
            }
        }

        if (added > 0) {
            log.info("Video task migration: added {} column(s) to video_tasks; "
                    + "pre-existing in-flight rows default to deducted=0/settled=0 (no balance impact, "
                    + "picked up by status poll / reconciliation job)", added);
        } else {
            log.info("Video task migration: schema already up to date");
        }
    }

    /**
     * 让 entityManagerFactory 显式依赖 {@link VideoTaskMigrationRunner}，
     * 保证其 @PostConstruct 迁移逻辑在 Hibernate 创建 EntityManagerFactory（进而执行 ddl-auto）之前完成。
     * 与 Spring Boot 自身让 Flyway/Liquibase 先于 JPA 运行所用的机制相同。
     */
    @Configuration
    static class VideoTaskMigrationDependsOnConfig {

        @Bean
        static BeanFactoryPostProcessor videoTaskMigrationEntityManagerFactoryDependsOnPostProcessor() {
            return new EntityManagerFactoryDependsOnPostProcessor("videoTaskMigrationRunner");
        }
    }
}
