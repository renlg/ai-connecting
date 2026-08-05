package com.aiconnecting.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(0)
public class VideoTaskMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
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
}
