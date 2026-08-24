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
 * 为存量失败策略表补齐排除 HTTP 状态码配置列。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@DependsOn("mysqlSchemaInitializer")
public class FailureStrategyMigrationRunner {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        boolean mysql = DbDialectUtil.isMysql(jdbcTemplate);
        Set<String> existing = DbDialectUtil.existingColumns(jdbcTemplate, mysql, "failure_strategies");
        if (existing.isEmpty() || existing.contains("excluded_http_codes")) {
            return;
        }

        try {
            jdbcTemplate.execute(
                    "ALTER TABLE failure_strategies ADD COLUMN excluded_http_codes VARCHAR(200)");
            log.info("Failure strategy migration: added excluded_http_codes column");
        } catch (DataAccessException e) {
            if (DbDialectUtil.isDuplicateColumnError(e, mysql)) {
                log.debug("Column failure_strategies.excluded_http_codes already added by another instance, skip: {}",
                        e.getMessage());
                return;
            }
            throw e;
        }
    }

    @Configuration
    static class FailureStrategyMigrationDependsOnConfig {

        @Bean
        static BeanFactoryPostProcessor failureStrategyMigrationEntityManagerFactoryDependsOnPostProcessor() {
            return new EntityManagerFactoryDependsOnPostProcessor("failureStrategyMigrationRunner");
        }
    }
}
