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

    private ServerLoad() {}

    public static void start(Plugin plugin) {
        Scheduler.runGlobalTimer(plugin, () -> {
            double[] tps = Bukkit.getTPS();
            if (tps != null && tps.length > 0) {
                currentTps = tps[0];
            }
        }, 20L, 20L);
    }

    static double tps() {
        return currentTps;
    }

    /** True when recent TPS is below the configured threshold (checks should back off). */
    static boolean isLagging(PluginConfig config) {
        return currentTps < config.lagExemptTps();
    }
}
