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

    private ServerLoad() {}

    public static void start(Plugin plugin) {
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
        }, 20L, 20L);
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
