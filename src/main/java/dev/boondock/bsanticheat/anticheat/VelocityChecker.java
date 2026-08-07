package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.integration.GeyserHook;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.lang.LanguageManager;
import dev.boondock.bsanticheat.util.CheckMath;
import dev.boondock.bsanticheat.util.Constants;
import dev.boondock.bsanticheat.util.Scheduler;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Velocity / AntiKnockback check (Masterplan phase 2.3).
 *
 * <p>When the server applies knockback it fires {@link PlayerVelocityEvent} with the velocity
 * the client is expected to apply. A few ticks later we compare the player's actual horizontal
 * displacement along the knockback direction against that expectation: a client that ignores or
 * reduces knockback (AntiKB/Velocity cheat) barely moves, while a vanilla client is pushed the
 * full amount in the first tick alone.
 *
 * <p>The delayed evaluation runs on the player's region thread (Folia-safe). Exemptions cover
 * the legitimate ways knockback is reduced/blocked (water, vehicle, gliding, riptide, a
 * climbable, or a wall in the knockback direction). Note: items granting near-total knockback
 * resistance are a known calibration caveat — netherite (~0.4) still moves the player enough to
 * pass. Off-by-default-friendly: gated by {@code velocity_detection}.
 */
public class VelocityChecker implements Listener {

    private final Plugin plugin;
    private final PluginConfig config;
    private final DatabaseManager database;
    private final LanguageManager lang;
    private LuckPermsHook luckPerms;
    private GeyserHook geyser;
    private MovementAlertManager alertManager;
    private ViolationManager violationManager;
    private TransactionManager transactionManager;

    private final Map<UUID, Integer> consecutive = new ConcurrentHashMap<>();

    public VelocityChecker(Plugin plugin, PluginConfig config, DatabaseManager database, LanguageManager lang) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.lang = lang;
    }

    public void setLuckPerms(LuckPermsHook luckPerms) {
        this.luckPerms = luckPerms;
    }

    public void setGeyser(GeyserHook geyser) {
        this.geyser = geyser;
    }

    public void setAlertManager(MovementAlertManager alertManager) {
        this.alertManager = alertManager;
    }

    public void setViolationManager(ViolationManager violationManager) {
        this.violationManager = violationManager;
    }

    public void setTransactionManager(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerVelocity(PlayerVelocityEvent event) {
        if (!config.velocityDetectionEnabled()) return;
        if (ServerLoad.isLagging(config)) return;

        Player player = event.getPlayer();
        if (Exemptions.isExempt(player, config, luckPerms, geyser)) return;

        Vector v = event.getVelocity();
        double expectedH = Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ());
        if (expectedH < Constants.VELOCITY_MIN_KB) return;   // not a real knockback

        // Legit reasons a knockback is reduced/blocked
        // isFlying/getAllowFlight covers survival flight granted by another plugin
        // (EssentialsX /fly): knockback barely displaces a flying player.
        if (player.isInWater() || player.isInsideVehicle() || player.isGliding() || player.isRiptiding()
                || player.isFlying() || player.getAllowFlight() || isOnClimbable(player)) {
            return;
        }

        final Location start = player.getLocation().clone();
        final double dirX = v.getX() / expectedH;
        final double dirZ = v.getZ() / expectedH;

        // A wall directly in the knockback direction legitimately stops the push.
        if (isWall(start, dirX, dirZ)) return;

        // Evaluate a few ticks later, on the player's own region thread (Folia-safe). The
        // window is extended by the transaction latency so a laggy client still has time to
        // report the movement it applied.
        long delay = Constants.VELOCITY_EVAL_DELAY_TICKS;
        if (transactionManager != null) {
            double rttMs = transactionManager.roundTripMs(player.getUniqueId());
            if (rttMs > 0) delay += Math.min(10L, Math.round(rttMs / 50.0));
        }
        UUID id = player.getUniqueId();
        Scheduler.runForEntityLater(plugin, player,
                () -> evaluate(id, start, expectedH, dirX, dirZ), delay);
    }

    private void evaluate(UUID id, Location start, double expectedH, double dirX, double dirZ) {
        Player player = org.bukkit.Bukkit.getPlayer(id);
        if (player == null) return;
        if (Exemptions.isExempt(player, config, luckPerms, geyser)) return;
        if (!player.getWorld().equals(start.getWorld())) return;

        Location now = player.getLocation();
        double dx = now.getX() - start.getX();
        double dz = now.getZ() - start.getZ();
        // Displacement projected onto the knockback direction (ignores incidental sideways move)
        double along = dx * dirX + dz * dirZ;

        if (along < expectedH * config.velocityMinApplyRatio()) {
            int c = consecutive.merge(id, 1, Integer::sum);
            if (config.debugMode()) {
                plugin.getLogger().info(String.format("[VELOCITY-DEBUG] %s applied=%.3f expected>=%.3f (%d/%d)",
                        player.getName(), along, expectedH * config.velocityMinApplyRatio(), c, config.velocityViolations()));
            }
            if (c >= config.velocityViolations()) {
                handleViolation(player, "VELOCITY", lang.format("alert.velocity", along, expectedH), along, now);
                consecutive.put(id, 0);
            }
        } else {
            consecutive.remove(id);
        }
    }

    /** True when a solid block sits directly in the knockback direction at body height. */
    private boolean isWall(Location start, double dirX, double dirZ) {
        Location probe = start.clone().add(dirX * 0.6, 0.5, dirZ * 0.6);
        return probe.getBlock().getType().isSolid();
    }

    /**
     * Climbable via the game's own tag, not a hand-rolled list: the latter misses the
     * *_PLANT growth variants (CAVE_VINES_PLANT etc.) and every future climbable, which
     * would flag a player who legitimately absorbed the knockback on a vine.
     */
    private boolean isOnClimbable(Player player) {
        if (player.isClimbing()) return true;
        Material at = player.getLocation().getBlock().getType();
        return at.isBlock() && org.bukkit.Tag.CLIMBABLE.isTagged(at);
    }

    private void handleViolation(Player player, String type, String details, double value, Location location) {
        if (database != null) {
            database.logAsync("anticheat_" + type.toLowerCase(), value,
                    player.getName() + ": " + details + " @ " + CheckMath.formatLocation(location));
        }
        if (alertManager != null) {
            alertManager.addAlert(player, type, details, value, location);
        } else if (config.debugMode()) {
            plugin.getLogger().warning("[AntiCheat] " + player.getName() + " " + type + " - " + details);
        }
        if (violationManager != null) {
            violationManager.flag(player, type);
        }
    }

    public void cleanup(UUID playerId) {
        consecutive.remove(playerId);
    }
}
