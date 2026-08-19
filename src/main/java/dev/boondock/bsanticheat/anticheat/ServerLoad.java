package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.util.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Tracks recent server TPS so checks can be relaxed during lag (movement/combat deltas
 * spike when the server can't keep up, which would otherwise cause false positives).
 *
 * <p>TPS is sampled once a second on the main thread and cached in a volatile field, so it
 * is safe to read from any thread (including Netty packet threads).
 */
public final class ServerLoad {

    private static volatile double currentTps = 20.0;
    private static volatile double currentMspt = 50.0;

    // Whether checks are currently suspended, as last reported to the console, plus how many
    // consecutive samples have disagreed with that. Announcing the state matters: while the
    // server is under the threshold nearly every check backs off, so a box sitting at 17 TPS
    // runs with the anticheat effectively switched off — silently, which is the worst way for
    // it to be off. The confirmation count keeps a server hovering on the threshold from
    // logging a line a second.
    private static boolean announcedLagging = false;
    private static int pendingSamples = 0;
    private static final int SAMPLES_BEFORE_ANNOUNCING = 5;

    private ServerLoad() {}

    public static void start(Plugin plugin, PluginConfig config) {
        announcedLagging = false;
        pendingSamples = 0;
        Scheduler.runGlobalTimer(plugin, () -> {
            double[] tps = Bukkit.getTPS();
            if (tps != null && tps.length > 0) {
                currentTps = tps[0];
            }
            // Defensive: on region-threaded servers (Folia) the global tick-time metric
            // may be unsupported — a throw here must not kill the sampler task.
            try {
                currentMspt = Bukkit.getAverageTickTime();
            } catch (Throwable ignored) {
                currentMspt = 50.0; // neutral: the MSPT branch simply never triggers
            }
            announceIfChanged(plugin, config);
        }, 20L, 20L);
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
                    "Checks suspended: server below the lag threshold (TPS %.1f, MSPT %.1f, "
                            + "limit %.1f TPS). Detections stay off until it recovers — lower "
                            + "anticheat.lag_exempt_tps if this is the server's normal load.",
                    currentTps, currentMspt, config.lagExemptTps()));
        } else {
            plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                    "Checks resumed: server back above the lag threshold (TPS %.1f).", currentTps));
        }
    }

    static double tps() {
        return currentTps;
    }

    /**
     * True when the server is lagging and checks should back off. Uses two signals:
     * getTPS()[0] is a 1-minute average that barely reacts to short spikes — exactly
     * the situations that distort movement deltas — so the 5-second MSPT average
     * (getAverageTickTime) catches those; the equivalent threshold is 1000/lagExemptTps
     * (18 TPS → 55.6ms per tick).
     */
    static boolean isLagging(PluginConfig config) {
        double tpsFloor = config.lagExemptTps();
        return currentTps < tpsFloor || (tpsFloor > 0 && currentMspt > 1000.0 / tpsFloor);
    }
}
