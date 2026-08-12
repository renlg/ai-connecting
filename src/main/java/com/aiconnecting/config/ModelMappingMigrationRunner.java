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

/** Adds channels.model_mapping for existing MySQL databases before Hibernate validation runs. */
@Slf4j
@Component
@RequiredArgsConstructor
@DependsOn("mysqlSchemaInitializer")
public class ModelMappingMigrationRunner {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        if (!DbDialectUtil.isMysql(jdbcTemplate)) {
            return;
        }
        Set<String> columns = DbDialectUtil.existingColumns(jdbcTemplate, true, "channels");
        if (columns.isEmpty() || columns.contains("model_mapping")) {
            return;
        }
        try {
            jdbcTemplate.execute("ALTER TABLE channels ADD COLUMN model_mapping TEXT");
            log.info("Model mapping migration: added channels.model_mapping");
        } catch (DataAccessException e) {
            if (DbDialectUtil.isDuplicateColumnError(e, true)) {
                log.debug("channels.model_mapping was added by another instance, skip");
            } else {
                throw e;
            }
        }
    }

    @Configuration
    static class ModelMappingMigrationDependsOnConfig {
        @Bean
        static BeanFactoryPostProcessor modelMappingMigrationEntityManagerFactoryDependsOnPostProcessor() {
            return new EntityManagerFactoryDependsOnPostProcessor("modelMappingMigrationRunner");
        }
    }
}
