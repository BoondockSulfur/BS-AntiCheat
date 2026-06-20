package dev.boondock.bsanticheat.db;

/**
 * SQL time units for database queries.
 * Provides type-safe time unit handling for MySQL and SQLite.
 */
public enum TimeUnit {
    SECOND("second"),
    MINUTE("minute"),
    HOUR("hour"),
    DAY("day");

    private final String sqlName;

    TimeUnit(String sqlName) {
        this.sqlName = sqlName;
    }

    /**
     * Get the SQL-safe name for this time unit.
     * @return SQL time unit name in lowercase
     */
    public String getSqlName() {
        return sqlName;
    }

    /**
     * Get the SQL-safe name in uppercase (for MySQL INTERVAL syntax).
     * @return SQL time unit name in uppercase
     */
    public String getSqlNameUpper() {
        return sqlName.toUpperCase();
    }
}
