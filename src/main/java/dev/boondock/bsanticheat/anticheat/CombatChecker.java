package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.lang.LanguageManager;
import dev.boondock.bsanticheat.util.Constants;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Combat checks (event-based):
 * <ul>
 *   <li>Reach — hit landed from further than vanilla allows.</li>
 *   <li>KillAura — hitting a target well outside the field of view (aim angle), or hitting
 *       several distinct targets within a few milliseconds (multi-aura).</li>
 * </ul>
 * Thresholds are deliberately generous (latency/hitbox interpolation); precise combat
 * detection would need the packet layer.
 */
public class CombatChecker implements Listener {

    private final Plugin plugin;
    private final PluginConfig config;
    private final DatabaseManager database;
    private final LanguageManager lang;
    private LuckPermsHook luckPerms;
    private MovementAlertManager alertManager;
    private ViolationManager violationManager;

    // Approximate horizontal half-width of a player hitbox.
    private static final double HITBOX_ALLOWANCE = 0.3;

    // Recent attacks per attacker, for multi-target (multi-aura) detection.
    private final Map<UUID, Deque<TargetHit>> recentTargets = new ConcurrentHashMap<>();

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
        if (!config.combatChecksEnabled()) return;
        if (ServerLoad.isLagging(config)) return;

        // Only direct melee from a player against a living target
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        if (Exemptions.isExempt(attacker, config, luckPerms)) return;
        if (attacker.getWorld() != victim.getWorld()) return;

        // --- Reach ---
        if (config.reachDetectionEnabled()) {
            double distance = Math.max(0.0, attacker.getEyeLocation().distance(
                    victim.getLocation().clone().add(0, victim.getHeight() / 2.0, 0)) - HITBOX_ALLOWANCE);
            double maxReach = config.reachDistance();
            if (distance > maxReach) {
                handleViolation(attacker, "REACH", lang.format("alert.reach", distance, maxReach), distance);
            }
        }

        // --- KillAura ---
        if (config.killAuraDetectionEnabled()) {
            // (a) Aim angle: attacker must roughly face the target
            Vector look = attacker.getEyeLocation().getDirection();
            Vector toTarget = victim.getLocation().clone().add(0, victim.getHeight() / 2.0, 0).toVector()
                    .subtract(attacker.getEyeLocation().toVector());
            if (toTarget.lengthSquared() > 1.0e-6) {
                double angle = Math.toDegrees(look.angle(toTarget));
                if (angle > config.killAuraMaxAngle()) {
                    handleViolation(attacker, "KILLAURA", lang.format("alert.killaura_angle", angle), angle);
                }
            }

            // (b) Multi-aura: several distinct targets hit within a tiny window
            int distinct = recordTarget(attacker.getUniqueId(), victim.getUniqueId());
            if (distinct >= config.killAuraMultiTargets()) {
                handleViolation(attacker, "KILLAURA",
                        lang.format("alert.killaura_multi", distinct, Constants.KILLAURA_MULTI_WINDOW_MS), distinct);
            }
        }
    }

    /** Record an attacked target and return the number of distinct targets within the window. */
    private int recordTarget(UUID attacker, UUID victim) {
        long now = System.currentTimeMillis();
        long cutoff = now - Constants.KILLAURA_MULTI_WINDOW_MS;
        Deque<TargetHit> hits = recentTargets.computeIfAbsent(attacker, k -> new ArrayDeque<>());
        hits.addLast(new TargetHit(victim, now));
        while (!hits.isEmpty() && hits.peekFirst().time < cutoff) hits.pollFirst();
        Set<UUID> distinct = new HashSet<>();
        for (TargetHit h : hits) distinct.add(h.victim);
        return distinct.size();
    }

    private void handleViolation(Player player, String type, String details, double value) {
        if (database != null) {
            database.logAsync("anticheat_" + type.toLowerCase(), value, player.getName() + ": " + details);
        }
        if (alertManager != null) {
            alertManager.addAlert(player, type, details, value, player.getLocation());
        } else if (config.debugMode()) {
            plugin.getLogger().warning("[AntiCheat] " + player.getName() + " " + type + " - " + details);
        }
        if (violationManager != null) {
            violationManager.flag(player, type);
        }
    }

    public void cleanup(UUID playerId) {
        recentTargets.remove(playerId);
    }

    private static final class TargetHit {
        final UUID victim;
        final long time;
        TargetHit(UUID victim, long time) {
            this.victim = victim;
            this.time = time;
        }
    }
}
