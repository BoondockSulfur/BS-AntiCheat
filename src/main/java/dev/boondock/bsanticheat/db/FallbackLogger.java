package dev.boondock.bsanticheat.db;

import org.bukkit.plugin.Plugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fallback file logger for when database is unavailable.
 * Ensures no data loss during database outages.
 *
 * @since 3.0.0
 */
public class FallbackLogger {

    private final Plugin plugin;
    private final String logFilePath;
    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean isWriting = new AtomicBoolean(false);
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT);

    private static final int MAX_QUEUE_SIZE = 10000;
    private static final int FLUSH_THRESHOLD = 100;

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
        // Check queue size to prevent memory issues
        if (queue.size() >= MAX_QUEUE_SIZE) {
            plugin.getLogger().warning("[Fallback] Queue full (" + MAX_QUEUE_SIZE + "), dropping entry: " + type);
            return;
        }

        String timestamp = formatter.format(
                LocalDateTime.ofInstant(Instant.ofEpochMilli(timeMs), ZoneId.systemDefault()));
        String entry = String.format(java.util.Locale.ROOT, "%s | %s | %.2f | %s", timestamp, type, value, description);
        queue.offer(entry);

        // Trigger flush if threshold reached
        if (queue.size() >= FLUSH_THRESHOLD) {
            flushAsync();
        }
    }

    /**
     * Flush queued entries to file asynchronously.
     */
    public void flushAsync() {
        // Prevent concurrent writes
        if (!isWriting.compareAndSet(false, true)) {
            return; // Already writing
        }

        // During plugin disable the scheduler rejects new tasks with an
        // IllegalPluginAccessException (which would abort the caller's shutdown
        // sequence) — write synchronously instead.
        if (!plugin.isEnabled()) {
            try {
                flush();
            } catch (IOException e) {
                plugin.getLogger().severe("[Fallback] Failed to write to fallback log: " + e.getMessage());
            } finally {
                isWriting.set(false);
            }
            return;
        }

        dev.boondock.bsanticheat.util.Scheduler.runAsync(plugin, () -> {
            try {
                flush();
            } catch (IOException e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "[Fallback] Failed to write to fallback log", e);
            } finally {
                isWriting.set(false);
            }
        });
    }

    /**
     * Synchronous flush - writes all queued entries to file.
     */
    private void flush() throws IOException {
        if (queue.isEmpty()) {
            return;
        }

        // Drain into a batch FIRST. Polling straight into the writer meant that a failure
        // part-way through — a full disk, a revoked permission, exactly the situations this
        // file exists for — had already removed those entries from the queue, so they were
        // gone for good. This is the last line of defence during a database outage; it must
        // not be the thing that loses the data.
        java.util.List<String> batch = new java.util.ArrayList<>();
        String entry;
        while ((entry = queue.poll()) != null) {
            batch.add(entry);
        }
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
            // Hand them back so the next flush retries them.
            queue.addAll(batch);
            throw e;
        }

        plugin.getLogger().info("[Fallback] Wrote " + batch.size() + " entries to fallback log");
    }

    /**
     * Shutdown - flush remaining entries.
     */
    public void shutdown() {
        try {
            flush();
        } catch (IOException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "[Fallback] Failed to flush on shutdown", e);
        }
    }
}
