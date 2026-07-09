package dev.boondock.bsanticheat.util;

import dev.boondock.bsanticheat.anticheat.TransactionManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Shared math/lookup helpers used by several checkers. Kept in one place so the
 * latency model and the ice physics can't drift apart between checks — a stale copy
 * in one checker would flag players the others exempt.
 */
public final class CheckMath {

    private CheckMath() {}

    /**
     * Round-trip latency (ms) for lag compensation: the precise transaction RTT when
     * available, otherwise the coarse {@link Player#getPing()}.
     */
    public static int effectivePing(TransactionManager transactionManager, Player player) {
        if (transactionManager != null) {
            double rtt = transactionManager.roundTripMs(player.getUniqueId());
            if (rtt >= 0) return (int) Math.round(rtt);
        }
        return player.getPing();
    }

    /**
     * Latency slack multiplier for detection thresholds. Square-root scaling so
     * high-ping players get progressively more tolerance without allowing extreme
     * values: 200ms → +10%, 500ms → +20%, 1000ms → +30%. 1.0 at or below 100ms.
     */
    public static double pingSlack(int ping) {
        if (ping <= 100) return 1.0;
        return 1.0 + (Math.sqrt(ping - 100) / 100.0);
    }

    /** True for all walkable ice variants. */
    public static boolean isIce(Material m) {
        return m == Material.ICE || m == Material.PACKED_ICE
                || m == Material.BLUE_ICE || m == Material.FROSTED_ICE;
    }

    /**
     * Ice speed multiplier for the first ice found within 3 blocks below (1.0 = none).
     * Stops at the first solid non-ice block — the entity is supported by that instead.
     * The 3-block scan keeps the multiplier alive mid-jump/mid-hop over ice.
     */
    public static double iceMultiplierBelow(Location loc, double iceMultiplier, double blueIceMultiplier) {
        for (int i = 1; i <= 3; i++) {
            Material m = loc.getBlock().getRelative(0, -i, 0).getType();
            if (m == Material.BLUE_ICE) return blueIceMultiplier;
            if (isIce(m)) return iceMultiplier;
            if (m.isSolid()) break;
        }
        return 1.0;
    }
}
