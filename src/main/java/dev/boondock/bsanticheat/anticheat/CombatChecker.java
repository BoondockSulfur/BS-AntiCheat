package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.integration.GeyserHook;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.lang.LanguageManager;
import dev.boondock.bsanticheat.util.CheckMath;
import dev.boondock.bsanticheat.util.Constants;
import dev.boondock.bsanticheat.util.ItemCompat;
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
    // Server-measured fall distance (shared with MovementChecker) and spear lunges, for the
    // mace smash check.
    private FallTracker fallTracker;
    private LungeTracker lungeTracker;
    private final Map<UUID, long[]> maceStreak = new ConcurrentHashMap<>();
    // Vanilla smashes only when the attacker has fallen more than this.
    private static final double SMASH_MIN_FALL = 1.5;
    // A lunge this recent makes the attacker's motion something the fall measurement does
    // not model.
    private static final long MACE_LUNGE_GRACE_MS = 3000L;

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
     * <p>Same purpose as MovementChecker's recentTeleport/recentJoin and PacketChecker's
     * graceUntil: a teleport moves an entity server-side while the attacking client still has
     * the old position, so the distance measured at event time is one nobody involved could
     * see. It applies to BOTH sides: being teleported yourself
     * desyncs your view of everyone, and a target arriving next to you desyncs theirs.
     */
    private final Map<UUID, Long> graceUntil = new ConcurrentHashMap<>();

    /**
     * Last damage event per attacker: [0]=victim UUID high bits, [1]=low bits, [2]=time(ms).
     *
     * <p>One swing does not always produce one event. Item plugins that add their own damage
     * (e.g. MMOItems/MythicLib) can fire EntityDamageByEntityEvent several times for the same
     * hit, with identical geometry. Counted separately, one over-reach hit would satisfy every
     * streak requirement in this class on its own. Weapon cooldowns make genuine hits on the
     * same victim hundreds of milliseconds apart, so anything inside one tick is the same
     * swing.
     */
    private final Map<UUID, long[]> lastHit = new ConcurrentHashMap<>();
    private static final long SAME_SWING_MS = 60L;
    private static final long TELEPORT_GRACE_MS = 2000L;
    private static final long JOIN_GRACE_MS = 3000L;
    private static final long STREAK_WINDOW_MS = 10000L;

    /**
     * Recent positions of players and vehicles, oldest first.
     *
     * <p>The attacking client sees a target where the server had it one round trip plus the
     * client's entity interpolation ago. Against a fast target (elytra, riptide, horse) that
     * is several blocks from where it is now, so reach is measured against the closest
     * position the target held within that lag window.
     */
    private final Map<UUID, Deque<Pos>> positions = new ConcurrentHashMap<>();

    private record Pos(UUID world, double x, double y, double z, long time) {}
    private static final long POSITION_HISTORY_MS = 1500L;
    // Vanilla clients interpolate other entities over 3 ticks.
    private static final long ENTITY_INTERPOLATION_MS = 150L;
    // Tracker update granularity and round-trip jitter on top of the measured ping.
    private static final long LAG_WINDOW_JITTER_MS = 100L;
    // With the target's travel measured from history, only the attacker's own lead over its
    // last processed position remains: two ticks of sprinting.
    private static final double HISTORY_MODE_SLACK = Constants.SPRINT_BLOCKS_PER_SECOND * 0.1;
    // Ceiling for the velocity-based allowance of targets without a position history (mobs).
    private static final double VICTIM_VELOCITY_MAX_BLOCKS = 6.0;
    // Histories not updated within POSITION_HISTORY_MS are dropped at most this often.
    private static final long POSITION_SWEEP_INTERVAL_MS = 5000L;
    private volatile long lastPositionSweep;

    // KillAura angle: closer than this the aim angle is not meaningful (overlapping hitboxes).
    private static final double KILLAURA_MIN_ANGLE_DISTANCE = 1.0;

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

    public void setFallTracker(FallTracker fallTracker) {
        this.fallTracker = fallTracker;
    }

    public void setLungeTracker(LungeTracker lungeTracker) {
        this.lungeTracker = lungeTracker;
    }

    /** Suspicious-hit streak: increment within the window, restart when it lapsed. */
    private int bumpStreak(Map<UUID, long[]> map, UUID id) {
        return bumpStreak(map, id, System.currentTimeMillis(), 0L);
    }

    /**
     * Streak bump that counts at most once per {@code minSpacingMs}: a further bump inside
     * that spacing belongs to the same burst and leaves the count unchanged.
     */
    static int bumpStreak(Map<UUID, long[]> map, UUID id, long now, long minSpacingMs) {
        long[] st = map.compute(id, (k, v) -> {
            if (v == null || now - v[1] > STREAK_WINDOW_MS) return new long[]{1, now};
            if (now - v[1] < minSpacingMs) return v;
            v[0]++;
            v[1] = now;
            return v;
        });
        return (int) st[0];
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!config.combatChecksEnabled()) return;

        // Only direct melee from a player against a living target.
        // The cause gate matters: Thorns fires this event with the ARMOR WEARER as
        // damager (cause THORNS) — without it the innocent victim gets flagged.
        // Sweep hits land on entities the attacker never aimed at, so they are
        // excluded from reach/angle too, not just from multi-aura.
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        if (ServerLoad.isLagging(config, attacker)) return;
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
        // nothing about the player; see MeleeTracker.
        if (meleeTracker != null && !meleeTracker.isMeleeHit(attacker.getUniqueId(), victim.getEntityId(), now)) {
            if (config.debugMode()) {
                plugin.getLogger().info("[COMBAT-DEBUG] " + attacker.getName()
                        + ": damage without an attack packet (plugin ability) -> not judged");
            }
            resetStreaks(attacker.getUniqueId());
            return;
        }

        boolean victimIsPlayer = victim instanceof Player;
        UUID attackerId = attacker.getUniqueId();

        // --- Mace smash (OFF by default) ---
        checkMaceSmash(attacker, attackerId, now);

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
            // (a) Aim angle: attacker must roughly face the target. Measured to the nearest
            // part of the hitbox, not its centre: up close the centre can sit far off the
            // crosshair while the box is squarely under it. Players inside each other have
            // no meaningful angle at all, so those hits are skipped.
            org.bukkit.Location eye = attacker.getEyeLocation();
            if (distanceToHitbox(attacker, victim) >= KILLAURA_MIN_ANGLE_DISTANCE) {
                double angle = angleToBox(eye.toVector(), eye.getDirection(), victim.getBoundingBox());
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
            // Requires a streak like reach and aim angle: a crowded fight legitimately puts
            // several players within reach inside one window. A killaura sustains it; a
            // scramble does not.
            // One burst counts once: the targets are dropped after each bump and a bump within
            // the same window changes nothing, so four hits on three players in one scramble
            // are a streak of one, not two.
            int distinct = recordTarget(attacker.getUniqueId(), victim.getUniqueId());
            if (distinct >= config.killAuraMultiTargets()) {
                recentTargets.remove(attackerId);
                int c = bumpStreak(killAuraMultiStreak, attackerId, now, Constants.KILLAURA_MULTI_WINDOW_MS);
                if (c >= config.killAuraMultiViolations()) {
                    killAuraMultiStreak.remove(attackerId);
                    handleViolation(attacker, "KILLAURA",
                            lang.format("alert.killaura_multi", distinct, Constants.KILLAURA_MULTI_WINDOW_MS), distinct);
                }
            }
        }
    }

    /**
     * Reach, in its own method so that standing the check down cannot stand down the rest of
     * combat. Its early exits (unusable ping, verdict) must not skip KillAura's aim angle and
     * multi-target count, which do not depend on distance; otherwise a cheat that inflates its
     * measured latency (answering transaction pings late) would switch all three off at once.
     */
    private void checkReach(Player attacker, LivingEntity victim, UUID attackerId) {
        // Measure to the hitbox surface, not the center — center-based distance
        // false-positives on large mobs (Ghast, Ravager) whose hitbox extends
        // multiple blocks from the center.
        int ping = CheckMath.effectivePing(transactionManager, attacker);
        long lagWindowMs = ping + ENTITY_INTERPOLATION_MS + LAG_WINDOW_JITTER_MS;
        org.bukkit.entity.Entity mover = rootVehicle(victim);
        boolean historyMode = (mover == victim && victim instanceof Player)
                || (mover instanceof org.bukkit.entity.Vehicle && positions.containsKey(mover.getUniqueId()));
        double measured = historyMode
                ? minDistanceOverLag(attacker, victim, mover, System.currentTimeMillis() - lagWindowMs)
                : distanceToHitbox(attacker, victim);
        double distance = Math.max(0.0, measured - HITBOX_ALLOWANCE);
        // Honour a raised interaction-range attribute: a datapack or plugin that grants
        // longer reach through it is granting it legitimately, and judging those hits
        // against the flat config value would flag a player for using their own gear.
        //
        // Item plugins with long-reach weapons typically do not use this attribute; their
        // reach arrives as ability damage, which MeleeTracker handles. This lookup covers
        // only what does use the attribute.
        double configured = config.reachDistance();
        var range = attacker.getAttribute(org.bukkit.attribute.Attribute.ENTITY_INTERACTION_RANGE);
        if (range != null) {
            configured = Math.max(configured, range.getValue() + Constants.REACH_ATTRIBUTE_SLACK);
        }
        // Weapons with an attack_range component (the spear, MC 1.21.11+, reaches 4.5 plus a
        // 0.125 hitbox margin) set their own melee reach. The item is read from the main hand
        // NOW, at damage time: this event fires while the server processes the attack packet,
        // after every hotbar change the client sent before it and before any sent after it —
        // so it is exactly the item the attack was made with, and swapping to a long weapon
        // afterwards cannot lend its reach to the hit.
        ItemCompat.AttackRange weapon = ItemCompat.attackRange(attacker.getInventory().getItemInMainHand());
        configured = itemReachLimit(configured, weapon);
        // Latency is an ADDITIVE error here, not a multiplicative one. pingSlack scales a
        // limit, which is right for a speed (blocks per tick x slack) and wrong for a
        // distance: what latency costs is however far the target travelled while the hit
        // was in flight, and that is speed x time. At high round trips a sprinting pair
        // separates by far more than a scaled limit allows.
        // Above this there is nothing left to compensate — the server simply cannot tell
        // where either player was. Standing down is the honest answer; compensating ever
        // more generously would just turn the check into a hole that scales with latency,
        // and a cheat can inflate its own measured latency.
        if (ping > config.reachMaxPingMs()) {
            reachStreak.remove(attackerId);
            if (config.debugMode()) {
                plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                        "[REACH-DEBUG] %s skipped: ping=%dms > %dms",
                        attacker.getName(), ping, config.reachMaxPingMs()));
            }
            return;
        }
        // With a position history the target's travel over the lag window is already in the
        // measured distance. Without one (mobs) it is estimated: at least a sprint, or the
        // target's actual velocity over the round trip plus interpolation if that is more.
        double latencyAllowance;
        if (historyMode) {
            latencyAllowance = HISTORY_MODE_SLACK;
        } else {
            latencyAllowance = Math.min(config.reachMaxLatencyBlocks(),
                    Constants.SPRINT_BLOCKS_PER_SECOND * ping / 1000.0);
            double victimSpeed = mover.getVelocity().length() * 20.0; // blocks per second
            double victimTravel = Math.min(VICTIM_VELOCITY_MAX_BLOCKS,
                    victimSpeed * (ping + ENTITY_INTERPOLATION_MS) / 1000.0);
            latencyAllowance = Math.max(latencyAllowance, victimTravel);
        }
        // A resized player reaches proportionally further — their arms are longer.
        double maxReach = configured * CheckMath.scale(attacker) + latencyAllowance;
        if (config.debugMode() && distance > maxReach * 0.8) {
            // Logged from 80% of the limit, not only on the flag, so an alert can be judged
            // afterwards against the hits that led up to it.
            plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                    "[REACH-DEBUG] %s -> %s dist=%.2f max=%.2f (config=%.2f scale=%.2f "
                            + "latency=+%.2f ping=%dms history=%b) streak=%d/%d",
                    attacker.getName(), victim.getType().name(), distance, maxReach,
                    configured, CheckMath.scale(attacker), latencyAllowance, ping, historyMode,
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
     * A mace smash computed from a fall the server did not see.
     *
     * <p>Smash damage grows with the attacker's fall distance, and vanilla builds that
     * distance from the client's on-ground flag: a client claiming to be airborne while
     * walking down stairs or hopping keeps adding to it. The server's own measurement
     * ({@link FallTracker}) only counts descent with nothing underneath, so a claimed distance
     * far above it is inflated. Only a measurement that is reliable since the last landing is
     * compared — a wind charge, knockback, bounce or teleport since then makes it unreliable —
     * and a recent spear lunge skips the check as well.
     */
    void checkMaceSmash(Player attacker, UUID id, long now) {
        if (!config.maceDetectionEnabled() || fallTracker == null) return;
        if (attacker.getInventory().getItemInMainHand().getType() != org.bukkit.Material.MACE) return;
        double claimed = attacker.getFallDistance();
        if (claimed <= SMASH_MIN_FALL) return;
        FallTracker.State fall = fallTracker.state(id);
        if (!fall.reliable()) return;
        if (lungeTracker != null) {
            LungeTracker.Lunge lunge = lungeTracker.lastLunge(id);
            if (lunge != null && now - lunge.timeMs() < MACE_LUNGE_GRACE_MS) return;
        }
        if (attacker.isGliding() || attacker.isInsideVehicle() || attacker.isInWater()) return;
        double excess = claimed - fall.distance();
        if (excess < Math.max(config.maceMinExcess(), fall.distance() * 0.5)) return;
        if (config.debugMode()) {
            plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                    "[MACE-DEBUG] %s claimed=%.2f measured=%.2f", attacker.getName(), claimed, fall.distance()));
        }
        int c = bumpStreak(maceStreak, id, now, 0L);
        if (c < config.maceViolations()) return;
        maceStreak.remove(id);
        handleViolation(attacker, "MACE", lang.format("alert.mace", claimed, fall.distance()), claimed);
    }

    /**
     * The reach limit with a weapon's {@code attack_range}: its maximum reach plus the hitbox
     * margin it adds to targets, with the same slack as the attribute. Never lowers the limit;
     * the component's minimum reach is irrelevant to an upper bound. Package-private for tests.
     */
    static double itemReachLimit(double configured, ItemCompat.AttackRange weapon) {
        if (weapon == null) return configured;
        return Math.max(configured, weapon.maxReach() + weapon.hitboxMargin() + Constants.REACH_ATTRIBUTE_SLACK);
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

    /**
     * Only teleports that can actually desync a client get grace (see
     * {@link PacketChecker#teleportNeedsGrace}). A pearl or chorus hop is repeatable at will;
     * granting grace — and a streak reset — for each would let pearl spam keep reach and
     * KillAura off for a whole fight. The short jump itself is covered by the position
     * history, which still holds where the target was before it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        // Teleports bypass PlayerMoveEvent; without this the pre-teleport spot would stay the
        // newest known position until the player next moved.
        recordPosition(event.getPlayer().getUniqueId(), event.getTo(), System.currentTimeMillis());
        if (!PacketChecker.teleportNeedsGrace(event.getCause(), event.getFrom(), event.getTo())) return;
        graceUntil.put(event.getPlayer().getUniqueId(),
                System.currentTimeMillis() + TELEPORT_GRACE_MS);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(org.bukkit.event.player.PlayerMoveEvent event) {
        if (!config.combatChecksEnabled() || !config.reachDetectionEnabled()) return;
        recordPosition(event.getPlayer().getUniqueId(), event.getTo(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleMove(org.bukkit.event.vehicle.VehicleMoveEvent event) {
        if (!config.combatChecksEnabled() || !config.reachDetectionEnabled()) return;
        // A vehicle's history is only read when a hit entity rides it, so empty vehicles
        // (minecart and boat farms) are not recorded.
        if (event.getVehicle().getPassengers().isEmpty()) return;
        recordPosition(event.getVehicle().getUniqueId(), event.getTo(), System.currentTimeMillis());
    }

    /** Append a position to an entity's history. Package-private so tests can backdate it. */
    void recordPosition(UUID id, org.bukkit.Location loc, long now) {
        if (loc == null || loc.getWorld() == null) return;
        Deque<Pos> dq = positions.computeIfAbsent(id, k -> new ConcurrentLinkedDeque<>());
        dq.addLast(new Pos(loc.getWorld().getUID(), loc.getX(), loc.getY(), loc.getZ(), now));
        // Keep one entry older than the history span: it is where the entity stood when the
        // span began.
        long cutoff = now - POSITION_HISTORY_MS;
        while (dq.size() >= 2) {
            java.util.Iterator<Pos> it = dq.iterator();
            it.next();
            if (it.next().time() >= cutoff) break;
            dq.pollFirst();
        }
        sweepPositions(now);
    }

    boolean hasPositionHistory(UUID id) {
        return positions.containsKey(id);
    }

    /** Drop histories nobody has updated for a while (vehicles have no quit event). */
    void sweepPositions(long now) {
        if (now - lastPositionSweep < POSITION_SWEEP_INTERVAL_MS) return;
        lastPositionSweep = now;
        positions.entrySet().removeIf(e -> {
            Pos last = e.getValue().peekLast();
            return last == null || last.time() < now - POSITION_HISTORY_MS;
        });
    }

    /** The outermost vehicle the entity rides, or the entity itself. */
    private static org.bukkit.entity.Entity rootVehicle(org.bukkit.entity.Entity e) {
        org.bukkit.entity.Entity cur = e;
        for (int i = 0; i < 8 && cur.getVehicle() != null; i++) cur = cur.getVehicle();
        return cur;
    }

    /**
     * Closest distance from the attacker's eyes to the victim's hitbox at any position the
     * victim (or the vehicle carrying it) held since {@code windowStart}, including now.
     */
    private double minDistanceOverLag(Player attacker, LivingEntity victim,
                                      org.bukkit.entity.Entity mover, long windowStart) {
        org.bukkit.util.BoundingBox box = victim.getBoundingBox();
        org.bukkit.Location eye = attacker.getEyeLocation();
        double best = distanceToBox(eye, box);
        Deque<Pos> dq = positions.get(mover.getUniqueId());
        if (dq == null) return best;
        org.bukkit.Location cur = mover.getLocation();
        if (cur.getWorld() == null) return best;
        UUID w = cur.getWorld().getUID();
        Pos atStart = null; // newest position before the window: where the window opened
        for (Pos p : dq) {
            if (!p.world().equals(w)) continue;
            if (p.time() < windowStart) {
                if (atStart == null || p.time() > atStart.time()) atStart = p;
                continue;
            }
            best = Math.min(best, distanceToBox(eye, shifted(box, p, cur)));
        }
        if (atStart != null) best = Math.min(best, distanceToBox(eye, shifted(box, atStart, cur)));
        return best;
    }

    private static org.bukkit.util.BoundingBox shifted(org.bukkit.util.BoundingBox box, Pos p,
                                                       org.bukkit.Location cur) {
        return box.clone().shift(p.x() - cur.getX(), p.y() - cur.getY(), p.z() - cur.getZ());
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        graceUntil.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + JOIN_GRACE_MS);
    }

    @EventHandler
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        positions.remove(event.getPlayer().getUniqueId()); // positions from before death are void
        graceUntil.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + JOIN_GRACE_MS);
    }

    @EventHandler
    public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent event) {
        graceUntil.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + JOIN_GRACE_MS);
    }

    /** Distance from the attacker's eyes to the nearest point of the victim's bounding box. */
    private double distanceToHitbox(Player attacker, LivingEntity victim) {
        return distanceToBox(attacker.getEyeLocation(), victim.getBoundingBox());
    }

    private static double distanceToBox(org.bukkit.Location eye, org.bukkit.util.BoundingBox box) {
        double dx = eye.getX() - Math.max(box.getMinX(), Math.min(eye.getX(), box.getMaxX()));
        double dy = eye.getY() - Math.max(box.getMinY(), Math.min(eye.getY(), box.getMaxY()));
        double dz = eye.getZ() - Math.max(box.getMinZ(), Math.min(eye.getZ(), box.getMaxZ()));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Smallest angle (degrees) between the look direction and any point of the box, 0 when
     * the look ray passes through it. Approximated by walking the look ray and taking, at each
     * step, the box point nearest to it — never below the true minimum, and within a fraction
     * of a degree of it. Package-private and free of server state so it can be tested directly.
     */
    static double angleToBox(Vector eye, Vector look, org.bukkit.util.BoundingBox box) {
        if (box.contains(eye)) return 0.0;
        if (look.lengthSquared() < 1.0e-9) return 0.0;
        Vector dir = look.clone().normalize();
        double reach = eye.distance(box.getCenter()) + box.getWidthX() + box.getHeight() + box.getWidthZ();
        double best = 180.0;
        for (double t = 0.0; t <= reach; t += 0.05) {
            double px = eye.getX() + dir.getX() * t;
            double py = eye.getY() + dir.getY() * t;
            double pz = eye.getZ() + dir.getZ() * t;
            Vector to = new Vector(
                    Math.max(box.getMinX(), Math.min(px, box.getMaxX())) - eye.getX(),
                    Math.max(box.getMinY(), Math.min(py, box.getMaxY())) - eye.getY(),
                    Math.max(box.getMinZ(), Math.min(pz, box.getMaxZ())) - eye.getZ());
            if (to.lengthSquared() < 1.0e-9) return 0.0;
            best = Math.min(best, Math.toDegrees(dir.angle(to)));
        }
        return best;
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
            database.logAsync(player.getUniqueId(), "anticheat_" + type.toLowerCase(), value,
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
        positions.remove(playerId);
        // The vehicle the player rode stops being updated too.
        sweepPositions(System.currentTimeMillis());
        lastHit.remove(playerId);
        recentTargets.remove(playerId);
        consecutiveCriticals.remove(playerId);
        consecutiveAutoBlock.remove(playerId);
        reachStreak.remove(playerId);
        killAuraAngleStreak.remove(playerId);
        killAuraMultiStreak.remove(playerId);
        maceStreak.remove(playerId);
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
