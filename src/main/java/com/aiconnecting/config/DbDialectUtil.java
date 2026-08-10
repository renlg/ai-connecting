package com.aiconnecting.config;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 启动期迁移 Runner 共用的数据库方言探测与列存在性检查工具，
 * 让同一份 Runner 逻辑在 SQLite（PRAGMA table_info）和 MySQL（information_schema）下都能工作。
 */
public final class DbDialectUtil {

    private DbDialectUtil() {
    }

    public static boolean isMysql(JdbcTemplate jdbcTemplate) {
        return "MySQL".equalsIgnoreCase(productName(jdbcTemplate.getDataSource()));
    }

    private static String productName(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to detect database product", e);
        }
    }

    /** 返回表的现有列名（小写），MySQL 下表不存在时返回空集合，SQLite 下同理（PRAGMA 无结果）。 */
    public static Set<String> existingColumns(JdbcTemplate jdbcTemplate, boolean mysql, String table) {
        if (mysql) {
            return jdbcTemplate.queryForList(
                            "SELECT column_name FROM information_schema.columns "
                                    + "WHERE table_schema = DATABASE() AND table_name = ?",
                            String.class, table)
                    .stream()
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());
        }
        return jdbcTemplate.queryForList("PRAGMA table_info(" + table + ")").stream()
                .map(row -> String.valueOf(row.get("name")).toLowerCase())
                .collect(Collectors.toSet());
    }

    /**
     * 沿异常链找到最深的 {@link SQLException} 并返回其 errorCode，供调用方按 MySQL 官方错误码
     * （而不是可能被驱动包装或本地化的错误文案）判断是否为可忽略的幂等冲突。找不到 SQLException
     * 时返回 -1，不会匹配任何已知 MySQL 错误码。
     */
    public static int mysqlErrorCode(Throwable e) {
        Throwable cause = e;
        SQLException sqlException = null;
        while (cause != null) {
            if (cause instanceof SQLException) {
                sqlException = (SQLException) cause;
            }
            cause = cause.getCause();
        }
        return sqlException != null ? sqlException.getErrorCode() : -1;
    }

    private static final int MYSQL_DUPLICATE_COLUMN_ERROR_CODE = 1060;

    /**
     * 判断 ALTER TABLE ADD COLUMN 失败是否为“列已存在”这类可安全忽略的幂等冲突
     * （多实例并发启动，两边都探测到列缺失并同时执行 ADD COLUMN 时会出现）。
     * MySQL 按官方错误码 1060 判断；SQLite 驱动不提供专用错误码，只能退化为按错误文案匹配。
     */
    public static boolean isDuplicateColumnError(Throwable e, boolean mysql) {
        if (mysql) {
            return mysqlErrorCode(e) == MYSQL_DUPLICATE_COLUMN_ERROR_CODE;
        }
        String message = e.getMessage();
        return message != null && message.toLowerCase().contains("duplicate column name");
    }
}
