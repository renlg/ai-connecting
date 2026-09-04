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

/** 为存量 tokens 表补齐仅用于安全展示的 Token Key 掩码列。 */
@Slf4j
@Component
@RequiredArgsConstructor
@DependsOn("mysqlSchemaInitializer")
public class TokenKeyMaskMigrationRunner {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        boolean mysql = DbDialectUtil.isMysql(jdbcTemplate);
        Set<String> existing = DbDialectUtil.existingColumns(jdbcTemplate, mysql, "tokens");
        if (existing.isEmpty() || existing.contains("key_mask")) {
            return;
        }

        try {
            jdbcTemplate.execute("ALTER TABLE tokens ADD COLUMN key_mask TEXT");
            log.info("Token key mask migration: added tokens.key_mask");
        } catch (DataAccessException e) {
            if (DbDialectUtil.isDuplicateColumnError(e, mysql)) {
                log.debug("Column tokens.key_mask already added by another instance, skip: {}", e.getMessage());
                return;
            }
            throw e;
        }
    }

    @Configuration
    static class TokenKeyMaskMigrationDependsOnConfig {

        @Bean
        static BeanFactoryPostProcessor tokenKeyMaskMigrationEntityManagerFactoryDependsOnPostProcessor() {
            return new EntityManagerFactoryDependsOnPostProcessor("tokenKeyMaskMigrationRunner");
        }
    }
}
