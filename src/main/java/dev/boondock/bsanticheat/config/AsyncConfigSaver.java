package dev.boondock.bsanticheat.config;

import dev.boondock.bsanticheat.util.Scheduler;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Writes config.yml off the server threads.
 *
 * <p>The YAML text comes from a snapshot supplier ({@link PluginConfig} serialises its
 * current, never-mutated configuration under its own lock), so the file write never touches
 * a configuration another thread may be reading or changing. Writes are serialised on
 * {@link #fileLock} and go through a temp file, so a crash mid-write cannot leave a
 * truncated config behind.
 *
 * @since 3.0.0
 */
public class AsyncConfigSaver {

    private final JavaPlugin plugin;
    private final Supplier<String> snapshot;
    private final File configFile;
    private final Object fileLock = new Object();
    private final AtomicBoolean isSaving = new AtomicBoolean(false);
    private final AtomicBoolean pendingSave = new AtomicBoolean(false);

    public AsyncConfigSaver(JavaPlugin plugin, Supplier<String> snapshot) {
        this.plugin = plugin;
        this.snapshot = snapshot;
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
    }

    /**
     * Save the current snapshot on an async thread. Requests arriving while a save runs are
     * coalesced into one follow-up save.
     *
     * @return CompletableFuture that completes when this save is done
     */
    public CompletableFuture<Void> saveAsync() {
        pendingSave.set(true);

        // If already saving, the running save will pick up the pending flag
        if (!isSaving.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                // Reset before taking the snapshot: a change after this point sets it again
                // and triggers a follow-up save that includes it.
                pendingSave.set(false);
                writeLatest();
                future.complete(null);
            } catch (Exception e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "[Config] Failed to save config", e);
                future.completeExceptionally(e);
            } finally {
                isSaving.set(false);
                // Checked only after the flag is released: a request arriving between a
                // check made earlier and the release would otherwise be lost.
                if (pendingSave.get()) scheduleFollowUp();
            }
        };
        try {
            Scheduler.runAsync(plugin, task);
        } catch (RuntimeException e) {
            // Scheduler refuses new tasks while the plugin is disabling; write in place.
            task.run();
        }
        return future;
    }

    private void scheduleFollowUp() {
        try {
            // Only if still pending: flushPending() may have written it in the meantime.
            Scheduler.runAsyncLater(plugin, () -> {
                if (pendingSave.get()) saveAsync();
            }, 1L, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            // Disabling: the shutdown save writes the latest snapshot.
        }
    }

    /**
     * Write a requested but not yet written save on the calling thread, and wait for a
     * running write to finish. Called before the file is re-read, so a reload cannot load a
     * file that lacks changes still waiting in the queue.
     */
    public void flushPending() {
        boolean pending = pendingSave.getAndSet(false);
        if (!pending && !isSaving.get()) return;
        try {
            writeLatest();
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "[Config] Failed to save config", e);
        }
    }

    /**
     * Snapshot and write under {@link #fileLock}, so of two concurrent writers the later one
     * always writes the later snapshot.
     */
    private void writeLatest() throws IOException {
        synchronized (fileLock) {
            writeNow(snapshot.get());
        }
    }

    /**
     * Save synchronously (for shutdown). Waits for a running async write to finish first.
     */
    public void saveSyncOnShutdown() {
        try {
            writeLatest();
            plugin.getLogger().info("[Config] Configuration saved synchronously on shutdown");
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "[Config] Failed to save config on shutdown", e);
        }
    }

    /** Write the given YAML to config.yml on the calling thread. */
    void writeNow(String yaml) throws IOException {
        synchronized (fileLock) {
            Path target = configFile.toPath();
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, yaml, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
