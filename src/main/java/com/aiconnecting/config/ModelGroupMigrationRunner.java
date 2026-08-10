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

import java.util.Set;

/**
 * 模型组功能迁移：为存量 model_configs 补齐 fallback_group_id 列、为存量 usage_logs 补齐 actual_model 列。
 * model_groups / model_group_members 是全新表，交由 Hibernate ddl-auto（SQLite）或 schema-mysql.sql
 * 中的 CREATE TABLE IF NOT EXISTS（MySQL，见 {@code MysqlSchemaInitializer}）创建，此处无需处理。
 *
 * 与 {@link ModelTypeMigrationRunner} 相同的原因，必须在 Hibernate 创建 EntityManagerFactory
 * （ddl-auto=validate）之前运行，否则存量 MySQL 表缺列会导致 validate 直接启动失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@DependsOn("mysqlSchemaInitializer")
public class ModelGroupMigrationRunner {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        boolean mysql = DbDialectUtil.isMysql(jdbcTemplate);
        int added = 0;
        added += addColumnIfAbsent(mysql, "model_configs", "fallback_group_id", "BIGINT");
        added += addColumnIfAbsent(mysql, "usage_logs", "actual_model", "VARCHAR(100)");
        if (added > 0) {
            log.info("Model group migration: added {} column(s)", added);
        } else {
            log.info("Model group migration: schema already up to date");
        }
    }

    private int addColumnIfAbsent(boolean mysql, String table, String column, String ddlType) {
        Set<String> existing = DbDialectUtil.existingColumns(jdbcTemplate, mysql, table);
        if (existing.isEmpty()) {
            // 表尚不存在（SQLite 全新库场景），交由 Hibernate ddl-auto 按实体定义创建全新表
            return 0;
        }
        if (existing.contains(column)) {
            return 0;
        }
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + ddlType);
            return 1;
        } catch (DataAccessException e) {
            if (DbDialectUtil.isDuplicateColumnError(e, mysql)) {
                log.debug("Column {}.{} already added by another instance, skip: {}", table, column, e.getMessage());
                return 0;
            }
            throw e;
        }
    }

    @Configuration
    static class ModelGroupMigrationDependsOnConfig {

        @Bean
        static BeanFactoryPostProcessor modelGroupMigrationEntityManagerFactoryDependsOnPostProcessor() {
            return new EntityManagerFactoryDependsOnPostProcessor("modelGroupMigrationRunner");
        }
    }
}
