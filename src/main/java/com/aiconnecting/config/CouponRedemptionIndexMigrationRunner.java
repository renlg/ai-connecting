package com.aiconnecting.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Ensures existing databases enforce one redemption per user and coupon. */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponRedemptionIndexMigrationRunner implements ApplicationRunner {

    static final String INDEX_NAME = "uk_coupon_redemption_coupon_user";
    private static final int MYSQL_DUPLICATE_KEY_NAME_ERROR_CODE = 1061;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        boolean mysql = DbDialectUtil.isMysql(jdbcTemplate);
        Set<String> columns = DbDialectUtil.existingColumns(
                jdbcTemplate, mysql, "coupon_redemption_logs");
        if (!columns.contains("coupon_id") || !columns.contains("user_id")) {
            log.warn("coupon_redemption_logs table not found when running unique-index migration, skip");
            return;
        }

        if (mysql) {
            createMysqlIndexIfAbsent();
        } else {
            jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS " + INDEX_NAME
                    + " ON coupon_redemption_logs (coupon_id, user_id)");
        }
        log.info("Coupon redemption unique-index migration complete");
    }

    private void createMysqlIndexIfAbsent() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                Integer.class, "coupon_redemption_logs", INDEX_NAME);
        if (count != null && count > 0) {
            return;
        }
        try {
            jdbcTemplate.execute("CREATE UNIQUE INDEX " + INDEX_NAME
                    + " ON coupon_redemption_logs (coupon_id, user_id)");
        } catch (DataAccessException e) {
            // Multiple instances may race between the information_schema check and CREATE INDEX.
            if (DbDialectUtil.mysqlErrorCode(e) == MYSQL_DUPLICATE_KEY_NAME_ERROR_CODE) {
                log.debug("Index {} was created by another instance, skip", INDEX_NAME);
            } else {
                throw e;
            }
        }
    }
}
