package dev.boondock.bsanticheat.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Scheduler abstraction that works on both Paper and Folia.
 *
 * <p>Folia has no single main thread — the world is split into region threads — so
 * {@code Bukkit.getScheduler()} is unsupported there. This wraps Paper's region/async
 * scheduler API, which exists on regular Paper too (where it simply runs on the main
 * thread), so the same code path behaves identically on Paper and correctly on Folia.
 *
 * <ul>
 *   <li>{@code global*} — not tied to a location/entity (timers, events, console commands).</li>
 *   <li>{@code async*} — off-thread work (DB, HTTP, file IO); time-based, not ticks.</li>
 *   <li>{@code forEntity/forPlayer} — must touch a specific entity's state; runs on that
 *       entity's region thread (Paper: main thread). No-op if the entity is gone.</li>
 * </ul>
 */
public final class Scheduler {

    private Scheduler() {}

    private static Consumer<ScheduledTask> wrap(Runnable r) {
        return task -> r.run();
    }

    // ---- Global (main region) ----

    public static void runGlobal(Plugin plugin, Runnable r) {
        Bukkit.getGlobalRegionScheduler().run(plugin, wrap(r));
    }

    public static void runGlobalLater(Plugin plugin, Runnable r, long delayTicks) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, wrap(r), Math.max(1L, delayTicks));
    }

    public static ScheduledTask runGlobalTimer(Plugin plugin, Runnable r, long delayTicks, long periodTicks) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin, wrap(r), Math.max(1L, delayTicks), Math.max(1L, periodTicks));
    }

    // ---- Async (off-thread), real time units ----

    public static void runAsync(Plugin plugin, Runnable r) {
        Bukkit.getAsyncScheduler().runNow(plugin, wrap(r));
    }

    public static void runAsyncLater(Plugin plugin, Runnable r, long delay, TimeUnit unit) {
        Bukkit.getAsyncScheduler().runDelayed(plugin, wrap(r), delay, unit);
    }

    public static ScheduledTask runAsyncTimer(Plugin plugin, Runnable r, long delay, long period, TimeUnit unit) {
        return Bukkit.getAsyncScheduler().runAtFixedRate(plugin, wrap(r), delay, period, unit);
    }

    // ---- Entity-bound ----

    /** Run on the entity's region thread; no-op if the entity has been removed. */
    public static void runForEntity(Plugin plugin, Entity entity, Runnable r) {
        entity.getScheduler().run(plugin, wrap(r), null);
    }

    /** Run on the entity's region thread after a delay; no-op if the entity is gone. */
    public static void runForEntityLater(Plugin plugin, Entity entity, Runnable r, long delayTicks) {
        entity.getScheduler().runDelayed(plugin, wrap(r), null, Math.max(1L, delayTicks));
    }

    /** Resolve an online player by UUID and run on its region thread; no-op if offline. */
    public static void runForPlayer(Plugin plugin, UUID id, Runnable r) {
        Player player = Bukkit.getPlayer(id);
        if (player != null) runForEntity(plugin, player, r);
    }
}
