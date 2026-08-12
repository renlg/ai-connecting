package com.aiconnecting.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class CouponRedemptionIndexMigrationRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void existingSqliteTableGetsUniqueIndex() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + tempDir.resolve("migration.db"), "", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE coupon_redemption_logs ("
                + "id INTEGER PRIMARY KEY, coupon_id BIGINT NOT NULL, "
                + "user_id BIGINT NOT NULL, redeemed_at TIMESTAMP NOT NULL)");

        new CouponRedemptionIndexMigrationRunner(jdbc).run(null);

        jdbc.update("INSERT INTO coupon_redemption_logs "
                + "(id, coupon_id, user_id, redeemed_at) VALUES (1, 10, 20, CURRENT_TIMESTAMP)");
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO coupon_redemption_logs "
                        + "(id, coupon_id, user_id, redeemed_at) VALUES (2, 10, 20, CURRENT_TIMESTAMP)"));
    }
}
