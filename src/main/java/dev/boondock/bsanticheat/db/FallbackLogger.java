package dev.boondock.bsanticheat.db;

import org.bukkit.plugin.Plugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
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
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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
     */
    public void log(String type, double value, String description) {
        // Check queue size to prevent memory issues
        if (queue.size() >= MAX_QUEUE_SIZE) {
            plugin.getLogger().warning("[Fallback] Queue full (" + MAX_QUEUE_SIZE + "), dropping entry: " + type);
            return;
        }

        String timestamp = LocalDateTime.now().format(formatter);
        String entry = String.format("%s | %s | %.2f | %s", timestamp, type, value, description);
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

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                flush();
            } catch (IOException e) {
                plugin.getLogger().severe("[Fallback] Failed to write to fallback log: " + e.getMessage());
                e.printStackTrace();
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

            // Write all queued entries
            String entry;
            int count = 0;
            while ((entry = queue.poll()) != null) {
                writer.write(entry);
                writer.newLine();
                count++;
            }

            if (count > 0) {
                plugin.getLogger().info("[Fallback] Wrote " + count + " entries to fallback log");
            }
        }
    }

    /**
     * Shutdown - flush remaining entries.
     */
    public void shutdown() {
        try {
            flush();
        } catch (IOException e) {
            plugin.getLogger().severe("[Fallback] Failed to flush on shutdown: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Get the fallback log file path.
     */
    public String getLogFilePath() {
        return logFilePath;
    }

    /**
     * Check if there are queued entries.
     */
    public boolean hasQueuedEntries() {
        return !queue.isEmpty();
    }

    /**
     * Get current queue size.
     */
    public int getQueueSize() {
        return queue.size();
    }
}
