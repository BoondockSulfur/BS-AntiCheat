package dev.boondock.bsanticheat.util;

import dev.boondock.bsanticheat.anticheat.TransactionManager;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
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

    // ==================== ATTRIBUTES ====================
    // Vanilla base values. Anything above these was raised by a potion, an enchantment, an
    // item, a datapack or a plugin — all legitimate, and all invisible to a check that
    // compares against a flat configured limit.
    private static final double VANILLA_MOVEMENT_SPEED = 0.1;
    private static final double VANILLA_WALK_SPEED = 0.2;      // Player#getWalkSpeed default
    private static final double VANILLA_SNEAKING_SPEED = 0.3;
    private static final double VANILLA_STEP_HEIGHT = 0.6;
    private static final double VANILLA_JUMP_STRENGTH = 0.42;
    private static final double VANILLA_GRAVITY = 0.08;
    // Vanilla adds this as a movement_speed modifier while sprinting (+30%)
    private static final double VANILLA_SPRINT_BOOST = 1.3;

    /** An attribute's current value, or {@code fallback} when it is unavailable. */
    public static double attribute(Player player, Attribute attribute, double fallback) {
        try {
            AttributeInstance inst = player.getAttribute(attribute);
            return inst != null ? inst.getValue() : fallback;
        } catch (Throwable t) {
            return fallback; // attribute not present on this server version
        }
    }

    /**
     * How much faster than vanilla this player may legitimately move, as a multiplier ≥ 1.
     *
     * <p>Reads the real {@code movement_speed} attribute rather than hand-rolling a Speed
     * potion multiplier. The attribute already includes the potion, so this is identical
     * for potions (Speed II → 1.4) but ALSO covers every other legitimate source: attribute
     * modifiers from custom gear and item plugins, datapacks, and mount/armour buffs.
     *
     * <p>{@link Player#getWalkSpeed()} is checked separately because it bypasses the
     * attribute system entirely — that is what EssentialsX {@code /speed} sets.
     */
    public static double speedAttributeRatio(Player player) {
        double ratio = attribute(player, Attribute.MOVEMENT_SPEED, VANILLA_MOVEMENT_SPEED) / VANILLA_MOVEMENT_SPEED;
        // Vanilla applies the sprint boost as a movement_speed modifier, so a sprinting
        // player already reads ~1.3x here. The caller compares against its own, higher
        // sprint threshold — counting the boost twice would inflate the sprint ceiling by
        // 30% and blunt the check, so it is divided back out.
        if (player.isSprinting()) ratio /= VANILLA_SPRINT_BOOST;
        float walk = player.getWalkSpeed();
        if (walk > VANILLA_WALK_SPEED) ratio = Math.max(ratio, walk / VANILLA_WALK_SPEED);
        return Math.max(1.0, ratio);
    }

    /** Fraction of walking speed a sneaking player may reach (Swift Sneak raises this). */
    public static double sneakingSpeedFactor(Player player) {
        return Math.max(VANILLA_SNEAKING_SPEED,
                attribute(player, Attribute.SNEAKING_SPEED, VANILLA_SNEAKING_SPEED));
    }

    /** Legitimate auto-step height — raised by datapacks/items granting higher steps. */
    public static double stepHeight(Player player) {
        return Math.max(VANILLA_STEP_HEIGHT, attribute(player, Attribute.STEP_HEIGHT, VANILLA_STEP_HEIGHT));
    }

    /** How much higher than vanilla this player may jump, as a multiplier ≥ 1. */
    public static double jumpStrengthRatio(Player player) {
        return Math.max(1.0,
                attribute(player, Attribute.JUMP_STRENGTH, VANILLA_JUMP_STRENGTH) / VANILLA_JUMP_STRENGTH);
    }

    /**
     * True when the player falls slower than vanilla. Reduced gravity means hanging in the
     * air for many ticks without descending is legitimate, which is exactly the pattern the
     * hover check flags.
     */
    public static boolean hasReducedGravity(Player player) {
        return attribute(player, Attribute.GRAVITY, VANILLA_GRAVITY) < VANILLA_GRAVITY - 1.0e-6;
    }

    /**
     * Swim-speed multiplier from {@code water_movement_efficiency} (0 = vanilla drag,
     * 1 = none). This is the attribute vanilla maps Depth Strider onto, so reading it
     * also covers boots and plugins that grant the same effect without the enchantment.
     */
    public static double waterEfficiencyMultiplier(Player player) {
        double eff = attribute(player, Attribute.WATER_MOVEMENT_EFFICIENCY, 0.0);
        return 1.0 + Math.max(0.0, Math.min(1.0, eff));
    }

    /** Body scale (1.0 = vanilla). A resized player has a proportionally larger hitbox. */
    public static double scale(Player player) {
        return Math.max(1.0, attribute(player, Attribute.SCALE, 1.0));
    }

    /**
     * "world [x, y, z]" for alert text and database rows. Movement-family violations
     * used to log only the player and the reason, so an alert could not be judged after
     * the fact — the in-memory alert holding the position is dropped after 30 minutes,
     * and by then nobody can tell whether the player was on a boat, a decoration or
     * genuinely mid-air. XRay rows always carried their coordinates; now all of them do.
     */
    public static String formatLocation(Location loc) {
        if (loc == null) return "unknown";
        String world = loc.getWorld() != null ? loc.getWorld().getName() : "unknown";
        return String.format(java.util.Locale.ROOT, "%s [%d, %d, %d]",
                world, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
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
