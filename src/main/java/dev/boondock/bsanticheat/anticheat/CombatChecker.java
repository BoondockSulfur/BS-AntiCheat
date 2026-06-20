package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.lang.LanguageManager;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.Plugin;

/**
 * Basic combat checks. Currently: Reach — a melee hit landed from further away than
 * vanilla allows.
 *
 * <p>This is event-based (not packet-based), so the threshold is deliberately generous to
 * avoid false positives from latency and hitbox interpolation. Precise reach detection
 * needs the packet layer (planned).
 */
public class CombatChecker implements Listener {

    private final Plugin plugin;
    private final PluginConfig config;
    private final DatabaseManager database;
    private final LanguageManager lang;
    private LuckPermsHook luckPerms;
    private MovementAlertManager alertManager;
    private ViolationManager violationManager;

    // Approximate horizontal half-width of a player hitbox, subtracted so we measure to
    // the edge of the victim rather than its centre.
    private static final double HITBOX_ALLOWANCE = 0.3;

    public CombatChecker(Plugin plugin, PluginConfig config, DatabaseManager database, LanguageManager lang) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.lang = lang;
    }

    public void setLuckPerms(LuckPermsHook luckPerms) {
        this.luckPerms = luckPerms;
    }

    public void setAlertManager(MovementAlertManager alertManager) {
        this.alertManager = alertManager;
    }

    public void setViolationManager(ViolationManager violationManager) {
        this.violationManager = violationManager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!config.combatChecksEnabled() || !config.reachDetectionEnabled()) return;

        // Only direct melee from a player against a living target
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;

        if (Exemptions.isExempt(attacker, config, luckPerms)) return;

        // Both must be in the same world (damage events normally are)
        if (attacker.getWorld() != victim.getWorld()) return;

        // Distance from the attacker's eyes to the centre of the victim, minus a hitbox margin
        double victimCenterDistance = attacker.getEyeLocation().distance(
                victim.getLocation().clone().add(0, victim.getHeight() / 2.0, 0));
        double distance = Math.max(0.0, victimCenterDistance - HITBOX_ALLOWANCE);

        double maxReach = config.reachDistance();
        if (distance > maxReach) {
            handleViolation(attacker, distance, maxReach);
        }
    }

    private void handleViolation(Player player, double distance, double maxReach) {
        String details = lang.format("alert.reach", distance, maxReach);

        if (database != null) {
            database.logAsync("anticheat_reach", distance, player.getName() + ": " + details);
        }

        if (alertManager != null) {
            alertManager.addAlert(player, "REACH", details, distance, player.getLocation());
        } else if (config.debugMode()) {
            plugin.getLogger().warning("[AntiCheat] " + player.getName() + " REACH - " + details);
        }

        if (violationManager != null) {
            violationManager.flag(player, "REACH");
        }
    }
}
