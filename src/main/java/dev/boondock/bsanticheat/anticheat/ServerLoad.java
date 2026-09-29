package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.util.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks recent server tick rate so checks can be relaxed during lag (movement/combat deltas
 * spike when the server can't keep up, which would otherwise cause false positives).
 *
 * <p>The rate is measured directly: a task runs every tick and records when it actually ran,
 * and the tick rate over the last {@link #WINDOW_TICKS} ticks is derived from those
 * timestamps. {@code Bukkit.getTPS()[0]} is a one-minute average, so a short freeze would
 * hold it under the threshold — and with it nearly every check off — for up to a minute.
 * The short window recovers a few seconds after the server does.
 *
 * <p>On Folia the global region ticks independently of the regions players are in, so the
 * global clock says nothing about the region a player's movement is processed on. There,
 * each online player additionally gets a clock on their own entity scheduler, which ticks
 * with the region that owns them; {@link #isLagging(PluginConfig, Player)} consults it.
 *
 * <p>Every published value is volatile and safe to read from any thread.
 */
public final class ServerLoad {

    /** Ticks the short-window rate is averaged over (3 s at full speed). */
    static final int WINDOW_TICKS = 60;
    /**
     * A clock that has not ticked for this long is inside a stalled tick right now. The
     * backlog of movement processed at the start of the first tick after a freeze would
     * otherwise be judged before the sampler has had a chance to see the freeze.
     */
    static final long STALE_NANOS = 250_000_000L;
    private static final double NOMINAL_TPS = 20.0;

    /**
     * Tick timestamps over a short window. Written by exactly one thread (the one the clock's
     * task runs on); the derived values are published through volatile fields.
     */
    static final class TickClock {
        private final long[] stamps = new long[WINDOW_TICKS + 1];
        private int head;
        private int count;
        private volatile double tps = NOMINAL_TPS;
        private volatile long lastTickNanos;

        void tick(long nowNanos) {
            stamps[head] = nowNanos;
            head = (head + 1) % stamps.length;
            if (count < stamps.length) count++;
            lastTickNanos = nowNanos;
            if (count >= 2) {
                int oldest = (head - count + stamps.length) % stamps.length;
                long span = nowNanos - stamps[oldest];
                if (span > 0) tps = Math.min(NOMINAL_TPS, (count - 1) * 1.0e9 / span);
            }
        }

        double tps() {
            return tps;
        }

        /** True when the recent rate is under {@code floor}, or a tick is overdue right now. */
        boolean lagging(double floor, long nowNanos) {
            long last = lastTickNanos;
            if (last == 0L) return false; // never started (tests, or before the first tick)
            if (nowNanos - last > STALE_NANOS) return true;
            return tps < floor;
        }
    }

    private static volatile TickClock globalClock = new TickClock();
    // Folia only: one clock per player, driven by that player's region.
    private static final Map<UUID, TickClock> regionClocks = new ConcurrentHashMap<>();
    private static volatile boolean regionized = false;
    private static Plugin owner;

    // Whether checks are currently suspended, as last reported to the console, plus how many
    // consecutive samples have disagreed with that. The state is announced because nearly
    // every check backs off while the server is under the threshold; a server that runs
    // below it permanently would otherwise have the anticheat off without any indication.
    // The confirmation count keeps a server hovering on the threshold from logging a line a
    // second.
    private static boolean announcedLagging = false;
    private static int pendingSamples = 0;
    private static final int SAMPLES_BEFORE_ANNOUNCING = 5;

    private ServerLoad() {}

    public static void start(Plugin plugin, PluginConfig config) {
        announcedLagging = false;
        pendingSamples = 0;
        owner = plugin;
        globalClock = new TickClock();
        regionClocks.clear();
        regionized = isFolia();

        TickClock clock = globalClock;
        Scheduler.runGlobalTimer(plugin, () -> clock.tick(System.nanoTime()), 1L, 1L);
        Scheduler.runGlobalTimer(plugin, () -> {
            if (regionized) {
                // Players who joined since the last pass get their region clock here, so no
                // join hook is needed. Players who left are dropped; their entity task is
                // retired by the scheduler together with the entity.
                java.util.Set<UUID> online = new java.util.HashSet<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    online.add(p.getUniqueId());
                    track(p);
                }
                regionClocks.keySet().retainAll(online);
            }
            announceIfChanged(plugin, config);
        }, 20L, 20L);
    }

    /** True on Folia, where regions tick independently of the global region. */
    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Start a region clock for this player (Folia only; a no-op elsewhere, where every
     * player is ticked by the main thread and the global clock already covers them).
     */
    static void track(Player player) {
        if (!regionized || owner == null) return;
        UUID id = player.getUniqueId();
        if (regionClocks.containsKey(id)) return;
        TickClock clock = new TickClock();
        // The global timer and a region thread may track the same player at once; only the
        // caller whose clock was inserted schedules the entity task.
        if (regionClocks.putIfAbsent(id, clock) != null) return;
        try {
            player.getScheduler().runAtFixedRate(owner, task -> clock.tick(System.nanoTime()),
                    () -> regionClocks.remove(id, clock), 1L, 1L);
        } catch (Throwable t) {
            regionClocks.remove(id, clock); // scheduling refused (entity already removed)
        }
    }

    /** Log the transition into and out of the lag-exempt state, once it has held. */
    private static void announceIfChanged(Plugin plugin, PluginConfig config) {
        boolean lagging = isLagging(config);
        if (lagging == announcedLagging) {
            pendingSamples = 0;
            return;
        }
        if (++pendingSamples < SAMPLES_BEFORE_ANNOUNCING) return;
        pendingSamples = 0;
        announcedLagging = lagging;
        if (lagging) {
            plugin.getLogger().warning(String.format(java.util.Locale.ROOT,
                    "Checks suspended: server below the lag threshold (TPS %.1f over the last "
                            + "%d ticks, limit %.1f TPS). Detections stay off until it recovers "
                            + "— lower anticheat.lag_exempt_tps if this is the server's normal load.",
                    tps(), WINDOW_TICKS, config.lagExemptTps()));
        } else {
            plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                    "Checks resumed: server back above the lag threshold (TPS %.1f).", tps()));
        }
    }

    /** Recent tick rate of the global clock (main thread on Paper). */
    static double tps() {
        return globalClock.tps();
    }

    /**
     * True when the server is lagging and checks should back off: the tick rate over the last
     * {@link #WINDOW_TICKS} ticks is under {@code lag_exempt_tps}, or the current tick is
     * overdue by more than {@link #STALE_NANOS}.
     */
    static boolean isLagging(PluginConfig config) {
        return globalClock.lagging(config.lagExemptTps(), System.nanoTime());
    }

    /**
     * Like {@link #isLagging(PluginConfig)}, but on Folia also considers the region that owns
     * this player — the one whose tick rate actually shapes their movement deltas.
     */
    static boolean isLagging(PluginConfig config, Player player) {
        if (isLagging(config)) return true;
        if (!regionized || player == null) return false;
        TickClock clock = regionClocks.get(player.getUniqueId());
        if (clock == null) {
            track(player);
            return false;
        }
        return clock.lagging(config.lagExemptTps(), System.nanoTime());
    }

    /**
     * Variant for threads that must not touch the Bukkit API (Netty). Reads only the clocks
     * and never starts one; a player without a region clock yet is covered by the global
     * timer, which tracks every online player once per second.
     */
    static boolean isLagging(PluginConfig config, UUID playerId) {
        if (isLagging(config)) return true;
        if (!regionized || playerId == null) return false;
        TickClock clock = regionClocks.get(playerId);
        return clock != null && clock.lagging(config.lagExemptTps(), System.nanoTime());
    }
}
