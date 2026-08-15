package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.integration.GeyserHook;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.lang.LanguageManager;
import dev.boondock.bsanticheat.util.CheckMath;
import dev.boondock.bsanticheat.util.Constants;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
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
    private GeyserHook geyser;
    private MovementAlertManager alertManager;
    private ViolationManager violationManager;
    private TransactionManager transactionManager;

    // Slack for latency/hitbox interpolation on top of the surface distance.
    private static final double HITBOX_ALLOWANCE = 0.3;

    // Recent attacks per attacker, for multi-target (multi-aura) detection.
    private final Map<UUID, Deque<TargetHit>> recentTargets = new ConcurrentHashMap<>();
    // Criticals / AutoBlock: consecutive impossible hits
    private final Map<UUID, Integer> consecutiveCriticals = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> consecutiveAutoBlock = new ConcurrentHashMap<>();
    // Reach / KillAura angle: consecutive suspicious hits within a short window. A single
    // hit measured at event time is too noisy to flag (latency moves both hitboxes; a
    // legit flick hit is judged against the pre-flick rotation) — a cheat exceeds the
    // limit on every hit anyway. The window matters: without it, hits that bypass these
    // checks entirely (mob hits with killaura_players_only, no-reach-check hits) never
    // reset the counter, so rare legit outliers would accumulate across a whole session.
    // State: [0]=count, [1]=timestamp of last suspicious hit (ms).
    private final Map<UUID, long[]> reachStreak = new ConcurrentHashMap<>();
    private final Map<UUID, long[]> killAuraAngleStreak = new ConcurrentHashMap<>();
    private final Map<UUID, long[]> killAuraMultiStreak = new ConcurrentHashMap<>();
    private static final long STREAK_WINDOW_MS = 10000L;

    public CombatChecker(Plugin plugin, PluginConfig config, DatabaseManager database, LanguageManager lang) {
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

    /** Suspicious-hit streak: increment within the window, restart when it lapsed. */
    private int bumpStreak(Map<UUID, long[]> map, UUID id) {
        long now = System.currentTimeMillis();
        long[] st = map.compute(id, (k, v) -> {
            if (v == null || now - v[1] > STREAK_WINDOW_MS) return new long[]{1, now};
            v[0]++;
            v[1] = now;
            return v;
        });
        return (int) st[0];
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!config.combatChecksEnabled()) return;
        if (ServerLoad.isLagging(config)) return;

        // Only direct melee from a player against a living target.
        // The cause gate matters: Thorns fires this event with the ARMOR WEARER as
        // damager (cause THORNS) — without it the innocent victim gets flagged.
        // Sweep hits land on entities the attacker never aimed at, so they are
        // excluded from reach/angle too, not just from multi-aura.
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        if (Exemptions.isExempt(attacker, config, luckPerms, geyser)) return;
        if (attacker.getWorld() != victim.getWorld()) return;

        // Don't run combat checks against NPCs (Citizens tags them with "NPC" metadata) —
        // legitimate to hit and their hitboxes/positions are often unusual.
        if (victim.hasMetadata("NPC")) return;

        boolean victimIsPlayer = victim instanceof Player;
        UUID attackerId = attacker.getUniqueId();

        // --- Criticals (OFF by default — see config.yml) ---
        // Caveat, verified against the vanilla rules: the server only awards a critical
        // when fallDistance > 0 AND the player is airborne, and event.isCritical() reports
        // exactly that server-side decision. "critical AND on ground AND fallDistance <= 0"
        // is therefore a contradiction that a genuine vanilla crit can never satisfy — the
        // condition can only be met by a damage event another plugin synthesised with the
        // critical flag set. It also does not catch the actual Criticals cheat, which feeds
        // the server fake micro-falls so the crit is computed while the player is reported
        // airborne. Distinguishing that from a legitimate jump-crit needs the per-tick
        // vertical movement from the packet layer, not this event.
        if (config.criticalsDetectionEnabled() && event.isCritical()
                && attacker.isOnGround() && attacker.getFallDistance() <= 0.0f) {
            int c = consecutiveCriticals.merge(attackerId, 1, Integer::sum);
            if (c >= config.criticalsViolations()) {
                handleViolation(attacker, "CRITICALS", lang.get("alert.criticals"), c);
                consecutiveCriticals.put(attackerId, 0);
            }
        } else {
            consecutiveCriticals.remove(attackerId);
        }

        // --- AutoBlock: attacking while actively blocking with a shield. Vanilla lowers
        // the shield to attack; only a cheat can do both in the same moment.
        if (config.autoBlockDetectionEnabled() && attacker.isBlocking()) {
            int c = consecutiveAutoBlock.merge(attackerId, 1, Integer::sum);
            if (c >= config.autoBlockViolations()) {
                handleViolation(attacker, "AUTOBLOCK", lang.get("alert.autoblock"), c);
                consecutiveAutoBlock.put(attackerId, 0);
            }
        } else {
            consecutiveAutoBlock.remove(attackerId);
        }

        // --- Reach ---
        if (config.reachDetectionEnabled()) {
            // Measure to the hitbox surface, not the center — center-based distance
            // false-positives on large mobs (Ghast, Ravager) whose hitbox extends
            // multiple blocks from the center.
            double distance = Math.max(0.0, distanceToHitbox(attacker, victim) - HITBOX_ALLOWANCE);
            // Latency stretches the measured distance: both hitboxes moved between the
            // client's aim and the server's judgement. Same sqrt scaling as the movement
            // checks (200ms → +10%, 500ms → +20%).
            // Honour a raised interaction-range attribute. Item plugins (MMOItems and
            // friends) grant long-reach weapons by modifying it — judging those hits
            // against the flat config value flags the player for using their own gear.
            double configured = config.reachDistance();
            var range = attacker.getAttribute(org.bukkit.attribute.Attribute.ENTITY_INTERACTION_RANGE);
            if (range != null) {
                configured = Math.max(configured, range.getValue() + Constants.REACH_ATTRIBUTE_SLACK);
            }
            // A resized player reaches proportionally further — their arms are longer.
            double maxReach = configured * CheckMath.scale(attacker)
                    * CheckMath.pingSlack(
                            CheckMath.effectivePing(transactionManager, attacker));
            if (distance > maxReach) {
                int c = bumpStreak(reachStreak, attackerId);
                if (c >= config.reachViolations()) {
                    handleViolation(attacker, "REACH", lang.format("alert.reach", distance, maxReach), distance);
                    reachStreak.remove(attackerId);
                }
            } else {
                reachStreak.remove(attackerId);
            }
        }

        // --- KillAura --- (optionally only against player targets to avoid mob-grinding FPs)
        if (config.killAuraDetectionEnabled() && (!config.killAuraPlayersOnly() || victimIsPlayer)) {
            // (a) Aim angle: attacker must roughly face the target
            Vector look = attacker.getEyeLocation().getDirection();
            Vector toTarget = victim.getLocation().clone().add(0, victim.getHeight() / 2.0, 0).toVector()
                    .subtract(attacker.getEyeLocation().toVector());
            if (toTarget.lengthSquared() > 1.0e-6) {
                double angle = Math.toDegrees(look.angle(toTarget));
                if (config.debugMode()) {
                    plugin.getLogger().info(String.format("[KA-DEBUG] %s angle=%.0f (max %.0f) targetPlayer=%b",
                            attacker.getName(), angle, config.killAuraMaxAngle(), victimIsPlayer));
                }
                if (angle > config.killAuraMaxAngle()) {
                    int c = bumpStreak(killAuraAngleStreak, attackerId);
                    if (c >= config.killAuraAngleViolations()) {
                        handleViolation(attacker, "KILLAURA", lang.format("alert.killaura_angle", angle), angle);
                        killAuraAngleStreak.remove(attackerId);
                    }
                } else {
                    killAuraAngleStreak.remove(attackerId);
                }
            }

            // (b) Multi-aura: several distinct targets hit within a tiny window.
            // (Sweeping-edge hits are already excluded by the ENTITY_ATTACK gate above.)
            // Unlike reach and aim angle this had no streak requirement, so a single burst
            // flagged outright — and a crowded team fight legitimately puts three players
            // within reach inside 250ms. A killaura sustains it; a scramble does not.
            int distinct = recordTarget(attacker.getUniqueId(), victim.getUniqueId());
            if (distinct >= config.killAuraMultiTargets()
                    && bumpStreak(killAuraMultiStreak, attackerId) >= config.killAuraMultiViolations()) {
                killAuraMultiStreak.remove(attackerId);
                handleViolation(attacker, "KILLAURA",
                        lang.format("alert.killaura_multi", distinct, Constants.KILLAURA_MULTI_WINDOW_MS), distinct);
                // Drop the evidence after flagging, like every other check. Without this
                // each further hit inside the (short) window still sees the same distinct
                // targets and re-flags, so one burst raised the VL several times over.
                recentTargets.remove(attackerId);
            }
        }
    }

    /** Distance from the attacker's eyes to the nearest point of the victim's bounding box. */
    private double distanceToHitbox(Player attacker, LivingEntity victim) {
        org.bukkit.util.BoundingBox box = victim.getBoundingBox();
        org.bukkit.Location eye = attacker.getEyeLocation();
        double dx = eye.getX() - Math.max(box.getMinX(), Math.min(eye.getX(), box.getMaxX()));
        double dy = eye.getY() - Math.max(box.getMinY(), Math.min(eye.getY(), box.getMaxY()));
        double dz = eye.getZ() - Math.max(box.getMinZ(), Math.min(eye.getZ(), box.getMaxZ()));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
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
            database.logAsync("anticheat_" + type.toLowerCase(), value,
                    player.getName() + ": " + details + " @ " + CheckMath.formatLocation(player.getLocation()));
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
        consecutiveCriticals.remove(playerId);
        consecutiveAutoBlock.remove(playerId);
        reachStreak.remove(playerId);
        killAuraAngleStreak.remove(playerId);
        killAuraMultiStreak.remove(playerId);
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
