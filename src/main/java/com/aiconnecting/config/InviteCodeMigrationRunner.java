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

/** Adds the invite-code consumption marker before MySQL Hibernate schema validation runs. */
@Slf4j
@Component
@RequiredArgsConstructor
@DependsOn("mysqlSchemaInitializer")
public class InviteCodeMigrationRunner {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        if (!DbDialectUtil.isMysql(jdbcTemplate)) {
            return;
        }
        Set<String> columns = DbDialectUtil.existingColumns(jdbcTemplate, true, "users");
        if (columns.isEmpty() || columns.contains("invite_code_used")) {
            return;
        }
        try {
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN invite_code_used BOOLEAN NOT NULL DEFAULT FALSE");
            log.info("Invite code migration: added users.invite_code_used");
        } catch (DataAccessException e) {
            if (DbDialectUtil.isDuplicateColumnError(e, true)) {
                log.debug("users.invite_code_used was added by another instance, skip");
            } else {
                throw e;
            }
        }
    }

    @Configuration
    static class InviteCodeMigrationDependsOnConfig {
        @Bean
        static BeanFactoryPostProcessor inviteCodeMigrationEntityManagerFactoryDependsOnPostProcessor() {
            return new EntityManagerFactoryDependsOnPostProcessor("inviteCodeMigrationRunner");
        }
    }
}
