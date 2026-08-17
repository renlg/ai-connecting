package com.aiconnecting.repository;

import com.aiconnecting.dto.CostAggregateRow;
import com.aiconnecting.dto.CostSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CostQueryRepositoryTest {
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private CostQueryRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new SingleConnectionDataSource(DriverManager.getConnection("jdbc:sqlite::memory:"), true);
        jdbc = new JdbcTemplate(dataSource);
        repository = new CostQueryRepository(jdbc, new NamedParameterJdbcTemplate(dataSource));
        jdbc.execute("CREATE TABLE channels (id INTEGER PRIMARY KEY, name VARCHAR(100))");
        jdbc.execute("CREATE TABLE usage_logs (id INTEGER PRIMARY KEY, channel_id BIGINT, model VARCHAR(100), "
                + "actual_model VARCHAR(100), prompt_tokens INTEGER, completion_tokens INTEGER, "
                + "cached_tokens_cache_creation INTEGER, cached_tokens_cache_read INTEGER, credit_cost DECIMAL, "
                + "request_path VARCHAR(500), created_at BIGINT)");
        jdbc.execute("CREATE TABLE video_tasks (id INTEGER PRIMARY KEY, usage_log_id BIGINT, duration_seconds INTEGER)");
        jdbc.update("INSERT INTO channels(id, name) VALUES (1, '渠道A')");
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void aggregatesByChannelAndClientModelWithMediaMetricsAndBeijingBoundaries() {
        long start = LocalDate.of(2026, 8, 10).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        long end = LocalDate.of(2026, 8, 10).plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai"))
                .toInstant().toEpochMilli() - 1;
        insertUsage(1, "group-a", "upstream-1", 10, 3, 2, 1, "1.12555", "/v1/chat/completions", start);
        insertUsage(2, "group-a", "upstream-2", 0, 0, 0, 0, "2.00000", "/v1/images/generations", end);
        insertUsage(3, "video-a", "video-upstream", 0, 0, 0, 0, "3.50000", "/v1/videos", start + 1);
        jdbc.update("INSERT INTO video_tasks(id, usage_log_id, duration_seconds) VALUES (1, 3, 8)");
        // 北京日期之外的数据不能进入结果。
        insertUsage(4, "outside", "outside", 99, 99, 0, 0, "99", "/v1/chat/completions", start - 1);

        List<CostAggregateRow> rows = repository.findRows(
                LocalDate.of(2026, 8, 10).atStartOfDay(),
                LocalDate.of(2026, 8, 10).atTime(23, 59, 59, 999_000_000), null, null, 0, 20);

        assertThat(rows).hasSize(2);
        CostAggregateRow image = rows.stream().filter(row -> row.getModel().equals("group-a")).findFirst().orElseThrow();
        assertThat(image.getRequestCount()).isEqualTo(2);
        assertThat(image.getTotalPromptTokens()).isEqualTo(10);
        assertThat(image.getTotalCacheCreation()).isEqualTo(2);
        assertThat(image.getImageCount()).isEqualTo(1);
        assertThat(image.getModelType()).isEqualTo("image");
        assertThat(image.getActualModel()).contains("upstream-1", "upstream-2");
        assertThat(image.getTotalCreditCost().toPlainString()).isEqualTo("3.1256");

        CostAggregateRow video = rows.stream().filter(row -> row.getModel().equals("video-a")).findFirst().orElseThrow();
        assertThat(video.getModelType()).isEqualTo("video");
        assertThat(video.getVideoSeconds()).isEqualTo(8);

        CostSummary summary = repository.summarize(
                LocalDate.of(2026, 8, 10).atStartOfDay(),
                LocalDate.of(2026, 8, 10).atTime(23, 59, 59, 999_000_000), null, null);
        assertThat(summary.getRequestCount()).isEqualTo(3);
        assertThat(summary.getImageCount()).isEqualTo(1);
        assertThat(summary.getVideoSeconds()).isEqualTo(8);
        assertThat(repository.countRows(LocalDate.of(2026, 8, 10).atStartOfDay(),
                LocalDate.of(2026, 8, 10).atTime(23, 59, 59, 999_000_000), 1L, "group-a")).isEqualTo(1);
    }

    private void insertUsage(long id, String model, String actualModel, int prompt, int completion,
                             int cacheCreation, int cacheRead, String cost, String path, long createdAt) {
        jdbc.update("INSERT INTO usage_logs(id, channel_id, model, actual_model, prompt_tokens, completion_tokens, "
                        + "cached_tokens_cache_creation, cached_tokens_cache_read, credit_cost, request_path, created_at) "
                        + "VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, model, actualModel, prompt, completion, cacheCreation, cacheRead, cost, path, createdAt);
    }
}
