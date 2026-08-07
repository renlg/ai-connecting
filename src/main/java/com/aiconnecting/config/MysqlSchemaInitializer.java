package com.aiconnecting.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * MySQL 模式下的建表初始化：在 Hibernate 创建 EntityManagerFactory（ddl-auto=validate）之前
 * 执行 schema-mysql.sql 中的 DDL，使 validate 能通过。SQLite 模式（默认）下本类不做任何事，
 * 不影响单实例 SQLite 部署的现有行为。
 *
 * 与 {@link VideoTaskMigrationRunner} 相同的 @PostConstruct + EntityManagerFactoryDependsOnPostProcessor
 * 手法，强制在 Hibernate 初始化之前完成执行；VideoTaskMigrationRunner 额外声明了对本 bean 的 @DependsOn，
 * 保证建表先于四个迁移 Runner 的列检查逻辑执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MysqlSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void initialize() {
        if (!DbDialectUtil.isMysql(jdbcTemplate)) {
            return;
        }
        String sql;
        try {
            sql = new String(
                    new ClassPathResource("schema-mysql.sql").getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load schema-mysql.sql", e);
        }

        int executed = 0;
        for (String rawStatement : sql.split(";")) {
            String statement = rawStatement.strip();
            if (statement.isEmpty() || statement.startsWith("--")) {
                continue;
            }
            try {
                jdbcTemplate.execute(statement);
                executed++;
            } catch (DataAccessException e) {
                // 索引/唯一键不支持 MySQL 的 "IF NOT EXISTS"，重复执行时忽略已存在错误，保持幂等
                String message = e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : e.getMessage();
                if (message != null && message.contains("Duplicate key name")) {
                    log.debug("Index already exists, skip: {}", statement);
                } else {
                    throw e;
                }
            }
        }
        log.info("MySQL schema initialization complete ({} statement(s) executed)", executed);
    }

    @Configuration
    static class MysqlSchemaInitializerDependsOnConfig {

        @Bean
        static BeanFactoryPostProcessor mysqlSchemaInitializerEntityManagerFactoryDependsOnPostProcessor() {
            return new EntityManagerFactoryDependsOnPostProcessor("mysqlSchemaInitializer");
        }
    }
}
