package com.aiconnecting.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ModelMappingMigrationRunnerTest {

    @Test
    void existingMysqlTableGetsPortableAddColumnStatement() throws Exception {
        JdbcTemplate jdbc = mysqlJdbcTemplate();
        when(jdbc.queryForList(anyString(), eq(String.class), eq("channels")))
                .thenReturn(List.of("id", "model_ids"));

        new ModelMappingMigrationRunner(jdbc).migrate();

        verify(jdbc).execute("ALTER TABLE channels ADD COLUMN model_mapping TEXT");
    }

    @Test
    void mysqlSchemaCreatesColumnWithoutAlterStatement() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("schema-mysql.sql")) {
            String schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(schema.matches("(?s).*CREATE TABLE IF NOT EXISTS channels \\(.*model_mapping TEXT.*"));
            assertFalse(schema.toUpperCase().contains("ALTER TABLE"));
        }
    }

    private JdbcTemplate mysqlJdbcTemplate() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(jdbc.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        return jdbc;
    }
}
