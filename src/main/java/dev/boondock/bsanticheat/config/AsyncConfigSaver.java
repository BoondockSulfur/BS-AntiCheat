package dev.boondock.bsanticheat.config;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asynchronous configuration saver to prevent main thread blocking.
 * Ensures config changes don't freeze the server.
 *
 * @since 3.0.0
 */
public class AsyncConfigSaver {

    private final JavaPlugin plugin;
    private final AtomicBoolean isSaving = new AtomicBoolean(false);
    private final AtomicBoolean pendingSave = new AtomicBoolean(false);

    public AsyncConfigSaver(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Save config asynchronously.
     * Non-blocking - runs on Bukkit's async scheduler to ensure thread safety.
     *
     * IMPORTANT: Bukkit's saveConfig() must run on the main thread to be thread-safe.
     * This method schedules the save on the main thread via Bukkit scheduler.
     *
     * @return CompletableFuture that completes when save is done
     */
    public CompletableFuture<Void> saveAsync() {
        // Mark that a save is requested
        pendingSave.set(true);

        // If already saving, the running save will pick up the pending flag
        if (!isSaving.compareAndSet(false, true)) {
            plugin.getLogger().fine("[Config] Save already in progress, will save again after completion");
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();

        // Schedule save on main thread (Bukkit's saveConfig is NOT thread-safe!)
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                // Reset pending flag before saving — any new request after this
                // point will set it again and trigger a follow-up save
                pendingSave.set(false);
                plugin.saveConfig();
                plugin.getLogger().fine("[Config] Configuration saved successfully");

                // Check if another save was requested during this save
                if (pendingSave.compareAndSet(true, false)) {
                    // Schedule follow-up save
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        plugin.saveConfig();
                    }, 20L); // 1 second delay
                }

                future.complete(null);
            } catch (Exception e) {
                plugin.getLogger().severe("[Config] Failed to save config: " + e.getMessage());
                e.printStackTrace();
                future.completeExceptionally(e);
            } finally {
                isSaving.set(false);
            }
        });

        return future;
    }

    /**
     * Save config synchronously (for shutdown).
     * Should only be used during plugin disable.
     */
    public void saveSyncOnShutdown() {
        try {
            plugin.saveConfig();
            plugin.getLogger().info("[Config] Configuration saved synchronously on shutdown");
        } catch (Exception e) {
            plugin.getLogger().severe("[Config] Failed to save config on shutdown: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Check if a save operation is currently in progress.
     * @return true if saving
     */
    public boolean isSaving() {
        return isSaving.get();
    }
}
