package dev.boondock.bsanticheat.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.util.Constants;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * SQLite database manager for BSAntiCheat.
 */
public class DatabaseManager {

    private final Plugin plugin;
    private final PluginConfig config;
    private HikariDataSource ds;
    private final ConcurrentLinkedQueue<LogEntry> queue = new ConcurrentLinkedQueue<>();
    private int taskId = -1;
    private int cleanupTaskId = -1;
    private FallbackLogger fallbackLogger;
    private volatile boolean databaseAvailable = true;
    private int consecutiveFailures = 0;
    private static final int MAX_FAILURES_BEFORE_FALLBACK = 3;

    public DatabaseManager(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
        if (config.fallbackFileLoggingEnabled()) {
            this.fallbackLogger = new FallbackLogger(plugin, config.fallbackLogFile());
        }
    }

    public void init() {
        HikariConfig hc = new HikariConfig();
        File f = new File(config.sqliteFile());
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        hc.setJdbcUrl("jdbc:sqlite:" + f.getPath());
        hc.setMaximumPoolSize(config.poolMax());
        hc.setMinimumIdle(config.poolMinIdle());
        hc.setConnectionTimeout(config.poolConnTimeoutMs());
        hc.setPoolName("BSAntiCheatPool");
        this.ds = new HikariDataSource(hc);

        run("CREATE TABLE IF NOT EXISTS anticheat_logs (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "ts TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
            "log_type VARCHAR(32) NOT NULL, " +
            "value DOUBLE, " +
            "description VARCHAR(255))");

        run("CREATE INDEX IF NOT EXISTS idx_ac_logs_type_ts ON anticheat_logs(log_type, ts)");
        run("CREATE INDEX IF NOT EXISTS idx_ac_logs_ts ON anticheat_logs(ts)");

        long periodTicks = Constants.DB_FLUSH_INTERVAL_SECONDS * 20L;
        this.taskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushBatchSafe, periodTicks, periodTicks).getTaskId();
        startAutoCleanup();
    }

    private void startAutoCleanup() {
        int retentionDays = config.databaseRetentionDays();
        if (retentionDays <= 0) return;
        long initialDelay = 20L * 60 * 60;
        long period = 20L * 60 * 60 * 24;
        this.cleanupTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try { cleanOldData(retentionDays); }
            catch (Exception e) { plugin.getLogger().severe("[Database] Auto-cleanup failed: " + e.getMessage()); }
        }, initialDelay, period).getTaskId();
    }

    public void logAsync(String type, double value, String description) {
        if (queue.size() >= Constants.DB_MAX_QUEUE_SIZE) return;
        queue.add(new LogEntry(type, value, description));
    }

    private void flushBatchSafe() {
        try {
            flushBatch();
            if (consecutiveFailures > 0) {
                consecutiveFailures = 0;
                if (!databaseAvailable) {
                    plugin.getLogger().info("Database connection restored!");
                    databaseAvailable = true;
                }
            }
        } catch (Exception e) {
            consecutiveFailures++;
            if (consecutiveFailures >= MAX_FAILURES_BEFORE_FALLBACK && databaseAvailable) {
                databaseAvailable = false;
            }
            if (fallbackLogger != null && !databaseAvailable) {
                List<LogEntry> failedEntries = new ArrayList<>(queue);
                for (LogEntry entry : failedEntries) {
                    fallbackLogger.log(entry.type(), entry.value(), entry.description());
                }
                queue.clear();
            }
        }
    }

    private void flushBatch() throws SQLException {
        List<LogEntry> batch = new ArrayList<>();
        LogEntry e;
        while ((e = queue.poll()) != null && batch.size() < Constants.DB_MAX_BATCH_SIZE) {
            batch.add(e);
        }
        if (batch.isEmpty()) return;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO anticheat_logs (log_type, value, description) VALUES (?, ?, ?)")) {
            for (LogEntry le : batch) {
                ps.setString(1, le.type());
                ps.setDouble(2, le.value());
                ps.setString(3, le.description());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public int deleteAntiCheatLogs(String playerName, String logTypePrefix) {
        String sql = "DELETE FROM anticheat_logs WHERE log_type LIKE ? AND description LIKE ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, logTypePrefix + "%");
            ps.setString(2, playerName + ":%");
            return ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Delete error: " + e.getMessage());
        }
        return 0;
    }

    public void cleanOldData(int daysToKeep) {
        String sql = "DELETE FROM anticheat_logs WHERE ts < datetime('now', '-' || ? || ' days')";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, daysToKeep);
            int deleted = ps.executeUpdate();
            if (deleted > 0) plugin.getLogger().info("Cleaned " + deleted + " old log entries.");
        } catch (SQLException e) {
            plugin.getLogger().severe("Cleanup error: " + e.getMessage());
        }
    }

    public void shutdown() {
        try {
            if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
            if (cleanupTaskId != -1) Bukkit.getScheduler().cancelTask(cleanupTaskId);
            flushBatchSafe();
            if (fallbackLogger != null) fallbackLogger.shutdown();
        } finally {
            if (ds != null && !ds.isClosed()) ds.close();
        }
    }

    public boolean isDatabaseAvailable() { return databaseAvailable; }

    private void run(String sql) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.execute();
        } catch (SQLException e) {
            plugin.getLogger().severe("DB-Init-Error: " + e.getMessage());
        }
    }
}
