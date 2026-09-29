package dev.boondock.bsanticheat.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.util.Constants;
import dev.boondock.bsanticheat.util.Scheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SQLite database manager for BSAntiCheat.
 */
public class DatabaseManager {

    private final Plugin plugin;
    private final PluginConfig config;
    private HikariDataSource ds;
    /**
     * Bounded rather than a ConcurrentLinkedQueue guarded by a size check: that check ran on
     * every single logged violation, and {@code ConcurrentLinkedQueue.size()} walks the whole
     * queue. Harmless while the queue is short — but it is only ever long during a database
     * outage, i.e. exactly when every violation would then pay an O(n) scan. The capacity
     * enforces the same limit in constant time.
     */
    private final BlockingQueue<LogEntry> queue = new LinkedBlockingQueue<>(Constants.DB_MAX_QUEUE_SIZE);
    private ScheduledTask flushTask;
    private ScheduledTask cleanupTask;
    private FallbackLogger fallbackLogger;
    private volatile boolean databaseAvailable = true;
    // Whether anticheat_logs has the player_uuid column. False only when the migration could
    // not add it; inserts and deletes then use the legacy column set instead of failing.
    private volatile boolean uuidColumn = true;
    private int consecutiveFailures = 0;
    private static final int MAX_FAILURES_BEFORE_FALLBACK = 3;
    private static final long DROP_WARN_INTERVAL_MS = 60_000L;
    private final AtomicInteger dropped = new AtomicInteger();
    private final AtomicLong lastDropWarn = new AtomicLong();
    /** Exactly what SQLite's CURRENT_TIMESTAMP produces: UTC, second resolution. */
    private static final DateTimeFormatter SQLITE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).withZone(ZoneOffset.UTC);

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
        migrateSchema();

        this.flushTask = Scheduler.runAsyncTimer(plugin, this::flushBatchSafe,
                Constants.DB_FLUSH_INTERVAL_SECONDS, Constants.DB_FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
        if (fallbackLogger != null) fallbackLogger.start();
        startAutoCleanup();
    }

    /**
     * Idempotent schema upgrades, checked on every start. {@code player_uuid} is nullable:
     * rows written before it existed keep NULL and are matched by name as before.
     */
    void migrateSchema() {
        boolean hasUuid = false;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("PRAGMA table_info(anticheat_logs)");
             java.sql.ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if ("player_uuid".equalsIgnoreCase(rs.getString("name"))) hasUuid = true;
            }
        } catch (SQLException e) {
            // Unknown state: attempt the ALTER anyway, it reports an existing column.
            plugin.getLogger().warning("[Database] Could not read the table layout: " + e.getMessage());
        }
        uuidColumn = hasUuid || addUuidColumn();
        if (uuidColumn) {
            run("CREATE INDEX IF NOT EXISTS idx_ac_logs_uuid ON anticheat_logs(player_uuid)");
        } else {
            plugin.getLogger().severe("[Database] Column player_uuid is missing and could not be added; "
                    + "logs are written without player UUIDs.");
        }
    }

    /** Add player_uuid; true when the column exists afterwards. */
    private boolean addUuidColumn() {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("ALTER TABLE anticheat_logs ADD COLUMN player_uuid VARCHAR(36)")) {
            ps.execute();
            return true;
        } catch (SQLException e) {
            String msg = e.getMessage();
            if (msg != null && msg.toLowerCase(java.util.Locale.ROOT).contains("duplicate column")) return true;
            plugin.getLogger().severe("DB-Init-Error: " + msg);
            return false;
        }
    }

    boolean hasUuidColumn() {
        return uuidColumn;
    }

    private void startAutoCleanup() {
        int retentionDays = config.databaseRetentionDays();
        if (retentionDays <= 0) return;
        long initialDelaySec = 60L * 60;       // 1 hour
        long periodSec = 60L * 60 * 24;        // 24 hours
        this.cleanupTask = Scheduler.runAsyncTimer(plugin, () -> {
            try { cleanOldData(retentionDays); }
            catch (Exception e) { plugin.getLogger().severe("[Database] Auto-cleanup failed: " + e.getMessage()); }
        }, initialDelaySec, periodSec, TimeUnit.SECONDS);
    }

    /** Queue a log row that belongs to no player (stored without a UUID). */
    public void logAsync(String type, double value, String description) {
        logAsync(null, type, value, description);
    }

    /** Queue a log row for a known player; {@code playerId} may be null. */
    public void logAsync(UUID playerId, String type, double value, String description) {
        // offer(), not add(): a full queue must drop the entry, not throw into a check.
        if (!queue.offer(new LogEntry(type, value, description, System.currentTimeMillis(), playerId))) {
            dropped.incrementAndGet();
            warnDroppedRateLimited();
        }
    }

    /**
     * One warning per {@link #DROP_WARN_INTERVAL_MS} with the count since the last one. The
     * queue only fills while the database is down and no fallback file is configured; a
     * line per dropped entry would bury the console exactly then.
     */
    private void warnDroppedRateLimited() {
        long now = System.currentTimeMillis();
        long last = lastDropWarn.get();
        if (now - last < DROP_WARN_INTERVAL_MS || !lastDropWarn.compareAndSet(last, now)) return;
        int n = dropped.getAndSet(0);
        if (n > 0) {
            plugin.getLogger().warning("[Database] Log queue full (" + Constants.DB_MAX_QUEUE_SIZE
                    + "), " + n + " entries dropped. The database is not accepting writes"
                    + (fallbackLogger == null ? " and database.fallback_file_logging is off." : "."));
        }
    }

    /** Write the queued rows now, as the periodic flush does. */
    void flushNow() {
        flushBatchSafe();
    }

    private void flushBatchSafe() {
        try {
            flushBatch();
            int lost = dropped.getAndSet(0);
            if (lost > 0) {
                plugin.getLogger().warning("[Database] " + lost + " log entries were dropped while the queue was full.");
            }
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
                // Drain via poll() — copy+clear would drop entries added in between
                LogEntry entry;
                while ((entry = queue.poll()) != null) {
                    fallbackLogger.log(entry.type(), entry.value(), entry.description(), entry.timeMs());
                }
            }
        }
    }

    private void flushBatch() throws SQLException {
        List<LogEntry> batch = new ArrayList<>();
        LogEntry e;
        // Size check BEFORE poll() — the other way round the entry that overflows
        // the batch is already removed from the queue and silently lost.
        while (batch.size() < Constants.DB_MAX_BATCH_SIZE && (e = queue.poll()) != null) {
            batch.add(e);
        }
        if (batch.isEmpty()) return;
        // ts is written explicitly rather than left to CURRENT_TIMESTAMP: that default
        // stamps the flush time, which is up to DB_FLUSH_INTERVAL_SECONDS after the event
        // and identical for every row in the batch. Same format and zone (UTC) as the
        // default produced, so existing rows and queries are unaffected.
        boolean withUuid = uuidColumn;
        String sql = withUuid
                ? "INSERT INTO anticheat_logs (ts, log_type, value, description, player_uuid) VALUES (?, ?, ?, ?, ?)"
                : "INSERT INTO anticheat_logs (ts, log_type, value, description) VALUES (?, ?, ?, ?)";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (LogEntry le : batch) {
                ps.setString(1, SQLITE_TIMESTAMP.format(Instant.ofEpochMilli(le.timeMs())));
                ps.setString(2, le.type());
                ps.setDouble(3, le.value());
                ps.setString(4, le.description());
                if (withUuid) ps.setString(5, le.playerId() != null ? le.playerId().toString() : null);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException ex) {
            // Hand the polled entries back — otherwise a failed insert loses the whole
            // batch and the fallback logger only ever sees what was left in the queue.
            // offer() per entry, not addAll(): addAll on a bounded queue throws once it is
            // full, which would replace a logging failure with an exception on the way out.
            for (LogEntry le : batch) queue.offer(le);
            throw ex;
        }
    }

    /**
     * Delete log entries asynchronously — the synchronous variant can block up to the
     * pool connection timeout on the main thread when the pool is busy. The callback
     * runs on the main thread with the total number of deleted rows.
     *
     * @param playerId   the player's UUID; rows carrying it are deleted whatever name they show
     * @param playerName the current name, only used for rows written before the UUID column
     *                   existed (those have no UUID)
     */
    public void deleteAntiCheatLogsAsync(UUID playerId, String playerName, List<String> logTypePrefixes,
                                         java.util.function.IntConsumer callback) {
        Scheduler.runAsync(plugin, () -> {
            int deleted = 0;
            for (String prefix : logTypePrefixes) {
                deleted += deleteAntiCheatLogs(playerId, playerName, prefix);
            }
            int total = deleted;
            Scheduler.runGlobal(plugin, () -> callback.accept(total));
        });
    }

    /**
     * Delete every anticheat log for a player whose type does NOT start with one of
     * {@code excludedPrefixes}. Used by the movement alert command: its alert manager owns
     * every check except XRay, and a hand-maintained type list would go stale each time a
     * check is added.
     */
    public void deleteAntiCheatLogsExceptAsync(UUID playerId, String playerName, List<String> excludedPrefixes,
                                               java.util.function.IntConsumer callback) {
        Scheduler.runAsync(plugin, () -> {
            int deleted = deleteAntiCheatLogsExcept(playerId, playerName, excludedPrefixes);
            Scheduler.runGlobal(plugin, () -> callback.accept(deleted));
        });
    }

    /**
     * Row filter for one player: by UUID, and by name only for legacy rows without one.
     * The name patterns cover how the checks start their descriptions: {@code "name: ..."}
     * and the bracket-tagged XRay forms {@code "[XRay?] name: ..."} / {@code "[RESTRICTED] name ..."}.
     */
    private static final String PLAYER_FILTER = "(player_uuid = ? OR (player_uuid IS NULL AND ("
            + "description LIKE ? ESCAPE '\\' OR description LIKE ? ESCAPE '\\' OR description LIKE ? ESCAPE '\\')))";
    /** {@link #PLAYER_FILTER} for a table without the player_uuid column: name patterns only. */
    private static final String LEGACY_PLAYER_FILTER = "("
            + "description LIKE ? ESCAPE '\\' OR description LIKE ? ESCAPE '\\' OR description LIKE ? ESCAPE '\\')";

    private String playerFilter() {
        return uuidColumn ? PLAYER_FILTER : LEGACY_PLAYER_FILTER;
    }

    /** Bind {@link #playerFilter()}'s parameters starting at {@code index}. */
    private int bindPlayer(PreparedStatement ps, int index, UUID playerId, String playerName) throws SQLException {
        String name = escapeLike(playerName);
        if (uuidColumn) ps.setString(index++, playerId.toString());
        ps.setString(index++, name + ":%");
        ps.setString(index++, "[%] " + name + ":%");
        ps.setString(index++, "[%] " + name + " %");
        return index;
    }

    public int deleteAntiCheatLogs(UUID playerId, String playerName, String logTypePrefix) {
        String sql = "DELETE FROM anticheat_logs WHERE log_type LIKE ? ESCAPE '\\' AND " + playerFilter();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, likePrefix(logTypePrefix));
            bindPlayer(ps, 2, playerId, playerName);
            return ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Delete error: " + e.getMessage());
        }
        return 0;
    }

    private int deleteAntiCheatLogsExcept(UUID playerId, String playerName, List<String> excludedPrefixes) {
        StringBuilder sql = new StringBuilder(
                "DELETE FROM anticheat_logs WHERE log_type LIKE ? ESCAPE '\\' AND " + playerFilter());
        for (int i = 0; i < excludedPrefixes.size(); i++) {
            sql.append(" AND log_type NOT LIKE ? ESCAPE '\\'");
        }
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            ps.setString(1, likePrefix(Constants.LOG_TYPE_PREFIX));
            int index = bindPlayer(ps, 2, playerId, playerName);
            for (String prefix : excludedPrefixes) {
                ps.setString(index++, likePrefix(prefix));
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Delete error: " + e.getMessage());
        }
        return 0;
    }

    /** Escape the LIKE wildcards in a literal (see {@link #likePrefix}). */
    private static String escapeLike(String literal) {
        return literal.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Turn a literal string into a LIKE prefix pattern. SQL {@code LIKE} treats {@code _}
     * as a single-character wildcard — and Minecraft names may contain underscores, so an
     * unescaped "A_B:%" would also match player "AxB"'s rows and delete them.
     */
    private static String likePrefix(String literal) {
        return escapeLike(literal) + "%";
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
            if (flushTask != null) flushTask.cancel();
            if (cleanupTask != null) cleanupTask.cancel();
            // One flush writes at most one batch — loop until the queue is drained,
            // bailing out when no progress is made (DB not accepting writes).
            do {
                int before = queue.size();
                flushBatchSafe();
                if (queue.size() >= before && !queue.isEmpty()) break;
            } while (!queue.isEmpty());
            // Last resort: never drop what is still queued at shutdown
            if (!queue.isEmpty() && fallbackLogger != null) {
                LogEntry le;
                while ((le = queue.poll()) != null) {
                    fallbackLogger.log(le.type(), le.value(), le.description(), le.timeMs());
                }
            }
            if (fallbackLogger != null) fallbackLogger.shutdown();
        } finally {
            if (ds != null && !ds.isClosed()) ds.close();
        }
    }

    private void run(String sql) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.execute();
        } catch (SQLException e) {
            plugin.getLogger().severe("DB-Init-Error: " + e.getMessage());
        }
    }
}
