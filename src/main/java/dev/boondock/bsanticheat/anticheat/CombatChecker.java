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

import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

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
    private MeleeTracker meleeTracker;

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

    /**
     * Teleport / join / respawn / world-change grace, per player.
     *
     * <p>Every check family had one of these except this one: MovementChecker keeps
     * recentTeleport and recentJoin, PacketChecker keeps graceUntil. Combat had none, and it
     * needs one for the same reason they do — a teleport moves an entity server-side while
     * the attacking client still has the old position, so the distance measured at event time
     * is one nobody involved could see. It applies to BOTH sides: being teleported yourself
     * desyncs your view of everyone, and a target arriving next to you desyncs theirs.
     */
    private final Map<UUID, Long> graceUntil = new ConcurrentHashMap<>();

    /**
     * Last damage event per attacker: [0]=victim UUID high bits, [1]=low bits, [2]=time(ms).
     *
     * <p>One swing does not always produce one event. Item plugins that add their own damage
     * (MMOItems/MythicLib here) fire EntityDamageByEntityEvent several times for the same hit,
     * with identical geometry. Measured on this server 2026-08-27: of 327 hit groups, 83 came
     * twice, 76 three times and 4 four times — the same attacker, the same victim, the same
     * aim angle to the degree. Every streak requirement in this class is defeated by that: a
     * reach_violations of 3 is reached by ONE over-reach hit, which is exactly what the
     * counters exist to prevent. Weapon cooldowns make genuine hits on the same victim
     * hundreds of milliseconds apart, so anything inside one tick is the same swing.
     */
    private final Map<UUID, long[]> lastHit = new ConcurrentHashMap<>();
    private static final long SAME_SWING_MS = 60L;
    private static final long TELEPORT_GRACE_MS = 2000L;
    private static final long JOIN_GRACE_MS = 3000L;
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

    public void setMeleeTracker(MeleeTracker meleeTracker) {
        this.meleeTracker = meleeTracker;
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

        // A position desync on EITHER side makes the measured distance meaningless, so both
        // are checked: the attacker may have just been teleported, and so may the target.
        long now = System.currentTimeMillis();
        if (inGrace(attacker.getUniqueId(), now) || inGrace(victim.getUniqueId(), now)) {
            resetStreaks(attacker.getUniqueId());
            return;
        }

        // Don't run combat checks against NPCs (Citizens tags them with "NPC" metadata) —
        // legitimate to hit and their hitboxes/positions are often unusual.
        if (victim.hasMetadata("NPC")) return;

        // The same swing, delivered again by an item plugin, is not a second piece of
        // evidence. Dropped before any streak is touched.
        if (isRepeatOfSameSwing(attacker.getUniqueId(), victim.getUniqueId(), now)) return;

        // Damage the client never asked for is not a swing, and every check below reads the
        // GEOMETRY of a swing. An item plugin's ranged ability arrives here shaped exactly
        // like a melee hit — same cause, same damager — but from wherever the ability reaches,
        // hitting whatever it covers. Judging that as reach, aim angle or multi-target says
        // nothing about the player; see MeleeTracker for the live case behind this.
        if (meleeTracker != null && !meleeTracker.isMeleeHit(attacker.getUniqueId(), now)) {
            if (config.debugMode()) {
                plugin.getLogger().info("[COMBAT-DEBUG] " + attacker.getName()
                        + ": Schaden ohne Angriffspaket (Plugin-Ability) -> nicht bewertet");
            }
            resetStreaks(attacker.getUniqueId());
            return;
        }

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

        // --- Reach --- (own method: its early exits must not skip the checks below)
        if (config.reachDetectionEnabled()) checkReach(attacker, victim, attackerId);

        // --- KillAura --- (optionally only against player targets to avoid mob-grinding FPs)
        if (config.killAuraDetectionEnabled() && (!config.killAuraPlayersOnly() || victimIsPlayer)) {
            // (a) Aim angle: attacker must roughly face the target
            Vector look = attacker.getEyeLocation().getDirection();
            Vector toTarget = victim.getLocation().clone().add(0, victim.getHeight() / 2.0, 0).toVector()
                    .subtract(attacker.getEyeLocation().toVector());
            if (toTarget.lengthSquared() > 1.0e-6) {
                double angle = Math.toDegrees(look.angle(toTarget));
                if (config.debugMode()) {
                    plugin.getLogger().info(String.format(java.util.Locale.ROOT, "[KA-DEBUG] %s angle=%.0f (max %.0f) targetPlayer=%b",
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

    /**
     * Reach, in its own method so that standing the check down cannot stand down the rest of
     * combat. It has two exits — an unusable ping and a normal verdict — and both used to be
     * a {@code return} out of the event handler, which took KillAura's aim angle and
     * multi-target count with them. Neither has anything to do with reach: the multi-target
     * count is a question about time, not about distance, and a cheat that inflates its own
     * measured latency (by answering transaction pings late, the very trick the timer check
     * guards against) would have switched all three off at once.
     */
    private void checkReach(Player attacker, LivingEntity victim, UUID attackerId) {
        // Measure to the hitbox surface, not the center — center-based distance
        // false-positives on large mobs (Ghast, Ravager) whose hitbox extends
        // multiple blocks from the center.
        double distance = Math.max(0.0, distanceToHitbox(attacker, victim) - HITBOX_ALLOWANCE);
        // Honour a raised interaction-range attribute: a datapack or plugin that grants
        // longer reach through it is granting it legitimately, and judging those hits
        // against the flat config value would flag a player for using their own gear.
        //
        // Do NOT expect item plugins to show up here. This was written assuming MMOItems
        // and friends work that way; measured on this server 2026-08-27, they do not —
        // the attribute read 3.0 (vanilla) for every player while their weapons were
        // reaching much further. Long-reach ITEM behaviour arrives as an ability that
        // deals damage directly, which does not touch this attribute at all and is
        // handled where it actually shows up, by MeleeTracker. This lookup is kept
        // because it is correct for anything that does use the attribute, not because it
        // covers item plugins.
        double configured = config.reachDistance();
        var range = attacker.getAttribute(org.bukkit.attribute.Attribute.ENTITY_INTERACTION_RANGE);
        if (range != null) {
            configured = Math.max(configured, range.getValue() + Constants.REACH_ATTRIBUTE_SLACK);
        }
        // Latency is an ADDITIVE error here, not a multiplicative one. pingSlack scales a
        // limit, which is right for a speed (blocks per tick x slack) and wrong for a
        // distance: what latency costs is however far the target travelled while the hit
        // was in flight, and that is speed x time. Measured on this server 2026-08-27 in a
        // four-way PvP session at 373-1519ms round trips: the old model allowed 4.7-5.5
        // blocks while a sprinting pair separates by 4.2-17.0 in one round trip, and the
        // alerts ran to 14.61 — a figure the multiplicative model cannot produce and
        // ordinary play can.
        int ping = CheckMath.effectivePing(transactionManager, attacker);
        // Above this there is nothing left to compensate — the server simply cannot tell
        // where either player was. Standing down is the honest answer; compensating ever
        // more generously would just turn the check into a hole that scales with latency,
        // and a cheat can inflate its own measured latency.
        if (ping > config.reachMaxPingMs()) {
            reachStreak.remove(attackerId);
            if (config.debugMode()) {
                plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                        "[REACH-DEBUG] %s uebersprungen: ping=%dms > %dms",
                        attacker.getName(), ping, config.reachMaxPingMs()));
            }
            return;
        }
        double latencyAllowance = Math.min(config.reachMaxLatencyBlocks(),
                Constants.SPRINT_BLOCKS_PER_SECOND * ping / 1000.0);
        // A resized player reaches proportionally further — their arms are longer.
        double maxReach = configured * CheckMath.scale(attacker) + latencyAllowance;
        if (config.debugMode() && distance > maxReach * 0.8) {
            // Logged from 80% of the limit, not only on the flag: the 2026-08-26 alert
            // (7.26 blocks against a 4.00 cap) could not be judged afterwards because
            // this check, alone among them, recorded nothing at all.
            plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                    "[REACH-DEBUG] %s -> %s dist=%.2f max=%.2f (konfig=%.2f scale=%.2f "
                            + "latenz=+%.2f ping=%dms) streak=%d/%d",
                    attacker.getName(), victim.getType().name(), distance, maxReach,
                    configured, CheckMath.scale(attacker), latencyAllowance, ping,
                    (int) reachStreak.getOrDefault(attackerId, new long[]{0, 0})[0],
                    config.reachViolations()));
        }
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

    /**
     * True when this is the same swing arriving again — same attacker, same victim, inside one
     * tick. Package-private so the rule can be exercised without an event.
     */
    boolean isRepeatOfSameSwing(UUID attacker, UUID victim, long now) {
        long hi = victim.getMostSignificantBits();
        long lo = victim.getLeastSignificantBits();
        long[] prev = lastHit.get(attacker);
        boolean repeat = prev != null && prev[0] == hi && prev[1] == lo
                && now - prev[2] < SAME_SWING_MS;
        if (!repeat) lastHit.put(attacker, new long[]{hi, lo, now});
        return repeat;
    }

    /** Lapse a grace window without waiting for it, so the expiry can be tested. */
    void expireGraceForTest(UUID id) {
        graceUntil.remove(id);
    }

    /** True while a teleport/join/respawn/world change still has this player's positions in flux. */
    private boolean inGrace(UUID id, long now) {
        Long until = graceUntil.get(id);
        return until != null && now < until;
    }

    /**
     * Drop the streaks as well as skipping the hit. A streak built partly from before a
     * teleport and partly from after it is evidence from two different places.
     */
    private void resetStreaks(UUID id) {
        reachStreak.remove(id);
        killAuraAngleStreak.remove(id);
        killAuraMultiStreak.remove(id);
        recentTargets.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        graceUntil.put(event.getPlayer().getUniqueId(),
                System.currentTimeMillis() + TELEPORT_GRACE_MS);
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        graceUntil.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + JOIN_GRACE_MS);
    }

    @EventHandler
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        graceUntil.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + JOIN_GRACE_MS);
    }

    @EventHandler
    public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent event) {
        graceUntil.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + JOIN_GRACE_MS);
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
        // Concurrent, not ArrayDeque: on Folia an EntityDamageByEntityEvent runs on the
        // VICTIM's region thread, so one attacker hitting entities that belong to two
        // different regions reaches this deque from two threads at once.
        Deque<TargetHit> hits = recentTargets.computeIfAbsent(attacker, k -> new ConcurrentLinkedDeque<>());
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
        graceUntil.remove(playerId);
        lastHit.remove(playerId);
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
