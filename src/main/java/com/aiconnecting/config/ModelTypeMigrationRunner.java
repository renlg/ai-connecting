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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模型类型/分辨率档位计费字段迁移：
 * 为 model_configs 补齐 type、vision_support 及各分辨率档位价格列，并将存量模型的 type 回填为 'text'
 *
 * 与 {@link VideoTaskMigrationRunner} 相同的原因：必须在 Hibernate 创建 EntityManagerFactory
 * （ddl-auto=validate/update）之前运行，否则存量 MySQL 表缺列会导致 validate 直接启动失败，
 * ApplicationRunner 永远得不到执行机会。改为 @PostConstruct + EntityManagerFactoryDependsOnPostProcessor。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@DependsOn("mysqlSchemaInitializer")
public class ModelTypeMigrationRunner {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        boolean mysql = DbDialectUtil.isMysql(jdbcTemplate);
        Set<String> existing = DbDialectUtil.existingColumns(jdbcTemplate, mysql, "model_configs");
        if (existing.isEmpty()) {
            // 表尚不存在（SQLite 全新库场景），交由 Hibernate ddl-auto 按实体定义创建全新表
            return;
        }

        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("type", "VARCHAR(20) NOT NULL DEFAULT 'text'");
        columns.put("vision_support", "BOOLEAN NOT NULL DEFAULT FALSE");
        for (String col : List.of("image_price_1k", "image_price_2k", "image_price_4k",
                "video_price_480p", "video_price_720p", "video_price_1080p", "video_price_4k",
                "audio_price_standard", "audio_price_hd")) {
            columns.put(col, "DECIMAL(10,2) NOT NULL DEFAULT 0");
        }

        int added = 0;
        for (Map.Entry<String, String> entry : columns.entrySet()) {
            if (!existing.contains(entry.getKey())) {
                try {
                    jdbcTemplate.execute("ALTER TABLE model_configs ADD COLUMN "
                            + entry.getKey() + " " + entry.getValue());
                    added++;
                } catch (DataAccessException e) {
                    if (DbDialectUtil.isDuplicateColumnError(e, mysql)) {
                        log.debug("Column {} already added by another instance, skip: {}", entry.getKey(), e.getMessage());
                    } else {
                        throw e;
                    }
                }
            }
        }

        int backfilled = jdbcTemplate.update(
                "UPDATE model_configs SET type = 'text' WHERE type IS NULL OR type = ''");

        if (added > 0 || backfilled > 0) {
            log.info("Model type migration: added {} column(s), backfilled {} row(s) to type='text'", added, backfilled);
        } else {
            log.info("Model type migration: schema already up to date");
        }
    }

    @Configuration
    static class ModelTypeMigrationDependsOnConfig {

        @Bean
        static BeanFactoryPostProcessor modelTypeMigrationEntityManagerFactoryDependsOnPostProcessor() {
            return new EntityManagerFactoryDependsOnPostProcessor("modelTypeMigrationRunner");
        }
    }
}
