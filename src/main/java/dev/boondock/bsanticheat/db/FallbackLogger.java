package dev.boondock.bsanticheat.db;

import dev.boondock.bsanticheat.util.Scheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.Plugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fallback file logger for when database is unavailable.
 * Ensures no data loss during database outages.
 *
 * <p>Entries are written when {@link #FLUSH_THRESHOLD} are queued and, independently, every
 * {@link #FLUSH_INTERVAL_SECONDS} once {@link #start()} has run, so a quiet outage with a
 * handful of entries reaches the file without waiting for shutdown.
 * All writes, including the shutdown flush, are serialised on {@link #writeLock}.
 *
 * @since 3.0.0
 */
public class FallbackLogger {

    private final Plugin plugin;
    private final String logFilePath;
    // A deque so a failed write can put its batch back at the HEAD, ahead of anything
    // logged meanwhile, keeping the file in order.
    private final LinkedBlockingDeque<String> queue = new LinkedBlockingDeque<>(MAX_QUEUE_SIZE);
    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);
    private final AtomicInteger dropped = new AtomicInteger();
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT);
    private volatile ScheduledTask periodicFlush;

    static final int MAX_QUEUE_SIZE = 10000;
    static final int FLUSH_THRESHOLD = 100;
    static final long FLUSH_INTERVAL_SECONDS = 30L;

    public FallbackLogger(Plugin plugin, String logFilePath) {
        this.plugin = plugin;
        this.logFilePath = logFilePath;

        // Ensure directory exists
        File logFile = new File(logFilePath);
        File parentDir = logFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
    }

    /** Start the periodic flush. */
    public void start() {
        start(FLUSH_INTERVAL_SECONDS);
    }

    void start(long intervalSeconds) {
        if (periodicFlush != null) return;
        periodicFlush = Scheduler.runAsyncTimer(plugin, this::flushQuietly,
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    /**
     * Log an entry to the fallback file.
     * Non-blocking - entries are queued and written asynchronously.
     *
     * @param type Log type
     * @param value Numeric value
     * @param description Description
     * @param timeMs When the violation was detected. Passed in rather than read here: the
     *               fallback receives whole batches at once when the database is failing, so
     *               stamping at write time gave every entry of an outage the same moment.
     */
    public void log(String type, double value, String description, long timeMs) {
        String timestamp = formatter.format(
                LocalDateTime.ofInstant(Instant.ofEpochMilli(timeMs), ZoneId.systemDefault()));
        String entry = String.format(java.util.Locale.ROOT, "%s | %s | %.2f | %s", timestamp, type, value, description);
        if (!queue.offerLast(entry)) {
            // One warning per overflow, not one per entry: the queue is only full when the
            // file cannot be written, and a line per dropped entry would flood the log too.
            if (dropped.getAndIncrement() == 0) {
                plugin.getLogger().warning("[Fallback] Queue full (" + MAX_QUEUE_SIZE
                        + "), dropping entries until the fallback log can be written again.");
            }
            return;
        }

        if (queue.size() >= FLUSH_THRESHOLD) {
            flushAsync();
        }
    }

    /**
     * Flush queued entries to file asynchronously.
     */
    public void flushAsync() {
        // At most one pending flush task; it writes everything queued by the time it runs.
        if (!flushScheduled.compareAndSet(false, true)) {
            return;
        }

        // During plugin disable the scheduler rejects new tasks with an
        // IllegalPluginAccessException (which would abort the caller's shutdown
        // sequence) — write synchronously instead.
        if (!plugin.isEnabled()) {
            flushScheduled.set(false);
            flushQuietly();
            return;
        }

        try {
            Scheduler.runAsync(plugin, () -> {
                flushScheduled.set(false);
                flushQuietly();
            });
        } catch (RuntimeException e) {
            flushScheduled.set(false);
            flushQuietly();
        }
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (IOException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "[Fallback] Failed to write to fallback log", e);
        }
    }

    /**
     * Synchronous flush - writes all queued entries to file. Blocks while another flush
     * is writing, so entries are never written twice or out of order.
     */
    void flush() throws IOException {
        writeLock.lock();
        try {
            flushLocked();
        } finally {
            writeLock.unlock();
        }
    }

    private void flushLocked() throws IOException {
        if (queue.isEmpty()) {
            reportDrops();
            return;
        }

        // Drain into a batch FIRST. Polling straight into the writer meant that a failure
        // part-way through — a full disk, a revoked permission, exactly the situations this
        // file exists for — had already removed those entries from the queue, so they were
        // gone for good. This is the last line of defence during a database outage; it must
        // not be the thing that loses the data.
        List<String> batch = new ArrayList<>();
        queue.drainTo(batch);
        if (batch.isEmpty()) {
            return;
        }

        File logFile = new File(logFilePath);
        boolean isNewFile = !logFile.exists();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
            // Write header if new file
            if (isNewFile) {
                writer.write("# BSAntiCheat Fallback Log");
                writer.newLine();
                writer.write("# Format: Timestamp | Type | Value | Description");
                writer.newLine();
                writer.write("# This file contains data logged during database outages");
                writer.newLine();
                writer.newLine();
            }

            for (String line : batch) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException e) {
            // Back to the head, oldest first, so the next flush writes them in order.
            // Entries that no longer fit (the queue filled up meanwhile) are counted as dropped.
            for (int i = batch.size() - 1; i >= 0; i--) {
                if (!queue.offerFirst(batch.get(i))) dropped.incrementAndGet();
            }
            throw e;
        }

        plugin.getLogger().info("[Fallback] Wrote " + batch.size() + " entries to fallback log");
        reportDrops();
    }

    private void reportDrops() {
        int n = dropped.getAndSet(0);
        if (n > 0) {
            plugin.getLogger().warning("[Fallback] " + n + " entries were dropped while the queue was full.");
        }
    }

    /** Number of entries waiting to be written. */
    int queued() {
        return queue.size();
    }

    /**
     * Shutdown - stop the periodic flush and write the remaining entries. Waits for an
     * async flush that is still writing.
     */
    public void shutdown() {
        ScheduledTask task = periodicFlush;
        if (task != null) {
            task.cancel();
            periodicFlush = null;
        }
        try {
            flush();
        } catch (IOException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "[Fallback] Failed to flush on shutdown", e);
        }
    }
}
