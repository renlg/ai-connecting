package com.aiconnecting.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 模型类型/分辨率档位计费字段迁移：
 * 为 model_configs 补齐 type 及各分辨率档位价格列，并将存量模型的 type 回填为 'text'
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelTypeMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        Set<String> existing = jdbcTemplate.queryForList("PRAGMA table_info(model_configs)").stream()
                .map(row -> String.valueOf(row.get("name")).toLowerCase())
                .collect(Collectors.toSet());

        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("type", "VARCHAR(20) NOT NULL DEFAULT 'text'");
        for (String col : List.of("image_price_1k", "image_price_2k", "image_price_4k",
                "video_price_480p", "video_price_720p", "video_price_1080p", "video_price_4k")) {
            columns.put(col, "DECIMAL(10,2) NOT NULL DEFAULT 0");
        }

        int added = 0;
        for (Map.Entry<String, String> entry : columns.entrySet()) {
            if (!existing.contains(entry.getKey())) {
                jdbcTemplate.execute("ALTER TABLE model_configs ADD COLUMN "
                        + entry.getKey() + " " + entry.getValue());
                added++;
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
}
