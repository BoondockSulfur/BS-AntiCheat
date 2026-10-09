package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.integration.GeyserHook;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.lang.LanguageManager;
import dev.boondock.bsanticheat.util.CheckMath;
import dev.boondock.bsanticheat.util.Constants;
import dev.boondock.bsanticheat.util.GameCompat;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects suspicious movement patterns that may indicate cheating.
 * Checks for various movement types: walking, sprinting, swimming, riding, etc.
 * WARNING: This is basic detection and may produce false positives.
 *
 * PHASE 1: every PlayerMoveEvent is checked (no sampling), so a cheat cannot hide in
 * skipped moves. Ground state is server-authoritative ({@link #supportDepth}) rather than
 * the client-sent flag, and the consecutive-violation counters are per-tick.
 */
public class MovementChecker implements Listener {

    private final Plugin plugin;
    private final PluginConfig config;
    private final DatabaseManager database;
    private final LanguageManager lang;
    private LuckPermsHook luckPerms;
    private GeyserHook geyser;
    private MovementAlertManager alertManager;
    private ViolationManager violationManager;
    private TransactionManager transactionManager;
    private PistonTracker pistons;
    private LungeTracker lungeTracker;
    // Server-measured fall distance, shared with the mace check in CombatChecker.
    private FallTracker fallTracker = new FallTracker();

    // Last position seen, and when. Every move event updates these.
    private final Map<UUID, Location> lastLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMoveTime = new ConcurrentHashMap<>();
    // Setback target: the last position reached by a sample that passed every check without
    // starting a streak, or one recorded while checks were legitimately off (exemptions,
    // grace windows). Kept apart from lastLocations, which follows every event: setting back
    // to that would only undo the last packet of whatever the violation was built from.
    private final Map<UUID, Location> lastLegitLocations = new ConcurrentHashMap<>();
    // Setbacks issued by this checker whose teleport event has not arrived yet. The teleport
    // handler recognises them by destination, so a setback does not grant itself teleport
    // immunity or wipe the streaks that caused it.
    private record PendingSetback(Location target, long at) {}
    private final Map<UUID, PendingSetback> pendingSetbacks = new ConcurrentHashMap<>();
    private static final long SETBACK_MATCH_MS = 5000;

    // The sample currently being built: where and when it started, and how many move events
    // have been folded into it (see onPlayerMove).
    private final Map<UUID, Location> sampleFrom = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sampleAt = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> sampleEvents = new ConcurrentHashMap<>();
    private static final long MIN_SAMPLE_MS = Math.round(Constants.MOVEMENT_MIN_TIME_DELTA * 1000.0);
    // Start and length of the current burst of back-to-back events (for TELEPORT).
    private final Map<UUID, Location> burstFrom = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> burstEvents = new ConcurrentHashMap<>();

    // Horizontal speed budget: [0] = remaining allowance in blocks, [1]/[2] = distance and
    // ticks since the budget was last full (for the alert text). See checkSpeedBudget.
    private final Map<UUID, double[]> speedBudget = new ConcurrentHashMap<>();
    // Capacity in ticks of allowed travel. Longer than MOVEMENT_MAX_GAP_MS, so the backlog of
    // any stall that is still judged at all fits into what accrued during it.
    private static final double SPEED_BUDGET_TICKS = 10.0;
    // How far into debt (in ticks of allowed travel) the budget may go before it flags.
    private static final double SPEED_DEBT_TICKS = 3.0;
    private static final int BUDGET_OK = 0;
    private static final int BUDGET_DEBT = 1;
    private static final int BUDGET_SETBACK = 2;

    // Free-fall window (see checkFreeFall).
    private static final class AirWindow {
        final double startY;
        final long startAt;
        final double v0;
        int events;

        AirWindow(double startY, long startAt, double v0) {
            this.startY = startY;
            this.startAt = startAt;
            this.v0 = v0;
        }
    }
    private final Map<UUID, AirWindow> airWindows = new ConcurrentHashMap<>();
    private static final double PLAYER_GRAVITY = 0.08;
    private static final double PLAYER_DRAG = 0.98;
    private static final double VANILLA_JUMP_VELOCITY = 0.42;
    // Terminal falling speed of a player (0.08 gravity, 0.98 drag), rounded up.
    private static final double TERMINAL_FALL_SPEED = 4.0;
    // Added to the jump velocity as the assumed launch speed, for sampling noise.
    private static final double LAUNCH_MARGIN = 0.1;
    // Airborne time before a stretch is judged, and the leeway on the expected fall.
    private static final double FREE_FALL_MIN_TICKS = 20.0;
    private static final double FREE_FALL_TOLERANCE = 1.0;
    private static final int GRAVITY_OK = 0;
    private static final int GRAVITY_SUSPECT = 1;
    private static final int GRAVITY_SETBACK = 2;

    // Whether the player was flying at their previous move, to notice flight ending without
    // an event (allowFlight revoked by /fly or a region flag).
    private final Map<UUID, Boolean> wasFlying = new ConcurrentHashMap<>();
    // Flight momentum after flying stops: a sprint-flying player carries ~1 b/t that drag
    // takes around half a second to bleed below the walking cap, and falls from wherever
    // they were.
    private static final long FLIGHT_END_GRACE_MS = 2000;
    // Flight that ends in mid-air (leaving creative/spectator, /fly off, a world forcing
    // survival on join) leaves the player in the air for as long as the client takes to
    // follow, and a client that missed the change keeps flying until it is resynced. Movement
    // is not judged until the player lands, at most this long. Only players who could fly a
    // moment ago get it, so it opens nothing for anyone else.
    static final long FLIGHT_END_MAX_AIR_MS = 30_000;
    private final Map<UUID, Long> flightEndedAirborne = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> consecutiveSpeedViolations = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> consecutiveFlyViolations = new ConcurrentHashMap<>();
    // Sustained-hover detection: consecutive airborne samples without falling
    private final Map<UUID, Integer> consecutiveHoverTicks = new ConcurrentHashMap<>();
    // GroundSpoof: consecutive samples claiming on-ground while high in the air
    private final Map<UUID, Integer> consecutiveGroundSpoof = new ConcurrentHashMap<>();
    // NoSlow: consecutive samples moving too fast while using an item
    private final Map<UUID, Integer> consecutiveNoSlow = new ConcurrentHashMap<>();
    // Jesus / Spider / Step
    private final Map<UUID, Integer> consecutiveJesus = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> consecutiveSpider = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> consecutiveStep = new ConcurrentHashMap<>();
    // Elytra/Riptide: consecutive over-speed samples
    private final Map<UUID, Integer> consecutiveElytra = new ConcurrentHashMap<>();
    // Start of the current elytra/riptide speed sample. Speed is measured across a fixed
    // time window instead of per move event: a move event is not reliably one tick, and
    // treating it as one turns every packet gap into phantom speed (see checkElytraSpeed).
    private final Map<UUID, Location> elytraSampleFrom = new ConcurrentHashMap<>();
    private final Map<UUID, Long> elytraSampleAt = new ConcurrentHashMap<>();
    private static final long ELYTRA_SAMPLE_WINDOW_MS = 250;
    // Last moment the server reported the player riptiding. A riptide launch while gliding
    // adds its impulse on top of the glide, so for as long as that impulse lasts
    // (RIPTIDE_GRACE_MS) the elytra ceiling is raised by the riptide ceiling.
    private final Map<UUID, Long> lastRiptide = new ConcurrentHashMap<>();

    // Knockback immunity tracking
    private final Map<UUID, Long> recentKnockback = new ConcurrentHashMap<>();
    private static final long KNOCKBACK_IMMUNITY_MS = 2000; // 2 seconds

    // Teleport immunity tracking — prevents false positives after legitimate teleports
    private final Map<UUID, Long> recentTeleport = new ConcurrentHashMap<>();
    private static final long TELEPORT_IMMUNITY_MS = 1000; // 1 second grace period after teleport

    // Join/respawn/world-change grace — spawn teleports and chunk loading cause big deltas
    private final Map<UUID, Long> recentJoin = new ConcurrentHashMap<>();
    private static final long JOIN_GRACE_MS = 3000; // 3 second grace after join/respawn/world change

    // Elytra/Riptide landing grace — after gliding stops the player keeps high horizontal
    // momentum for a moment, which the ground speed check would misread as a Speed violation.
    // Stores the instant the grace EXPIRES, because riptide needs a longer one than elytra:
    // a trident launch is a single impulse that then bleeds off against drag, and vanilla
    // clears isRiptiding() after the ~0.5s animation while the player is still travelling at
    // several blocks per tick; that decaying tail would otherwise read as SPEED.
    // Also used for dismounts: leaving a horse or boat at speed hands the player its
    // momentum, with no key input of their own behind it.
    private final Map<UUID, Long> momentumGraceUntil = new ConcurrentHashMap<>();
    private static final long GLIDE_GRACE_MS = 1500;   // elytra: gliding stops, momentum drops fast
    private static final long RIPTIDE_GRACE_MS = 3000; // riptide: impulse decays over ~2-3s
    private static final long DISMOUNT_GRACE_MS = 1500;

    // Pillar-up grace — a player towering upwards places a block directly beneath their own
    // feet and lands on it immediately. Two things follow: the fresh block catches them
    // before gravity shows, so the fall the hover check waits for never happens, and at the
    // apex of each jump the previous solid block has already dropped out of the support scan.
    // The result reads as "airborne and not falling", which is precisely the hover signature.
    private final Map<UUID, Long> recentPillar = new ConcurrentHashMap<>();
    private static final long PILLAR_GRACE_MS = 1500;
    // How far below the feet a placed block still counts as the player's own new footing.
    private static final double PILLAR_MAX_DROP = 3.0;
    // …and how far it may sit horizontally — beyond one block sideways it is scaffolding out,
    // not towering up.
    private static final double PILLAR_MAX_REACH = 1.5;

    // Slime-bounce grace — a high bounce rises for far longer than the 2-block scan below
    // the player can see, so the launch moment is remembered instead.
    private final Map<UUID, Long> recentSlime = new ConcurrentHashMap<>();
    private static final long SLIME_GRACE_MS = 3000;
    // Bed / shelf-mushroom bounce grace: like the slime grace, but only after an observed
    // bounce and only for the vertical checks — these blocks do not throw a player sideways.
    private final Map<UUID, Long> recentBounce = new ConcurrentHashMap<>();
    // Last sample that descended faster than BOUNCE_MIN_IMPACT, for recognising a bounce.
    private final Map<UUID, Long> lastFastDescent = new ConcurrentHashMap<>();
    // Descent per tick a landing needs before its rebound can exceed an ordinary jump: beds
    // return 75% of the impact speed, and a jump starts at 0.42. Also above the landing speed
    // of an ordinary jump (~0.45 in the last tick, less averaged over a sample).
    static final double BOUNCE_MIN_IMPACT = 0.5;
    // How soon after that descent the rise must follow to count as its rebound.
    static final long BOUNCE_REVERSAL_MS = 300L;
    // Deepest scan below the feet for a potent-sulfur geyser: its push reaches up to five
    // times the height of a water column that is at most four blocks tall.
    private static final int GEYSER_SCAN_DEPTH = 24;

    // Lunge credit for the speed budget: [0] = blocks remaining, [1] = expiry (ms),
    // [2] = time of the stab it was granted for, so each stab is credited once, [3] = the
    // latest stab seen in the same burst.
    private final Map<UUID, double[]> lungeCredit = new ConcurrentHashMap<>();
    private static final long LUNGE_CREDIT_MS = 3000;

    // NoFall: a landing waiting for its fall damage event, and when fall damage last arrived.
    private record PendingLanding(double distance, long at, Location where) {}
    private final Map<UUID, PendingLanding> pendingLandings = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFallDamage = new ConcurrentHashMap<>();
    // Suspicious falls within the streak window: [0] = count, [1] = time of the last one.
    private final Map<UUID, long[]> noFallStreak = new ConcurrentHashMap<>();
    private static final long NOFALL_STREAK_WINDOW_MS = 60_000;
    // Mid-air samples whose vanilla fall distance lags behind the measured one.
    private final Map<UUID, Integer> noFallSpoofSamples = new ConcurrentHashMap<>();
    // Falls already counted as suspicious, so one fall never counts twice.
    private final java.util.Set<UUID> noFallCounted = ConcurrentHashMap.newKeySet();

    // Sprint rules: since when a sprint vanilla would have ended has been kept up, and the
    // omni-sprint streak.
    private final Map<UUID, Long> sprintHungerSince = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sprintBlindSince = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> consecutiveOmniSprint = new ConcurrentHashMap<>();
    // First protocol whose client ends a running sprint on low food or Blindness (1.21.2).
    private static final int PROTOCOL_SPRINT_STOP_RULES = 768;

    // Ice-momentum memory: [0]=timestamp(ms), [1]=ice speed multiplier. Sprint-jumping on
    // ice roads has no ice directly below mid-jump while the momentum persists.
    private final Map<UUID, double[]> recentIce = new ConcurrentHashMap<>();
    private static final long ICE_MOMENTUM_GRACE_MS = 2500;

    // What counts as "hanging in the air". One tick of vanilla gravity is 0.08 blocks, so a
    // player whose vertical movement stays inside this band is neither falling nor climbing —
    // which is what hovering means. A rise is not hovering and is left to the ascent check.
    private static final double HOVER_STILL_BAND = 0.08;
    // Vertical speed at the start of the current hover run, so a held speed can be told from
    // one that is still decaying (see the flag site).
    private final Map<UUID, Double> hoverStartDy = new ConcurrentHashMap<>();

    // Sustained-ascent state (opt-in check): last vertical speed and how many samples in a row
    // rose without the decay gravity imposes.
    private final Map<UUID, Double> lastAscentDy = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> consecutiveAscent = new ConcurrentHashMap<>();

    // Ground-scan depths: nothing supportive within 2 blocks = clearly airborne (hover),
    // within 3 = high above ground (GroundSpoof). One scan to the deeper limit serves both.
    private static final int AIRBORNE_SCAN_DEPTH = 2;
    private static final int GROUNDSPOOF_SCAN_DEPTH = 3;

    public MovementChecker(Plugin plugin, PluginConfig config, DatabaseManager database, LanguageManager lang) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.lang = lang;
    }

    /** Localized display name for a movement type (used in speed alerts). */
    private String typeName(MovementType type) {
        return lang.get("movetype." + type.name().toLowerCase());
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

    public void setPistonTracker(PistonTracker pistons) {
        this.pistons = pistons;
    }

    /** Spear lunges recorded by the packet layer; without it no lunge allowance is granted. */
    public void setLungeTracker(LungeTracker lungeTracker) {
        this.lungeTracker = lungeTracker;
    }

    public void setFallTracker(FallTracker fallTracker) {
        if (fallTracker != null) this.fallTracker = fallTracker;
    }

    public FallTracker fallTracker() {
        return fallTracker;
    }

    /**
     * True when a recent sideways piston push nearby can account for this horizontal speed:
     * a push adds at most {@link PistonTracker#MAX_PUSH_PER_TICK} on top of what the player
     * may move by themselves.
     */
    private boolean pistonExplainsHorizontal(Location loc, double perTick, double maxSpeed) {
        return pistons != null && perTick <= maxSpeed + PistonTracker.MAX_PUSH_PER_TICK
                && pistons.horizontalPushNear(loc);
    }

    /** Vertical counterpart of {@link #pistonExplainsHorizontal}. */
    private boolean pistonExplainsVertical(Location loc, double perTick, double maxVertical) {
        return pistons != null && perTick <= maxVertical + PistonTracker.MAX_PUSH_PER_TICK
                && pistons.verticalPushNear(loc);
    }

    /**
     * True when a piston nearby could be holding or lifting the player: a vertical push, or
     * a push moving the block under their feet. A sideways clock beside them does neither.
     */
    private boolean pistonHoldsUp(Location loc) {
        return pistons != null && pistons.verticalPushNear(loc);
    }

    /** Round-trip latency (ms) for lag compensation (see {@link dev.boondock.bsanticheat.util.CheckMath}). */
    private int effectivePing(Player player) {
        return CheckMath.effectivePing(transactionManager, player);
    }

    /**
     * Determines the current movement type of a player.
     * Priority order matters - checks most specific states first.
     */
    private MovementType getMovementType(Player player) {
        // PRIORITY 1: Check if in vehicle (highest priority)
        Entity vehicle = player.getVehicle();
        if (vehicle != null) {
            // Rideable animals
            // Zombie and skeleton horses are AbstractHorse, not Horse, but ride like one.
            if (vehicle instanceof Horse || vehicle instanceof ZombieHorse || vehicle instanceof SkeletonHorse) {
                return MovementType.RIDING_HORSE;
            }
            if (vehicle instanceof Donkey || vehicle instanceof Mule) {
                return MovementType.RIDING_DONKEY;
            }
            if (vehicle instanceof Llama) {
                return MovementType.RIDING_LLAMA;
            }
            // Covers the camel husk too, which the API models as a Camel.
            if (vehicle instanceof Camel) {
                return MovementType.RIDING_CAMEL;
            }
            if (vehicle instanceof Pig) {
                return MovementType.RIDING_PIG;
            }
            if (vehicle instanceof Strider) {
                return MovementType.RIDING_STRIDER;
            }
            // Vehicles
            if (vehicle instanceof Boat) {
                return MovementType.BOAT;
            }
            if (vehicle instanceof Minecart) {
                return MovementType.MINECART;
            }
            // Everything else: happy ghast (flying), nautilus, and seats such as the cushion,
            // which has no collision and moves the player only with itself. None of them is
            // on-foot movement; ridden mounts are judged by VehicleChecker.
            return MovementType.OTHER_VEHICLE;
        }

        // PRIORITY 2: Check special flying states
        if (player.isGliding()) {
            return MovementType.ELYTRA;
        }
        if (player.isRiptiding()) {
            return MovementType.RIPTIDE;
        }
        if (player.isFlying()) {
            return MovementType.CREATIVE_FLY;
        }

        // PRIORITY 3: Check water movement.
        //
        // Only the swim POSE maps to SWIMMING. Being in water without it (wading upright,
        // surfacing, riding Dolphin's Grace) deliberately stays WALKING/SPRINTING: the type
        // gates far more than the speed cap — Step, Spider, GroundSpoof and the hover checks
        // all require an on-foot type, and routing every in-water player to SWIMMING would
        // switch those off for anyone standing in a puddle. The water speed bonuses are
        // applied to the cap instead, in getMaxSpeed(), so a wading player is judged by water
        // physics while every other check stays armed.
        if (player.isSwimming()) {
            return MovementType.SWIMMING;
        }

        // PRIORITY 4: Check climbing. isClimbing() uses the actual game logic (hitbox
        // overlap, trapdoor-above-ladder…); the material check is a fallback for the
        // moment a player exits the top of a ladder while still rising.
        if (player.isClimbing() || isClimbableMaterial(player.getLocation().getBlock().getType())) {
            return MovementType.CLIMBING;
        }

        // PRIORITY 5: Check ground movement states
        if (player.isSprinting()) {
            return MovementType.SPRINTING;
        }
        // Sneaking only slows a player who is actually standing on something. isSneaking()
        // is a pose/input flag that stays set in mid-air, where vanilla applies no sneak
        // slowdown at all — a player who jumps or walks off a ledge while crouched keeps
        // full walking momentum and would be judged against the 0.3x sneak cap.
        if (player.isSneaking() && player.isOnGround()) {
            return MovementType.SNEAKING;
        }

        // DEFAULT: Normal walking
        return MovementType.WALKING;
    }

    /**
     * Gets the maximum allowed speed for a movement type.
     * Includes lag compensation based on player ping.
     */
    private double getMaxSpeed(MovementType type, Player player) {
        // Base speeds from config
        double baseWalkSpeed = config.speedThresholdWalk();
        double baseSprintSpeed = config.speedThresholdSprint();
        double baseFlySpeed = config.speedThresholdFly();

        // How fast this player may legitimately move. Read from the real movement_speed
        // attribute instead of hand-rolling a Speed potion multiplier: the attribute already
        // contains the potion (Speed II still yields 1.4x, unchanged) but ALSO every other
        // legitimate source — custom gear and item plugins that add attribute modifiers,
        // datapacks, and EssentialsX /speed, which bypasses attributes entirely.
        double speedMultiplier = CheckMath.speedAttributeRatio(player);

        // Apply soul speed enchantment multiplier on soul sand/soil
        Material below = player.getLocation().getBlock().getRelative(0, -1, 0).getType();
        if (below == Material.SOUL_SAND || below == Material.SOUL_SOIL) {
            speedMultiplier += Constants.SOUL_SPEED_MULTIPLIER;
        }

        // Ice is slippery — players legitimately reach much higher speeds on it. The
        // multiplier must survive sprint-jumping along an ice road: mid-jump the block
        // directly below is air, but the ice momentum persists. So scan a few blocks
        // down AND remember the last ice contact for a short grace period.
        UUID pid = player.getUniqueId();
        long nowMs = System.currentTimeMillis();
        double iceMultiplier = iceMultiplierBelow(player.getLocation());
        if (iceMultiplier > 1.0) {
            recentIce.put(pid, new double[]{nowMs, iceMultiplier});
        } else {
            double[] lastIce = recentIce.get(pid);
            if (lastIce != null && nowMs - (long) lastIce[0] < ICE_MOMENTUM_GRACE_MS) {
                iceMultiplier = lastIce[1];
            }
        }
        speedMultiplier *= iceMultiplier;

        // LAG COMPENSATION: sqrt ping scaling shared with all other checks
        speedMultiplier *= CheckMath.pingSlack(effectivePing(player));

        double cap = switch (type) {
            case WALKING -> baseWalkSpeed * speedMultiplier;
            case SPRINTING -> baseSprintSpeed * speedMultiplier;
            case SNEAKING -> {
                // The sneaking_speed attribute is what Swift Sneak actually modifies, so it
                // covers the enchantment and any item/plugin that grants faster crouching.
                // The enchantment is still read as a floor for servers where the attribute
                // is unavailable.
                double sneakMult = CheckMath.sneakingSpeedFactor(player);
                var leggings = player.getInventory().getLeggings();
                if (leggings != null) {
                    int lvl = leggings.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SWIFT_SNEAK);
                    if (lvl > 0) sneakMult = Math.max(sneakMult, Math.min(1.0,
                            Constants.SNEAKING_SPEED_MULTIPLIER + Constants.SWIFT_SNEAK_MULTIPLIER_PER_LEVEL * lvl));
                }
                yield baseWalkSpeed * sneakMult * speedMultiplier;
            }
            case SWIMMING -> swimCap(player, baseWalkSpeed, speedMultiplier);
            case CLIMBING -> baseWalkSpeed * Constants.CLIMBING_SPEED_MULTIPLIER;
            case RIDING_HORSE -> Constants.HORSE_MAX_SPEED;
            case RIDING_DONKEY -> Constants.DONKEY_MAX_SPEED;
            case RIDING_LLAMA -> Constants.LLAMA_MAX_SPEED;
            case RIDING_CAMEL -> Constants.CAMEL_MAX_SPEED;
            case RIDING_PIG -> Constants.PIG_MAX_SPEED;
            case RIDING_STRIDER -> Constants.STRIDER_MAX_SPEED;
            case BOAT -> Constants.BOAT_MAX_SPEED;
            case MINECART -> Constants.MINECART_MAX_SPEED;
            // Not reached from onPlayerMove (gliding/riptiding is routed to
            // checkElytraSpeed), but kept in sync with it so the ceiling has one source.
            case ELYTRA -> config.elytraMaxSpeed();
            case RIPTIDE -> config.riptideMaxSpeed();
            case CREATIVE_FLY -> baseFlySpeed * Constants.CREATIVE_FLY_MULTIPLIER;
            case OTHER_VEHICLE -> Constants.OTHER_VEHICLE_MAX_SPEED;
        };

        // Water physics apply to anyone IN water, not only to the swim pose. A player wading
        // upright, surfacing, or carried by Dolphin's Grace keeps their on-foot movement type
        // (so Step/Spider/GroundSpoof/hover stay armed) but must be judged against the water
        // cap — otherwise Depth Strider and Dolphin's Grace read as a SPEED violation against
        // the dry-land cap.
        //
        // A floor, never a ceiling: the swim cap is 0.8x walking, so applying it as a
        // replacement would flag someone strolling through a shallow pond.
        if (type != MovementType.SWIMMING && isOnFoot(type) && player.isInWater()) {
            cap = Math.max(cap, swimCap(player, baseWalkSpeed, speedMultiplier));
        }
        return cap;
    }

    /** True for the movement types a player performs on their own feet. */
    private static boolean isOnFoot(MovementType type) {
        return type == MovementType.WALKING || type == MovementType.SPRINTING
                || type == MovementType.SNEAKING;
    }

    /**
     * Speed cap for movement through water, including everything that legitimately lifts it.
     *
     * <p>Depth Strider removes most of the water drag — without it a player with Depth
     * Strider III swims at roughly walking speed and trips the cap. water_movement_efficiency
     * is the attribute vanilla maps the enchantment onto, so reading it also covers items and
     * plugins granting the same effect without the enchantment; the enchantment lookup stays
     * as a floor for servers where the attribute is unavailable.
     */
    private double swimCap(Player player, double baseWalkSpeed, double speedMultiplier) {
        double swim = baseWalkSpeed * Constants.SWIMMING_SPEED_MULTIPLIER * speedMultiplier;
        double waterMult = CheckMath.waterEfficiencyMultiplier(player);
        var boots = player.getInventory().getBoots();
        if (boots != null) {
            int ds = boots.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.DEPTH_STRIDER);
            if (ds > 0) waterMult = Math.max(waterMult,
                    1.0 + Constants.DEPTH_STRIDER_MULTIPLIER_PER_LEVEL * ds);
        }
        swim *= waterMult;
        // Dolphin's Grace drastically increases swim speed — avoid false positives
        if (player.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE)) {
            swim *= Constants.DOLPHINS_GRACE_MULTIPLIER;
        }
        // Never below the plain walking cap — see the floor note at the call site.
        return Math.max(swim, baseWalkSpeed * speedMultiplier);
    }

    /**
     * Track knockback/damage for immunity detection.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        UUID playerId = player.getUniqueId();

        // Track explosion knockback
        if (event.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_EXPLOSION ||
            event.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
            noteKnockback(playerId, System.currentTimeMillis());
        }

        // Track entity attacks (knockback)
        if (event.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK ||
            event.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            noteKnockback(playerId, System.currentTimeMillis());
        }
    }

    /**
     * Knockback of any cause, including explosions that deal no damage. A wind charge
     * launching its own thrower is such an explosion: it pushes the player without a damage
     * event and, depending on the server version, without a velocity event either.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityKnockback(io.papermc.paper.event.entity.EntityKnockbackEvent event) {
        if (event.getEntity() instanceof Player player) {
            noteKnockback(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    /** Knockback grace, and the fall measurement no longer describes the player's descent. */
    private void noteKnockback(UUID playerId, long now) {
        recentKnockback.put(playerId, now);
        fallTracker.disturb(playerId, now);
    }

    /**
     * Fall damage, also when another plugin cancelled it: for NoFall the question is only
     * whether the server computed any, not whether it was dealt.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onFallDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (event.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL) return;
        if (!(event.getEntity() instanceof Player player)) return;
        UUID id = player.getUniqueId();
        lastFallDamage.put(id, System.currentTimeMillis());
        pendingLandings.remove(id);
    }

    /**
     * A mace smash resets the attacker's fall distance, so the landing after it deals no
     * damage. Runs after CombatChecker (HIGH), which compares the distance first.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMaceHit(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (attacker.getInventory().getItemInMainHand().getType() != Material.MACE) return;
        fallTracker.disturb(attacker.getUniqueId(), System.currentTimeMillis());
    }

    /**
     * Any server-applied velocity (projectile knockback, wind charges, explosions,
     * fishing-rod pulls, jump pads from other plugins…) reaches the client as a velocity
     * packet and legitimately breaks the movement model for a moment. The damage-event
     * handler above misses every cause that deals no damage, so the velocity itself
     * grants the same immunity.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerVelocity(org.bukkit.event.player.PlayerVelocityEvent event) {
        noteKnockback(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    /**
     * Track legitimate teleports to prevent false-positive movement violations.
     * PlayerTeleportEvent extends PlayerMoveEvent, so without this handler
     * teleports would be detected as "teleport-like movement" violations.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        Location to = event.getTo();

        // Our own setback. It moves the player, so the position bookkeeping follows it, but it
        // is not a legitimate teleport: granting immunity and wiping the streaks here would
        // hand the player a free second after every setback, which a cheat simply waits out.
        PendingSetback pending = pendingSetbacks.get(playerId);
        if (pending != null && to != null) {
            if (System.currentTimeMillis() - pending.at() < SETBACK_MATCH_MS
                    && sameSpot(pending.target(), to)) {
                pendingSetbacks.remove(playerId);
                rebaseline(playerId, to, true, false);
                elytraSampleFrom.remove(playerId);
                elytraSampleAt.remove(playerId);
                return;
            }
            if (System.currentTimeMillis() - pending.at() >= SETBACK_MATCH_MS) {
                pendingSetbacks.remove(playerId); // cancelled by another plugin; never arrived
            }
        }

        // Mark this player as recently teleported
        recentTeleport.put(playerId, System.currentTimeMillis());
        pendingLandings.remove(playerId);

        // Reset location tracking to the teleport destination so the next
        // movement check uses the correct baseline position
        if (to != null) {
            rebaseline(playerId, to);
        }

        // Reset violation counters — teleport is legitimate, not a violation streak.
        // ALL movement-derived streaks, not just speed and fly: every one of them is built
        // from deltas against a position the teleport just invalidated, so carrying a
        // half-built count across it means flagging on evidence from a different place.
        consecutiveSpeedViolations.remove(playerId);
        consecutiveFlyViolations.remove(playerId);
        consecutiveHoverTicks.remove(playerId);
        consecutiveGroundSpoof.remove(playerId);
        consecutiveNoSlow.remove(playerId);
        consecutiveJesus.remove(playerId);
        consecutiveSpider.remove(playerId);
        consecutiveStep.remove(playerId);
        consecutiveElytra.remove(playerId);
        lastAscentDy.remove(playerId);
        consecutiveAscent.remove(playerId);
        // The elytra window measures distance between two sampled points; a teleport between
        // them is pure phantom distance.
        elytraSampleFrom.remove(playerId);
        elytraSampleAt.remove(playerId);

        if (config.debugMode()) {
            plugin.getLogger().fine("[AC] " + player.getName() + " teleported (" +
                    event.getCause().name() + "), granting immunity");
        }
    }

    /**
     * Remember when a player placed a block they could be standing on, so the vertical
     * checks can tell towering up ("pillaring") apart from hovering.
     *
     * <p>Only blocks placed under the player's own feet count — up to
     * {@link #PILLAR_MAX_DROP} below and {@link #PILLAR_MAX_REACH} sideways. That keeps the
     * exemption from becoming a free pass for flight: vanilla only allows placing against an
     * existing block, so a player who keeps producing footing beneath themselves is by
     * definition standing on a column they built, not floating. Bridging out sideways stays
     * outside the window and remains the SCAFFOLD check's business.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        Player player = event.getPlayer();
        org.bukkit.block.Block placed = event.getBlockPlaced();
        Location loc = player.getLocation();
        if (!placed.getWorld().equals(loc.getWorld())) return;

        // Block top surface relative to the feet: at or below them, within reach.
        double drop = loc.getY() - (placed.getY() + 1);
        if (drop < -0.5 || drop > PILLAR_MAX_DROP) return;
        if (Math.abs(placed.getX() + 0.5 - loc.getX()) > PILLAR_MAX_REACH) return;
        if (Math.abs(placed.getZ() + 0.5 - loc.getZ()) > PILLAR_MAX_REACH) return;

        recentPillar.put(player.getUniqueId(), System.currentTimeMillis());
    }

    /**
     * Leaving a vehicle at speed hands its momentum to the player, who then covers ground
     * for a moment without pressing anything — a horse at full gallop or a boat off blue ice
     * carries far more than the walking cap allows.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDismount(org.bukkit.event.entity.EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        extendMomentumGrace(player.getUniqueId(), System.currentTimeMillis() + DISMOUNT_GRACE_MS);
    }

    /**
     * Flight switched off by the player (double-tap) or taken away with the game mode. The
     * momentum of flying outlasts the flight; see {@link #FLIGHT_END_GRACE_MS}.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToggleFlight(org.bukkit.event.player.PlayerToggleFlightEvent event) {
        if (!event.isFlying()) {
            grantFlightGrace(event.getPlayer().getUniqueId());
            markFlightEnded(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(org.bukkit.event.player.PlayerGameModeChangeEvent event) {
        grantFlightGrace(event.getPlayer().getUniqueId());
        if (endsFlight(event.getPlayer().getGameMode(), event.getNewGameMode())) {
            markFlightEnded(event.getPlayer());
        }
    }

    /** True when the change takes away the flight creative and spectator always have. */
    static boolean endsFlight(GameMode from, GameMode to) {
        boolean couldFly = from == GameMode.CREATIVE || from == GameMode.SPECTATOR;
        boolean canFly = to == GameMode.CREATIVE || to == GameMode.SPECTATOR;
        return couldFly && !canFly;
    }

    private void markFlightEnded(Player player) {
        if (player.isOnGround()) return;
        flightEndedAirborne.put(player.getUniqueId(), System.currentTimeMillis());
    }

    /** Whether the grace after flight ended in mid-air still holds, see {@link #FLIGHT_END_MAX_AIR_MS}. */
    static boolean flightEndGraceHolds(long endedAt, boolean onGround, long now) {
        return !onGround && now - endedAt < FLIGHT_END_MAX_AIR_MS;
    }

    /** Notice flight ending when no event announced it (allowFlight revoked mid-flight). */
    private void trackFlightState(Player player, UUID playerId) {
        boolean flying = player.isFlying();
        Boolean was = wasFlying.put(playerId, flying);
        if (Boolean.TRUE.equals(was) && !flying) {
            grantFlightGrace(playerId);
            markFlightEnded(player);
        }
    }

    private void grantFlightGrace(UUID playerId) {
        extendMomentumGrace(playerId, System.currentTimeMillis() + FLIGHT_END_GRACE_MS);
    }

    /** Extend the momentum grace to {@code until}, never shortening one already running. */
    private void extendMomentumGrace(UUID playerId, long until) {
        momentumGraceUntil.merge(playerId, until, Math::max);
    }

    private static boolean sameSpot(Location a, Location b) {
        return a.getWorld() != null && a.getWorld().equals(b.getWorld())
                && a.distanceSquared(b) < 1.0e-4;
    }

    @EventHandler
    public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        recentJoin.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onPlayerRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        recentJoin.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent event) {
        recentJoin.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Skip teleport events — handled by onPlayerTeleport
        if (event instanceof PlayerTeleportEvent) {
            return;
        }

        // Skip if movement checks are disabled
        if (!config.movementChecksEnabled()) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // Only check actual position changes (not just head rotation)
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null) return;
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;

        // Flight can end without any event — /fly off or a WorldGuard flag revoking
        // allowFlight drops the player out of the air — so the flying state is followed here,
        // ahead of every exemption, to catch the transition wherever it happens.
        trackFlightState(player, playerId);

        // PHASE 1: no sampling — every position change is checked. PlayerMoveEvent already
        // fires per movement packet the server accepts, so this gives per-tick coverage and
        // no move goes unjudged.
        // The per-move work is bounded (arithmetic + a few block lookups), so full coverage
        // is cheap even with many players.

        // Skip checks for exempt players.
        //
        // Every one of these returns still records the position. The bookkeeping is not
        // optional just because no check ran: the last known position is what a setback
        // teleports to, and leaving it untouched while an exempt player moves means a
        // creative-mode flight (or a bypass period) freezes the baseline where it started.
        // The first violation after they return to survival then teleports them back there,
        // which can be thousands of blocks and minutes ago.
        if (isPlayerWhitelisted(player)) {
            if (config.debugMode()) {
                plugin.getLogger().fine("[AC] " + player.getName() + " is whitelisted, skipping");
            }
            rebaseline(playerId, to);
            return;
        }

        // Check OPs bypass
        if (player.isOp() && config.opsBypass()) {
            rebaseline(playerId, to);
            return;
        }

        // Defaults to false, so OPs never get it implicitly — an explicit grant is meant.
        if (player.hasPermission("bsanticheat.bypass")) {
            rebaseline(playerId, to);
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            rebaseline(playerId, to);
            return;
        }

        // Determine movement type
        MovementType moveType = getMovementType(player);
        long now = System.currentTimeMillis();

        // Elytra/Riptide get their own speed-ceiling check — vanilla physics allow far
        // higher speeds than ground movement, so the ground checks below don't apply.
        if (moveType == MovementType.ELYTRA || moveType == MovementType.RIPTIDE) {
            // Gliding takes precedence in getMovementType, so a riptide during a glide only
            // shows here.
            boolean riptiding = player.isRiptiding();
            if (riptiding) lastRiptide.put(playerId, now);
            boolean setBack = false;
            if (config.elytraDetectionEnabled() && !ServerLoad.isLagging(config, player)) {
                setBack = checkElytraSpeed(player, playerId, moveType, from, to);
            }
            // Landing grace, measured from now — riptide's impulse outlives its animation.
            long graceMs = riptiding || moveType == MovementType.RIPTIDE ? RIPTIDE_GRACE_MS : GLIDE_GRACE_MS;
            extendMomentumGrace(playerId, now + graceMs);
            // After a setback the teleport handler owns the baseline; the illegal position
            // must not overwrite it. A glide with an over-speed streak running is not a
            // setback target either.
            if (!setBack) {
                boolean clean = consecutiveElytra.getOrDefault(playerId, 0) == 0;
                rebaseline(playerId, to, clean, true);
            }
            return;
        }

        // Vehicles are handled by VehicleChecker (VehicleMoveEvent — PlayerMoveEvent
        // does not fire while riding). Creative fly is server-granted (allowFlight),
        // so it is trusted. On-foot states (walking/sprinting/sneaking/swimming/
        // climbing) ARE checked, each with its own speed multiplier in getMaxSpeed().
        if (moveType == MovementType.CREATIVE_FLY || moveType == MovementType.MINECART ||
            moveType == MovementType.BOAT || moveType.name().startsWith("RIDING_") ||
            moveType == MovementType.OTHER_VEHICLE) {
            rebaseline(playerId, to);
            return;
        }

        // Skip during server lag — reset the baseline so the catch-up move afterwards
        // isn't misread as a speed/teleport violation.
        if (ServerLoad.isLagging(config, player)) {
            rebaseline(playerId, to);
            return;
        }

        Location lastLoc = lastLocations.get(playerId);
        Long lastTime = lastMoveTime.get(playerId);
        Location sampleStart = sampleFrom.get(playerId);
        Long sampleStartAt = sampleAt.get(playerId);

        // A move is only judged when there is a usable baseline, no packet gap sits in
        // between, and no grace window is active. Otherwise the position is recorded as the
        // new baseline and nothing is judged.
        //
        // The gap condition is the movement-side counterpart of the Timer check's: silence
        // this long is a stalled connection, and what follows is the client flushing its
        // backlog. Judging that catch-up means judging several ticks of travel as if they
        // were one. The per-tick scaling below handles the ordinary case; this rules out the
        // pathological one, where the delta is large enough for the TELEPORT threshold that
        // no scaling protects.
        boolean armed = lastLoc != null && lastTime != null
                && sampleStart != null && sampleStartAt != null
                && from.getWorld().equals(to.getWorld())
                && to.getWorld().equals(sampleStart.getWorld())
                && (now - lastTime) <= Constants.MOVEMENT_MAX_GAP_MS
                && !hasMovementImmunity(player, playerId, now);
        if (!armed) {
            rebaseline(playerId, to);
            return;
        }

        // Fall measurement runs on every event, including the ones folded into a sample below:
        // the descent has to be summed packet by packet to match what vanilla counts.
        // Geyser lookup for this event, shared by the fall measurement and the sample checks.
        GeyserProbe geyserProbe = new GeyserProbe(to, to.getY() > from.getY());
        trackFall(player, playerId, from, to, now, geyserProbe);

        // Moves are judged as SAMPLES: the displacement from the start of the sample to this
        // event. Events arriving within MOVEMENT_MIN_TIME_DELTA of the sample start are too
        // close together to derive a rate from, so they are folded into the sample instead
        // of being dropped: dropping them while advancing the baseline would let a second
        // position packet sent right behind the first pass unjudged.
        int events = sampleEvents.getOrDefault(playerId, 0) + 1;
        long elapsedMs = now - sampleStartAt;
        boolean setBack = false;
        boolean suspect = false;

        // Bursts: runs of events each arriving within MOVEMENT_MIN_TIME_DELTA of the one
        // before. A burst can straddle a sample boundary, so it is tracked on its own.
        if ((now - lastTime) >= MIN_SAMPLE_MS || !burstFrom.containsKey(playerId)) {
            burstFrom.put(playerId, from.clone());
            burstEvents.put(playerId, 1);
        } else {
            burstEvents.merge(playerId, 1, Integer::sum);
        }

        // TELEPORT runs on every event, including the ones folded into a sample. It asks how
        // far the player jumped: this event's own step, and the distance covered since the
        // current burst began, less one tick of legitimate travel for every other event in
        // it — which is what a bunch of legitimate packets delivered together can span.
        // Splitting one jump across packets sent back to back therefore gains nothing.
        if (config.teleportDetectionEnabled()) {
            double jumped = from.distance(to);
            Location burstStart = burstFrom.get(playerId);
            int inBurst = burstEvents.getOrDefault(playerId, 1);
            if (inBurst > 1 && burstStart != null && to.getWorld().equals(burstStart.getWorld())) {
                double sinceBurst = burstStart.distance(to);
                if (sinceBurst > config.teleportThreshold()) {
                    // One tick of travel per event: the horizontal cap, but never less than
                    // terminal falling speed — a bunch of packets from a long fall is legit.
                    double perEvent = Math.max(getMaxSpeed(moveType, player), TERMINAL_FALL_SPEED);
                    jumped = Math.max(jumped, sinceBurst - perEvent * (inBurst - 1));
                }
            }
            if (jumped > config.teleportThreshold()) {
                setBack = handleViolation(player, "TELEPORT",
                        lang.format("alert.teleport", jumped), jumped, to);
                // The rate checks would only re-flag the same jump as speed or flight.
                if (!setBack) rebaseline(playerId, to, false, false);
                return;
            }
        }

        if (elapsedMs < MIN_SAMPLE_MS) {
            sampleEvents.put(playerId, events);
            lastLocations.put(playerId, to.clone());
            lastMoveTime.put(playerId, now);
            return;
        }

        {
            // The sample's displacement. The rate checks compare a per-TICK rate against
            // per-tick thresholds (0.4 blocks walking, 3.5 vertical, …), so it is divided by
            // the ticks the sample spans: the real time since it began, but never fewer than
            // the move events folded into it. A client sends one position packet per tick, so
            // a bunch of packets delivered together after a stall carries one tick of travel
            // each; dividing that bunch by the few milliseconds it took to arrive would read
            // a lagging player as a speed hacker. Packets sent faster than real time are the
            // Timer check's business, and the time-based speed budget below still judges the
            // distance against the real clock.
            //
            // Clamped at one tick, so sub-tick samples are never scaled UP: that direction
            // would invent speed rather than remove it.
            Vector movement = to.toVector().subtract(sampleStart.toVector());
            double rawHorizontal = Math.sqrt(movement.getX() * movement.getX() + movement.getZ() * movement.getZ());
            double elapsedTicks = elapsedMs / 50.0;
            double moveTicks = Math.max(1.0, Math.max(elapsedTicks, events));
            if (moveTicks > 1.0) movement.multiply(1.0 / moveTicks);

            // The next sample starts here.
            sampleFrom.put(playerId, to.clone());
            sampleAt.put(playerId, now);
            sampleEvents.remove(playerId);

            double horizontalDist = Math.sqrt(movement.getX() * movement.getX() + movement.getZ() * movement.getZ());
            double verticalDist = Math.abs(movement.getY());

            // Get max allowed speed for this movement type
            double maxSpeed = getMaxSpeed(moveType, player);
            // Jump strength is an attribute too — a plugin or item that grants a higher
            // jump legitimately produces a bigger single-step vertical delta.
            double maxVerticalSpeed = config.flyThreshold() * CheckMath.jumpStrengthRatio(player);

            // Block lookups reused by several checks below — this runs on every movement
            // packet of every player, so each scan is done once and shared.
            // Bounce blocks (slime, beds, shelf mushroom) and a sulfur cube underfoot throw a
            // landing player back up; a bubble column or a potent-sulfur geyser pushes them.
            // Slime keeps its full grace. Beds and the shelf mushroom only count once they have
            // actually thrown the player back up — a fast descent onto them followed by a rise —
            // so standing or walking on them changes nothing.
            boolean nearSlimeBlock = isNearSlimeBlock(to);
            boolean bouncyEntity = movement.getY() > 0.1 && isOnBouncyEntity(player);
            boolean bedBounce = !nearSlimeBlock && movement.getY() > 0
                    && isBounce(lastFastDescent.get(playerId), now)
                    && (isNearBedBounceBlock(from) || isNearBedBounceBlock(to));
            if (movement.getY() < -BOUNCE_MIN_IMPACT) lastFastDescent.put(playerId, now);
            boolean nearSlime = nearSlimeBlock || bouncyEntity || bedBounce;
            boolean nearGeyser = geyserProbe.get();
            boolean nearBubble = nearGeyser || isNearBubbleColumn(to);

            // Bounce blocks / pushing columns allow faster vertical movement
            if (nearSlime || nearBubble) {
                maxVerticalSpeed *= 2.0; // Double vertical speed allowance
            }

            // Slime launchers throw a player sideways as readily as upwards, and the bounce
            // itself needs no key input. Computed here so the horizontal checks see it too.
            // A geyser throws a player up to twenty blocks high, far beyond what the scans
            // can follow, so it is remembered the same way. A bed or shelf-mushroom bounce is
            // remembered for the vertical checks only.
            if (nearSlimeBlock || bouncyEntity || nearGeyser) recentSlime.put(playerId, now);
            if (bedBounce) recentBounce.put(playerId, now);
            Long slimeTs = recentSlime.get(playerId);
            boolean slimeGrace = slimeTs != null && (now - slimeTs) < SLIME_GRACE_MS;
            Long bounceTs = recentBounce.get(playerId);
            boolean bounceGrace = slimeGrace || (bounceTs != null && (now - bounceTs) < SLIME_GRACE_MS);

            // Air drag, friction or bounciness changed by an attribute (26.2): neither the
            // speed caps nor the gravity model describe this player's motion any more.
            boolean modifiedPhysics = CheckMath.hasModifiedPhysics(player);
            boolean speedGrace = slimeGrace || modifiedPhysics;

            // A spear lunge throws the player forwards without any velocity packet.
            double lungeAllowance = lungeAllowance(player, playerId, now);
            double speedCap = maxSpeed + lungeAllowance;

            // On-foot states (vertical and item-use checks use their own physics)
            boolean groundType = moveType == MovementType.WALKING
                    || moveType == MovementType.SPRINTING
                    || moveType == MovementType.SNEAKING;

            // Check horizontal speed (if enabled)
            // This ensures we catch both walking and sprinting violations
            boolean speedFlagged = false;
            if (config.speedDetectionEnabled() && horizontalDist > speedCap) {
                suspect = true;
                int consecutive = consecutiveSpeedViolations.merge(playerId, 1, Integer::sum);

                // Only alert after multiple consecutive violations
                if (consecutive >= config.speedViolationsThreshold()) {
                    // Checked here rather than at the top: both are displacement the player
                    // never asked for, and the piston lookup should only be paid for on the
                    // would-flag path.
                    if (!speedGrace && !pistonExplainsHorizontal(to, horizontalDist, speedCap)) {
                        setBack |= handleViolation(player, "SPEED",
                            lang.format("alert.speed", typeName(moveType), horizontalDist, speedCap),
                            horizontalDist, to);
                        speedFlagged = true;
                    }
                    consecutiveSpeedViolations.put(playerId, 0);
                }
            } else if (horizontalDist < speedCap * 0.7) {
                // Only reset if speed is significantly below threshold (70%)
                // This prevents a single valid move from washing out violations too quickly
                consecutiveSpeedViolations.compute(playerId, (k, v) -> {
                    if (v == null || v <= 0) return 0;
                    return Math.max(0, v - 1);
                });
            }

            // Averaged speed: the per-sample check above needs several over-speed samples in
            // a row, so alternating fast and slow samples (0.8 / 0.2 against a 0.4 cap) never
            // builds a streak while averaging well over the cap. See checkSpeedBudget.
            if (config.speedDetectionEnabled()) {
                int budget = checkSpeedBudget(player, playerId, moveType, rawHorizontal, elapsedTicks,
                        maxSpeed, speedGrace, speedFlagged, to, now);
                if (budget != BUDGET_OK) suspect = true;
                if (budget == BUDGET_SETBACK) setBack = true;
            }

            // NoSlow: moving too fast while using an item (eating, drawing a bow,
            // blocking with a shield, charging a trident…) which vanilla slows down.
            // isHandRaised() reflects the (client-influenced) item-use state.
            if (config.noSlowDetectionEnabled() && groundType && player.isHandRaised() && !modifiedPhysics) {
                double noSlowCap = getMaxSpeed(MovementType.WALKING, player) * config.noSlowSpeedMultiplier();
                if (horizontalDist > noSlowCap) {
                    suspect = true;
                    int c = consecutiveNoSlow.merge(playerId, 1, Integer::sum);
                    if (c >= config.noSlowViolations()) {
                        setBack |= handleViolation(player, "NOSLOW",
                            lang.format("alert.noslow", horizontalDist, noSlowCap), horizontalDist, to);
                        consecutiveNoSlow.put(playerId, 0);
                    }
                } else {
                    consecutiveNoSlow.remove(playerId);
                }
            } else {
                consecutiveNoSlow.remove(playerId);
            }

            // Sprinting that a vanilla client would have ended by itself.
            if (groundType) {
                suspect |= checkSprintRules(player, playerId, movement, horizontalDist, now,
                        speedGrace || lungeAllowance > 0, to);
            } else {
                clearSprintState(playerId);
            }

            // Every check below reads blocks, and several of them read the player's
            // NEIGHBOURS: the ground scan samples all four footprint corners, the
            // fall-slowing and liquid tests look sideways, and Spider/Jesus look at the
            // walls. Any of those can cross a chunk border, so the guard has to cover the
            // neighbourhood rather than only the block the player stands in.
            //
            // Two things go wrong without it. An unloaded chunk has no blocks to find, so
            // the scan reports "nothing below the player" for someone standing on perfectly
            // solid ground the server has not got in memory yet. And reading it forces a
            // synchronous chunk load from inside a movement handler, which Folia forbids.
            boolean chunkKnown = neighbourhoodLoaded(to);

            // Vertical/fly checks only apply to on-foot movement. Swimming and
            // climbing have their own vertical physics and would false-positive.
            if (groundType && chunkKnown) {
                // (bounceGrace is computed above, where the horizontal checks can see the slime
                // part too — a bounce launches far higher than the 2-block scan can follow.)

                // Towering up: the player is building the ground they stand on, one block per
                // jump. Applied to the hover check only — the vertical-burst and GroundSpoof
                // checks stay armed, since pillaring produces neither a 3.5-block jump nor a
                // player who is genuinely high above the ground.
                Long pillarTs = recentPillar.get(playerId);
                boolean pillarGrace = pillarTs != null && (now - pillarTs) < PILLAR_GRACE_MS;

                boolean flyExempt = player.hasPotionEffect(PotionEffectType.LEVITATION)
                        || player.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                        // Jump Boost raises both the height AND the time spent rising, which
                        // feeds the hover counter. The Step check already excluded it.
                        || player.hasPotionEffect(PotionEffectType.JUMP_BOOST)
                        // Reduced gravity means hanging in the air without descending is
                        // legitimate — which is precisely what the hover check looks for.
                        || CheckMath.hasReducedGravity(player)
                        || modifiedPhysics
                        || isInFallSlowingBlock(player)
                        || isNearLiquid(player) || bounceGrace || nearBubble;

                // One ground scan answers both vertical checks: "clearly airborne" (hover)
                // and "high above ground" (GroundSpoof) differ only in the depth they
                // accept, so the scan runs once.
                // (The chunk neighbourhood is already known to be loaded — see above.)
                boolean wantsFlyScan = config.flyDetectionEnabled() && !flyExempt;
                boolean wantsSpoofScan = config.groundSpoofDetectionEnabled() && !flyExempt
                        && player.isOnGround();
                int support = (wantsFlyScan || wantsSpoofScan)
                        ? supportDepth(player, GROUNDSPOOF_SCAN_DEPTH) : 0;
                // A block scan cannot see that the player is standing on a BOAT, a minecart,
                // a horse or another player's head — all legitimate ground. The entity query
                // is only run when the block scan found nothing, i.e. in the would-flag path,
                // so it costs nothing during normal play.
                if (support < 0 && hasEntitySupport(player)) support = AIRBORNE_SCAN_DEPTH;
                boolean clearlyAirborne = support < 0 || support > AIRBORNE_SCAN_DEPTH;
                boolean highAboveGround = support < 0;

                if (config.flyDetectionEnabled()) {
                    // (a) Vertical burst: too much upward movement in a single step
                    if (!flyExempt && verticalDist > maxVerticalSpeed && movement.getY() > 0) {
                        suspect = true;
                        int consecutive = consecutiveFlyViolations.merge(playerId, 1, Integer::sum);
                        if (consecutive >= config.flyViolationsThreshold()) {
                            // A piston lifts a player a full block per push with no velocity
                            // packet to excuse it — elevators and flying machines do this all
                            // day. Only a vertical push, and only by what one push can add.
                            if (!pistonExplainsVertical(to, verticalDist, maxVerticalSpeed)) {
                                setBack |= handleViolation(player, "FLY",
                                    lang.format("alert.fly", verticalDist, maxVerticalSpeed),
                                    verticalDist, to);
                            }
                            consecutiveFlyViolations.put(playerId, 0);
                        }
                    } else if (verticalDist < maxVerticalSpeed * 0.5) {
                        consecutiveFlyViolations.compute(playerId, (k, v) -> {
                            if (v == null || v <= 0) return 0;
                            return Math.max(0, v - 1);
                        });
                    }

                    // (b) Sustained hover: airborne across many samples without ever
                    // falling. Speed potions / sprinting are irrelevant here — only the
                    // vertical state matters, so legitimate fast running is unaffected.
                    if (!flyExempt && !pillarGrace && clearlyAirborne) {
                        if (Math.abs(movement.getY()) > HOVER_STILL_BAND) {
                            // Falling under gravity, or rising — neither is hovering. The rise
                            // matters as much as the fall: a wind charge, a Wind Burst mace or
                            // a Breeze throws a player upwards for longer than any knockback
                            // grace lasts, and the tail of that arc is exactly a slow climb
                            // with nothing underneath. Sustained climbing is judged separately,
                            // by whether it decays the way gravity requires.
                            consecutiveHoverTicks.remove(playerId);
                            hoverStartDy.remove(playerId);
                        } else {
                            suspect = true;
                            int hover = consecutiveHoverTicks.merge(playerId, 1, Integer::sum);
                            // Vertical speed when this run began. A hover HOLDS a speed; a
                            // ballistic arc passing through the band is still losing one, and
                            // the band is wide enough (+-0.08) that the apex of a slow arc sits
                            // inside it for many samples. Whether dy is CHANGING, not whether it
                            // is small, separates the two (as in checkSustainedAscent).
                            if (hover == 1) hoverStartDy.put(playerId, movement.getY());
                            double startDy = hoverStartDy.getOrDefault(playerId, movement.getY());
                            double drop = startDy - movement.getY();
                            // Logged at info so it survives the default log level. Only on the
                            // counting path, which is bounded by how often a player is
                            // genuinely airborne and still.
                            // `support` is the ground scan's answer: 0-3 = blocks found that
                            // far below the feet, -1 = nothing within reach.
                            if (config.debugMode()) {
                                plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                                        "[HOVER-DEBUG] %s y=%.2f dy=%.3f drop=%.3f (max %.3f) "
                                                + "support=%d onGround=%b pillarGrace=%b ticks=%.1f (%d/%d)",
                                        player.getName(), to.getY(), movement.getY(), drop,
                                        config.flyHoverMaxDrop(), support,
                                        player.isOnGround(), pillarGrace, moveTicks,
                                        hover, config.flyViolationsThreshold()));
                            }
                            if (drop > config.flyHoverMaxDrop()) {
                                // Still losing vertical speed, so this run is a fall through
                                // the band and not a hover. RESTART it rather than merely
                                // withholding the flag: the drop is measured against the run's
                                // first sample and never recovers, so a run that once exceeded
                                // the limit could never flag again, and a hover entered from a rise (dy +0.06, then held at
                                // -0.01) stays inside the +-0.08 band indefinitely with a
                                // permanent drop of 0.07. Restarting costs the ballistic case
                                // nothing, because it keeps falling out of the band anyway,
                                // while a held altitude rebuilds the count within a few ticks
                                // from a start sample that no longer carries the rise.
                                consecutiveHoverTicks.put(playerId, 1);
                                hoverStartDy.put(playerId, movement.getY());
                            } else if (hover >= config.flyViolationsThreshold()) {
                                if (!pistonHoldsUp(to)) {
                                    setBack |= handleViolation(player, "FLY",
                                        lang.format("alert.hover", hover),
                                        verticalDist, to);
                                }
                                consecutiveHoverTicks.put(playerId, 0);
                                hoverStartDy.remove(playerId);
                                // The same stretch must not be flagged twice.
                                airWindows.remove(playerId);
                            }
                        }
                    } else {
                        consecutiveHoverTicks.remove(playerId);
                        hoverStartDy.remove(playerId);
                    }

                    // (b2) Sustained ascent (opt-in, off by default). The hover band above does
                    // not count a climb, so a slow steady rise is judged here. Gravity is what
                    // separates the two:
                    // a player thrown upwards loses ~0.08 b/t of vertical speed every tick and
                    // is back down within a second or two, while a flight cheat holds the climb.
                    if (!flyExempt && !pillarGrace && clearlyAirborne) {
                        setBack |= checkSustainedAscent(player, playerId, movement.getY(), moveTicks, to);
                        if (consecutiveAscent.getOrDefault(playerId, 0) > 0) suspect = true;
                    } else {
                        lastAscentDy.remove(playerId);
                        consecutiveAscent.remove(playerId);
                    }

                    // (b3) Free fall: over a whole airborne stretch, the player must have come
                    // down at least as far as gravity pulls them. Always on with fly detection.
                    if (!flyExempt && !pillarGrace && clearlyAirborne && !player.getAllowFlight()) {
                        int gravity = checkFreeFall(player, playerId, sampleStart, sampleStartAt,
                                events, movement.getY(), to, now);
                        if (gravity != GRAVITY_OK) suspect = true;
                        if (gravity == GRAVITY_SETBACK) setBack = true;
                    } else {
                        airWindows.remove(playerId);
                    }
                } else {
                    airWindows.remove(playerId);
                }

                // (c) GroundSpoof: the client claims it is on the ground while it is
                // clearly several blocks up in the air. Fly/NoFall spoof the on-ground
                // flag (which player.isOnGround() reflects) to dodge the hover check.
                if (config.groundSpoofDetectionEnabled() && !flyExempt
                        && player.isOnGround() && highAboveGround) {
                    suspect = true;
                    int gs = consecutiveGroundSpoof.merge(playerId, 1, Integer::sum);
                    if (gs >= config.groundSpoofViolations()) {
                        if (!pistonHoldsUp(to)) {
                            setBack |= handleViolation(player, "GROUNDSPOOF",
                                lang.get("alert.groundspoof"), 0, to);
                        }
                        consecutiveGroundSpoof.put(playerId, 0);
                    }
                } else {
                    consecutiveGroundSpoof.remove(playerId);
                }

                // Jesus: moving across the top of water without sinking
                if (config.jesusDetectionEnabled() && !player.isInWater() && !player.isGliding()
                        && horizontalDist > 0.08 && Math.abs(movement.getY()) < 0.05 && isOnWaterSurface(player)) {
                    suspect = true;
                    int c = consecutiveJesus.merge(playerId, 1, Integer::sum);
                    if (c >= config.jesusViolations()) {
                        setBack |= handleViolation(player, "JESUS", lang.get("alert.jesus"), horizontalDist, to);
                        consecutiveJesus.put(playerId, 0);
                    }
                } else {
                    consecutiveJesus.remove(playerId);
                }

                // Spider: climbing a wall (moving up while pressed against a solid block, not a ladder)
                // Require meaningful sustained upward motion (> 0.1/tick) so the tail of a
                // normal jump next to a wall doesn't count as climbing.
                if (config.spiderDetectionEnabled() && movement.getY() > 0.1 && !player.isOnGround()
                        && !isNearLiquid(player) && !isOnClimbable(player) && isAgainstWall(player)) {
                    suspect = true;
                    int c = consecutiveSpider.merge(playerId, 1, Integer::sum);
                    if (c >= config.spiderViolations()) {
                        setBack |= handleViolation(player, "SPIDER", lang.get("alert.spider"), movement.getY(), to);
                        consecutiveSpider.put(playerId, 0);
                    }
                } else {
                    consecutiveSpider.remove(playerId);
                }

                // Step: stepping up more than the vanilla ~0.6 in one move while staying grounded
                if (config.stepDetectionEnabled() && player.isOnGround()
                        && movement.getY() > Math.max(config.stepMaxHeight(), CheckMath.stepHeight(player))
                        && horizontalDist > 0.05
                        && !nearSlime && !player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
                    suspect = true;
                    int c = consecutiveStep.merge(playerId, 1, Integer::sum);
                    if (c >= config.stepViolations()) {
                        setBack |= handleViolation(player, "STEP", lang.format("alert.step", movement.getY()), movement.getY(), to);
                        consecutiveStep.put(playerId, 0);
                    }
                } else {
                    consecutiveStep.remove(playerId);
                }
            } else if (groundType) {
                // On-foot, but the surrounding chunks are not all in memory — typically a
                // player walking into fresh terrain. Nothing here can be judged without
                // reading blocks, so the streaks are dropped rather than carried across the
                // blind spot: resuming a half-built hover count on the far side of it would
                // flag on evidence that was never actually gathered.
                consecutiveFlyViolations.remove(playerId);
                consecutiveHoverTicks.remove(playerId);
                consecutiveGroundSpoof.remove(playerId);
                consecutiveJesus.remove(playerId);
                consecutiveSpider.remove(playerId);
                consecutiveStep.remove(playerId);
                airWindows.remove(playerId);
            } else {
                // Swimming or climbing: the free-fall model does not apply, and an airborne
                // stretch cannot continue across it.
                airWindows.remove(playerId);
            }
        }

        // After a setback the teleport handler restores the baseline position, so don't
        // overwrite it with the illegal location. Only a sample that passed every check
        // without so much as starting a streak becomes the setback target: a position
        // reached while a violation was building is exactly what a setback must undo.
        if (!setBack) {
            lastLocations.put(playerId, to.clone());
            if (!suspect) lastLegitLocations.put(playerId, to.clone());
        }
        lastMoveTime.put(playerId, now);
    }

    /**
     * Climbing that gravity cannot explain.
     *
     * <p>Ballistic motion has a signature: whatever threw the player upwards, the ascent loses
     * about one tick of gravity (0.08 blocks) of vertical speed per tick and ends. A cheat
     * holding a player in a climb does not decay. Only the missing decay is judged here, never
     * the climb itself — being thrown upwards is ordinary, and a Trial Chamber full of Breezes
     * does it all day.
     *
     * <p>Off by default: it is a heuristic that should be calibrated with {@code debug_mode}
     * data from the server it runs on before it is trusted.
     *
     * @return true when the player was set back
     */
    private boolean checkSustainedAscent(Player player, UUID playerId, double dy, double moveTicks, Location to) {
        if (!config.sustainedAscentDetectionEnabled()) return false;

        Double previous = lastAscentDy.put(playerId, dy);

        // Not climbing → nothing to judge, and the streak is over.
        if (dy <= HOVER_STILL_BAND) {
            consecutiveAscent.remove(playerId);
            return false;
        }
        // A sample spanning several ticks should have decayed by several ticks' worth of
        // gravity, and the arithmetic for that is guesswork. Skip it rather than guess.
        if (moveTicks > 1.5 || previous == null) return false;

        double decay = previous - dy;
        if (decay >= config.sustainedAscentMinDecay()) {
            consecutiveAscent.remove(playerId); // slowing down like a thrown object should
            return false;
        }

        int c = consecutiveAscent.merge(playerId, 1, Integer::sum);
        if (config.debugMode()) {
            plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                    "[ASCENT-DEBUG] %s dy=%.3f decay=%.3f (%d/%d)",
                    player.getName(), dy, decay, c, config.sustainedAscentViolations()));
        }
        if (c < config.sustainedAscentViolations()) return false;

        consecutiveAscent.remove(playerId);
        if (pistonHoldsUp(to)) return false; // a column of pistons lifts at a constant rate
        return handleViolation(player, "FLY",
                lang.format("alert.sustained_ascent", dy), dy, to);
    }

    /**
     * Horizontal speed judged against the real clock.
     *
     * <p>A budget of allowed travel accrues at the speed cap for every tick of real time and
     * is spent by the distance actually covered; it holds at most
     * {@link #SPEED_BUDGET_TICKS} ticks' worth. The per-sample check needs several
     * over-speed samples in a row, so a cheat alternating fast and slow samples never builds
     * a streak — but its average still drains the budget, and the budget running
     * {@link #SPEED_DEBT_TICKS} ticks into debt is a SPEED violation.
     *
     * <p>Lag does not trip it: a stall accrues budget while no packets arrive, and the backlog
     * delivered afterwards spends exactly that. Packets sent faster than real time gain
     * nothing either, since accrual is by the clock and not per packet.
     *
     * @return {@link #BUDGET_OK}, {@link #BUDGET_DEBT} (in debt, not flagged) or
     *         {@link #BUDGET_SETBACK} (flagged and set back)
     */
    private int checkSpeedBudget(Player player, UUID playerId, MovementType moveType, double distance,
                                 double elapsedTicks, double maxSpeed, boolean slimeGrace,
                                 boolean alreadyFlagged, Location to, long now) {
        double capacity = maxSpeed * SPEED_BUDGET_TICKS;
        double[] b = speedBudget.computeIfAbsent(playerId, k -> new double[]{capacity, 0.0, 0.0});
        double available = Math.min(b[0] + maxSpeed * elapsedTicks, capacity);
        if (available >= capacity) {
            b[1] = 0.0;
            b[2] = 0.0;
        }
        b[0] = available - distance;
        b[1] += distance;
        b[2] += elapsedTicks;
        // A lunge's travel is paid from its own one-off credit, outside the capacity cap.
        if (b[0] < 0) {
            double[] credit = lungeCredit.get(playerId);
            if (credit != null && now <= credit[1] && credit[0] > 0) {
                double take = Math.min(-b[0], credit[0]);
                credit[0] -= take;
                b[0] += take;
                b[1] -= take;
            }
        }
        if (b[0] >= 0) return BUDGET_OK;
        if (alreadyFlagged) {
            // The per-sample check has just reported this stretch; don't report it twice.
            b[0] = 0.0;
            return BUDGET_DEBT;
        }
        if (b[0] >= -maxSpeed * SPEED_DEBT_TICKS) return BUDGET_DEBT;

        double average = b[1] / Math.max(1.0, b[2]);
        b[0] = 0.0;
        b[1] = 0.0;
        b[2] = 0.0;
        if (slimeGrace || pistonExplainsHorizontal(to, average, maxSpeed)) return BUDGET_DEBT;
        boolean setBack = handleViolation(player, "SPEED",
                lang.format("alert.speed", typeName(moveType), average, maxSpeed), average, to);
        return setBack ? BUDGET_SETBACK : BUDGET_DEBT;
    }

    /**
     * Airborne stretches that gravity cannot explain.
     *
     * <p>From the first sample with nothing underneath, the stretch is compared against free
     * fall: after {@link #FREE_FALL_MIN_TICKS} ticks in the air a player must have come down
     * at least as far as vanilla gravity and drag take them, starting from the fastest upward
     * speed they can have had — a jump (with the jump-strength attribute), or the speed
     * actually observed in the first sample if that was higher. This covers what the hover
     * band cannot see: bobbing up and down around one height, sinking at a constant slow
     * rate, and a steady climb. Everything that legitimately defies gravity is exempt at the
     * call site or ends the stretch (knockback and flight grace, water, climbables,
     * scaffolding, cobwebs, powder snow, honey, bubble columns, slime, potions, pistons).
     *
     * <p>Time is the smaller of real elapsed ticks and the number of move events: a stall
     * stretches the arrival times without adding flight time, and judging a stretched window
     * against a longer fall would flag a lagging player.
     *
     * @return {@link #GRAVITY_OK}, {@link #GRAVITY_SUSPECT} (above the free-fall curve) or
     *         {@link #GRAVITY_SETBACK} (flagged and set back)
     */
    private int checkFreeFall(Player player, UUID playerId, Location sampleStart, long sampleStartAt,
                              int events, double dyPerTick, Location to, long now) {
        AirWindow w = airWindows.get(playerId);
        if (w == null) {
            double launch = VANILLA_JUMP_VELOCITY * CheckMath.jumpStrengthRatio(player) + LAUNCH_MARGIN;
            w = new AirWindow(sampleStart.getY(), sampleStartAt, Math.max(launch, dyPerTick));
            airWindows.put(playerId, w);
        }
        w.events += events;
        double ticks = Math.min((now - w.startAt) / 50.0, w.events);
        double rise = to.getY() - w.startY;
        double freeFall = CheckMath.ballisticRise(w.v0, PLAYER_GRAVITY, PLAYER_DRAG, ticks);
        if (rise <= freeFall) return GRAVITY_OK;
        if (ticks < FREE_FALL_MIN_TICKS || rise <= freeFall + FREE_FALL_TOLERANCE) return GRAVITY_SUSPECT;

        airWindows.remove(playerId);
        // The hover streak covers part of the same stretch; it must not flag it again.
        consecutiveHoverTicks.remove(playerId);
        hoverStartDy.remove(playerId);
        if (pistonHoldsUp(to)) return GRAVITY_SUSPECT;

        if (config.debugMode()) {
            plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                    "[FREEFALL-DEBUG] %s rise=%.2f expected<=%.2f ticks=%.1f events=%d v0=%.2f",
                    player.getName(), rise, freeFall, ticks, w.events, w.v0));
        }
        double perTick = rise / ticks;
        String details = perTick > HOVER_STILL_BAND
                ? lang.format("alert.sustained_ascent", perTick)
                : lang.format("alert.hover", (int) Math.round(ticks));
        return handleViolation(player, "FLY", details, rise, to) ? GRAVITY_SETBACK : GRAVITY_SUSPECT;
    }

    // ==================== Spear lunge ====================

    /**
     * Extra horizontal speed per tick a recent lunge accounts for, 0 when there is none.
     *
     * <p>The impulse is 0.458 blocks per tick per Lunge level and stronger airborne; the
     * airborne factor is always applied, since whether the stab was made in the air is not
     * known here. Vanilla applies no lunge while riding, gliding or in water. Each lunge also
     * earns a one-off credit for the speed budget covering the distance the impulse carries
     * before drag eats it; a newer lunge replaces the credit rather than adding to it, so
     * stabbing repeatedly never buys more than one lunge's worth of travel at a time.
     */
    double lungeAllowance(Player player, UUID playerId, long now) {
        if (lungeTracker == null) return 0.0;
        LungeTracker.Lunge lunge = lungeTracker.lastLunge(playerId);
        if (lunge == null) return 0.0;
        if (player.isInsideVehicle() || player.isGliding() || player.isInWater()) return 0.0;
        double impulse = Constants.LUNGE_IMPULSE_PER_LEVEL * lunge.level() * Constants.LUNGE_AIR_FACTOR;
        // A stab following the previous one within the spacing belongs to the same lunge: a
        // burst of STAB packets must not keep restarting the window.
        double[] credit = lungeCredit.get(playerId);
        long stab = lunge.timeMs();
        if (credit == null || stab - (long) credit[3] >= Constants.LUNGE_MIN_SPACING_MS) {
            if (now - stab >= 0 && now - stab <= LUNGE_CREDIT_MS) {
                double remaining = credit != null && now <= credit[1] ? credit[0] : 0.0;
                lungeCredit.put(playerId, new double[]{
                        Math.max(remaining, impulse * Constants.LUNGE_BUDGET_TICKS), stab + LUNGE_CREDIT_MS,
                        stab, stab});
            }
        } else {
            if (stab > (long) credit[3]) credit[3] = stab;
            stab = (long) credit[2];
        }
        long age = now - stab;
        return age >= 0 && age <= Constants.LUNGE_WINDOW_MS ? impulse : 0.0;
    }

    // ==================== Fall measurement / NoFall ====================

    /**
     * Follow the player's descent as the server sees it (see {@link FallTracker}) and judge
     * landings. Runs for NoFall and for the mace check, which reads the measured distance.
     */
    private void trackFall(Player player, UUID id, Location from, Location to, long now, GeyserProbe geyserProbe) {
        boolean noFall = config.noFallDetectionEnabled();
        if (!noFall && !config.maceDetectionEnabled()) return;
        resolvePendingLanding(player, now);
        if (!neighbourhoodLoaded(to)) {
            disturbFall(id, now);
            return;
        }
        // Places where vanilla resets the fall distance itself: water, climbables, webs…
        if (resetsFall(player, to)) {
            landFall(id, now);
            return;
        }
        // …and everything whose effect on the fall the measurement does not model.
        if (disturbsFall(player, id, to, now, geyserProbe)) {
            disturbFall(id, now);
            return;
        }
        double dy = to.getY() - from.getY();
        if (dy < 0) fallTracker.addDescent(id, -dy, now);

        if (dy <= 1.0e-9 && isLandedAt(player, to)) {
            FallTracker.State fall = fallTracker.state(id);
            boolean counted = noFallCounted.contains(id);
            landFall(id, now);
            if (noFall && fall.reliable() && !counted && fall.distance() > 0) {
                checkLanding(player, id, fall.distance(), to, now);
            }
            return;
        }
        if (Math.abs(dy) < 1.0e-4) {
            // Not descending and no block underneath: standing on an entity (boat, another
            // player). Vanilla resets on it too.
            fallTracker.resetKeepingTrust(id, now);
            noFallSpoofSamples.remove(id);
            return;
        }
        if (noFall && dy < 0) checkMidAirReset(player, id, to);
    }

    private void landFall(UUID id, long now) {
        fallTracker.land(id, now);
        noFallSpoofSamples.remove(id);
        noFallCounted.remove(id);
    }

    private void disturbFall(UUID id, long now) {
        fallTracker.disturb(id, now);
        noFallSpoofSamples.remove(id);
        noFallCounted.remove(id);
    }

    /** True where vanilla resets fall distance: liquids, climbables, webs, powder snow, honey walls. */
    private boolean resetsFall(Player player, Location to) {
        if (player.isInWater() || player.isSwimming() || player.isClimbing()) return true;
        if (isInFallSlowingBlock(to)) return true;
        org.bukkit.block.Block feet = to.getBlock();
        return resetsFallAt(feet) || resetsFallAt(feet.getRelative(0, 1, 0));
    }

    private static boolean resetsFallAt(org.bukkit.block.Block block) {
        Material m = block.getType();
        if (m.isAir()) return false;
        if (m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN
                || m == Material.COBWEB || m == Material.POWDER_SNOW || m == Material.SCAFFOLDING
                || m == Material.SWEET_BERRY_BUSH) return true;
        if (isClimbableMaterial(m)) return true;
        if (m.isBlock() && org.bukkit.Tag.FALL_DAMAGE_RESETTING.isTagged(m)) return true;
        return block.getBlockData() instanceof org.bukkit.block.data.Waterlogged w && w.isWaterlogged();
    }

    /** True when something acts on the fall that the measurement does not model. */
    private boolean disturbsFall(Player player, UUID id, Location to, long now, GeyserProbe geyserProbe) {
        if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                || player.hasPotionEffect(PotionEffectType.LEVITATION)) return true;
        if (player.getAllowFlight() || player.isFlying() || player.isGliding() || player.isRiptiding()
                || player.isInsideVehicle()) return true;
        if (CheckMath.hasReducedGravity(player) || CheckMath.hasModifiedPhysics(player)) return true;
        Long bounce = recentSlime.get(id);
        if (bounce != null && now - bounce < SLIME_GRACE_MS) return true;
        Long bedBounce = recentBounce.get(id);
        if (bedBounce != null && now - bedBounce < SLIME_GRACE_MS) return true;
        // Slime changes the fall near it. Beds and the shelf mushroom do not while the player
        // merely stands or walks on them: landing on them is an ordinary landing (the landing
        // judgement excuses them as fall-cushioning blocks), and an actual bounce is covered by
        // the bounce grace above.
        if (isNearSlimeBlock(to) || isNearBubbleColumn(to) || geyserProbe.get()) return true;
        return pistonHoldsUp(to);
    }

    /**
     * True when the player's feet rest exactly on the collision top of a block under their
     * footprint: the feet block (slabs, beds, snow layers) or the one below.
     */
    private boolean isLandedAt(Player player, Location to) {
        double feetY = to.getY();
        int base = (int) Math.floor(feetY);
        double half = 0.3 * CheckMath.scale(player);
        double[] dx = {0, half, -half, half, -half};
        double[] dz = {0, half, half, -half, -half};
        for (int i = 0; i < dx.length; i++) {
            int bx = (int) Math.floor(to.getX() + dx[i]);
            int bz = (int) Math.floor(to.getZ() + dz[i]);
            for (int k = 0; k <= 1; k++) {
                if (landsOn(to.getWorld().getBlockAt(bx, base - k, bz), feetY)) return true;
            }
        }
        return false;
    }

    private static boolean landsOn(org.bukkit.block.Block block, double feetY) {
        Material m = block.getType();
        if (m.isAir()) return false;
        try {
            if (block.isPassable()) return false;
            org.bukkit.util.BoundingBox box = block.getBoundingBox();
            return box.getVolume() > 0 && Math.abs(box.getMaxY() - feetY) < 0.01;
        } catch (Throwable t) {
            // Collision shape unavailable: judge full blocks by their top face.
            return m.isSolid() && Math.abs(block.getY() + 1 - feetY) < 0.01;
        }
    }

    /**
     * A measured fall well past the safe distance ended on ordinary ground. Whether fall
     * damage followed is only known a little later — the server applies it after the move
     * event — so the landing is parked and resolved by {@link #resolvePendingLanding}.
     */
    private void checkLanding(Player player, UUID id, double fallen, Location to, long now) {
        double safe = CheckMath.safeFallDistance(player);
        var jump = player.getPotionEffect(PotionEffectType.JUMP_BOOST);
        if (jump != null) safe += jump.getAmplifier() + 1;
        double multiplier = CheckMath.fallDamageMultiplier(player);
        if (multiplier <= 0) return;
        // Feather Falling lowers the damage; each level is treated as one more safe block.
        var boots = player.getInventory().getBoots();
        int feather = boots == null ? 0 : boots.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.FEATHER_FALLING);
        double excess = (fallen - safe) * Math.min(multiplier, 1.0);
        if (excess < config.noFallMinExtraDistance() + feather) return;
        if (player.isInvulnerable() || player.getNoDamageTicks() > 0) return;
        if (!fallDamageRuleOn(player)) return;
        if (landingSoftened(player, to)) return;
        Long damage = lastFallDamage.get(id);
        if (damage != null && damage >= now - 1000) return; // already arrived
        pendingLandings.put(id, new PendingLanding(fallen, now, to.clone()));
        try {
            dev.boondock.bsanticheat.util.Scheduler.runForEntityLater(plugin, player,
                    () -> resolvePendingLanding(player, System.currentTimeMillis()),
                    Constants.NOFALL_RESOLVE_MS / 50 + 2);
        } catch (Throwable ignored) {
            // no entity scheduler: resolved on the next move instead
        }
    }

    private static boolean fallDamageRuleOn(Player player) {
        try {
            Boolean rule = player.getWorld().getGameRuleValue(org.bukkit.GameRule.FALL_DAMAGE);
            return rule == null || rule;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Landing blocks that reduce or cancel fall damage — hay, honey, beds, slime, the other
     * bounce blocks — or reset it, under any footprint corner at the feet or just below.
     */
    private boolean landingSoftened(Player player, Location to) {
        int base = (int) Math.floor(to.getY());
        double half = 0.3 * CheckMath.scale(player);
        double[] dx = {0, half, -half, half, -half};
        double[] dz = {0, half, half, -half, -half};
        for (int i = 0; i < dx.length; i++) {
            int bx = (int) Math.floor(to.getX() + dx[i]);
            int bz = (int) Math.floor(to.getZ() + dz[i]);
            for (int k = 0; k <= 1; k++) {
                org.bukkit.block.Block b = to.getWorld().getBlockAt(bx, base - k, bz);
                Material m = b.getType();
                if (m == Material.HAY_BLOCK || m == Material.HONEY_BLOCK
                        || GameCompat.isBounceBlock(m) || resetsFallAt(b)) return true;
            }
        }
        return false;
    }

    /**
     * Settle a parked landing once the fall damage event has had time to arrive. No damage by
     * then counts as one suspicious fall. Package-private so tests can drive the clock.
     */
    void resolvePendingLanding(Player player, long now) {
        UUID id = player.getUniqueId();
        PendingLanding landing = pendingLandings.get(id);
        if (landing == null || now - landing.at() < Constants.NOFALL_RESOLVE_MS) return;
        if (!pendingLandings.remove(id, landing)) return;
        Long damage = lastFallDamage.get(id);
        if (damage != null && damage >= landing.at() - 1000) return;
        if (player.isDead() || !player.isOnline()) return;
        noteNoFall(player, lang.format("alert.nofall_landing", landing.distance()),
                landing.distance(), landing.where(), now);
    }

    /**
     * Vanilla's fall distance fell far behind the measured one while the server finds nothing
     * underneath: the client claimed ground contact in mid-air, which resets it.
     */
    private void checkMidAirReset(Player player, UUID id, Location to) {
        FallTracker.State fall = fallTracker.state(id);
        double threshold = CheckMath.safeFallDistance(player) + config.noFallMinExtraDistance();
        if (!fall.reliable() || fall.distance() < threshold || noFallCounted.contains(id)) {
            noFallSpoofSamples.remove(id);
            return;
        }
        double claimed = player.getFallDistance();
        if (claimed + Math.max(2.0, fall.distance() * 0.5) >= fall.distance()) {
            noFallSpoofSamples.remove(id);
            return;
        }
        if (supportDepth(player, GROUNDSPOOF_SCAN_DEPTH) >= 0 || hasEntitySupport(player)) {
            noFallSpoofSamples.remove(id);
            return;
        }
        int c = noFallSpoofSamples.merge(id, 1, Integer::sum);
        if (c < Constants.NOFALL_SPOOF_SAMPLES) return;
        noFallSpoofSamples.remove(id);
        noteNoFall(player, lang.format("alert.nofall_spoof", fall.distance(), claimed),
                fall.distance(), to, System.currentTimeMillis());
    }

    /** One suspicious fall; flags once the streak within the window reaches the threshold. */
    private void noteNoFall(Player player, String details, double value, Location where, long now) {
        UUID id = player.getUniqueId();
        noFallCounted.add(id);
        long[] st = noFallStreak.compute(id, (k, v) -> {
            if (v == null || now - v[1] > NOFALL_STREAK_WINDOW_MS) return new long[]{1, now};
            v[0]++;
            v[1] = now;
            return v;
        });
        if (config.debugMode()) {
            plugin.getLogger().info("[NOFALL-DEBUG] " + player.getName() + ": " + details
                    + " (" + st[0] + "/" + config.noFallViolations() + ")");
        }
        if (st[0] < config.noFallViolations()) return;
        noFallStreak.remove(id);
        handleViolation(player, "NOFALL", details, value, where, false);
    }

    // ==================== Sprint rules ====================

    /**
     * Sprinting a 1.21.2+ client ends by itself: with a food level of 6 or less (unless it
     * may fly) and under Blindness. Kept up for longer than {@code sprint_min_duration_ms}
     * — which absorbs the latency before the client learns of the change — it is not vanilla.
     * Omni-sprint (opt-in): sprinting on the ground while moving well away from the view.
     *
     * @return true while a streak is building (the position is then no setback target)
     */
    private boolean checkSprintRules(Player player, UUID id, Vector movement, double horizontalDist,
                                     long now, boolean momentumGrace, Location to) {
        boolean sprinting = player.isSprinting();
        boolean suspect = false;
        if (config.sprintDetectionEnabled() && sprinting && !player.getAllowFlight() && knowsSprintStopRules(player)) {
            if (player.getFoodLevel() <= 6) {
                suspect |= sustained(player, id, sprintHungerSince, now,
                        lang.format("alert.sprint_hunger", player.getFoodLevel()), to);
            } else {
                sprintHungerSince.remove(id);
            }
            if (player.hasPotionEffect(PotionEffectType.BLINDNESS)) {
                suspect |= sustained(player, id, sprintBlindSince, now, lang.get("alert.sprint_blind"), to);
            } else {
                sprintBlindSince.remove(id);
            }
        } else {
            sprintHungerSince.remove(id);
            sprintBlindSince.remove(id);
        }

        if (config.sprintOmniDetectionEnabled() && sprinting && player.isOnGround()
                && horizontalDist > 0.15 && !momentumGrace && !player.isInWater() && !iceMomentum(id, now)) {
            double angle = angleFromView(movement, to.getYaw());
            if (angle > config.sprintOmniMaxAngle()) {
                suspect = true;
                int c = consecutiveOmniSprint.merge(id, 1, Integer::sum);
                if (c >= config.sprintOmniViolations()) {
                    consecutiveOmniSprint.remove(id);
                    handleViolation(player, "SPRINT", lang.format("alert.sprint_omni", angle), angle, to, false);
                }
            } else {
                consecutiveOmniSprint.remove(id);
            }
        } else {
            consecutiveOmniSprint.remove(id);
        }
        return suspect;
    }

    /** Time a condition from its first sample; flags once it lasted the minimum duration. */
    private boolean sustained(Player player, UUID id, Map<UUID, Long> since, long now, String details, Location to) {
        long start = since.computeIfAbsent(id, k -> now);
        long held = now - start;
        if (held < config.sprintMinDurationMs()) return true;
        since.put(id, now); // next report only after another full duration
        handleViolation(player, "SPRINT", details, held, to, false);
        return true;
    }

    /** Older clients (via ViaVersion) keep a running sprint on low food and Blindness. */
    private static boolean knowsSprintStopRules(Player player) {
        int protocol = Exemptions.clientProtocol(player);
        return protocol < 0 || protocol >= PROTOCOL_SPRINT_STOP_RULES;
    }

    private boolean iceMomentum(UUID id, long now) {
        double[] ice = recentIce.get(id);
        return ice != null && now - (long) ice[0] < ICE_MOMENTUM_GRACE_MS;
    }

    /** Angle in degrees between horizontal movement and the view direction (yaw). */
    static double angleFromView(Vector movement, float yaw) {
        double len = Math.hypot(movement.getX(), movement.getZ());
        if (len < 1.0e-9) return 0.0;
        double rad = Math.toRadians(yaw);
        double lookX = -Math.sin(rad);
        double lookZ = Math.cos(rad);
        double cos = (movement.getX() * lookX + movement.getZ() * lookZ) / len;
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cos))));
    }

    private void clearSprintState(UUID id) {
        sprintHungerSince.remove(id);
        sprintBlindSince.remove(id);
        consecutiveOmniSprint.remove(id);
    }

    /** Record a position as the new baseline without judging the move that led to it. */
    private void rebaseline(UUID playerId, Location to) {
        rebaseline(playerId, to, true, true);
    }

    /**
     * Restart position tracking at {@code to}.
     *
     * @param legit       also make it the setback target
     * @param resetBudget refill the speed budget (not after a setback: the debt that caused
     *                    it must still count)
     */
    private void rebaseline(UUID playerId, Location to, boolean legit, boolean resetBudget) {
        long now = System.currentTimeMillis();
        lastLocations.put(playerId, to.clone());
        lastMoveTime.put(playerId, now);
        sampleFrom.put(playerId, to.clone());
        sampleAt.put(playerId, now);
        sampleEvents.remove(playerId);
        burstFrom.remove(playerId);
        burstEvents.remove(playerId);
        airWindows.remove(playerId);
        if (resetBudget) speedBudget.remove(playerId);
        if (legit) lastLegitLocations.put(playerId, to.clone());
        // Whatever made this move unjudgeable (grace, exemption, gap, vehicle, flight) also
        // breaks the fall measurement and the sprint timers.
        fallTracker.disturb(playerId, now);
        noFallSpoofSamples.remove(playerId);
        noFallCounted.remove(playerId);
        clearSprintState(playerId);
    }

    /**
     * True while any grace window is active: a legitimate teleport, a join/respawn/world
     * change, the momentum right after gliding/riptiding, or server-applied knockback.
     * Each of these legitimately breaks the movement model for a moment.
     */
    private boolean hasMovementImmunity(Player player, UUID playerId, long now) {
        Long lastTeleportTime = recentTeleport.get(playerId);
        if (lastTeleportTime != null && (now - lastTeleportTime) < TELEPORT_IMMUNITY_MS) {
            if (config.debugMode()) {
                plugin.getLogger().fine("[AC] " + player.getName() + " has teleport immunity, skipping check");
            }
            return true;
        }

        Long lastJoin = recentJoin.get(playerId);
        if (lastJoin != null && (now - lastJoin) < JOIN_GRACE_MS) return true;

        Long flightEnded = flightEndedAirborne.get(playerId);
        if (flightEnded != null) {
            if (flightEndGraceHolds(flightEnded, player.isOnGround(), now)) return true;
            flightEndedAirborne.remove(playerId);
        }

        // After gliding/riptiding the residual momentum would read as a Speed/Fly violation.
        Long graceUntil = momentumGraceUntil.get(playerId);
        if (graceUntil != null && now < graceUntil) return true;

        Long lastKnockback = recentKnockback.get(playerId);
        if (lastKnockback != null && (now - lastKnockback) < KNOCKBACK_IMMUNITY_MS) {
            if (config.debugMode()) {
                plugin.getLogger().fine("[AC] " + player.getName() + " has knockback immunity, skipping check");
            }
            return true;
        }
        return false;
    }

    /**
     * Elytra/Riptide speed-ceiling check.
     *
     * <p>Speed is averaged over a real elapsed window ({@link #ELYTRA_SAMPLE_WINDOW_MS})
     * rather than derived from a single move event. A move event is not reliably one tick:
     * the client may send several position packets per tick, and — the damaging direction —
     * a packet gap while flying over loading chunks delivers one event carrying several ticks
     * of travel. Multiplying such an event by 20 invents speed that was never flown. Dividing
     * by the time that actually passed makes a gap harmless: distance and elapsed time grow
     * together. The window also means the {@code elytra_violations} threshold spans ~0.75s
     * of sustained over-speed, which noise cannot fake.
     */
    private boolean checkElytraSpeed(Player player, UUID playerId, MovementType moveType, Location from, Location to) {
        long now = System.currentTimeMillis();
        Long lastTeleportTime = recentTeleport.get(playerId);
        if (lastTeleportTime != null && (now - lastTeleportTime) < TELEPORT_IMMUNITY_MS) return false;
        Long lastJoin = recentJoin.get(playerId);
        if (lastJoin != null && (now - lastJoin) < JOIN_GRACE_MS) return false;
        if (!from.getWorld().equals(to.getWorld())) return false;
        // Wind charges, TNT boosts and explosions throw a glider far past the gliding ceiling,
        // and every one of them arrives as a velocity (or explosion damage) the knockback grace
        // already records. The window is dropped as well, so the boost never ends up inside a
        // sample measured afterwards.
        Long lastKnockback = recentKnockback.get(playerId);
        if (lastKnockback != null && (now - lastKnockback) < KNOCKBACK_IMMUNITY_MS) {
            elytraSampleFrom.remove(playerId);
            elytraSampleAt.remove(playerId);
            return false;
        }

        Location sampleFrom = elytraSampleFrom.get(playerId);
        Long sampleAt = elytraSampleAt.get(playerId);
        // Open a new window: no baseline yet, a world change, or the previous one is stale
        // (the player stopped flying in between and momentum no longer connects the points).
        if (sampleFrom == null || sampleAt == null || !sampleFrom.getWorld().equals(to.getWorld())
                || now - sampleAt > ELYTRA_SAMPLE_WINDOW_MS * 4) {
            elytraSampleFrom.put(playerId, to.clone());
            elytraSampleAt.put(playerId, now);
            return false;
        }
        long elapsed = now - sampleAt;
        if (elapsed < ELYTRA_SAMPLE_WINDOW_MS) return false; // keep accumulating

        double bps = sampleFrom.distance(to) / (elapsed / 1000.0);
        elytraSampleFrom.put(playerId, to.clone());
        elytraSampleAt.put(playerId, now);

        double max = moveType == MovementType.ELYTRA ? config.elytraMaxSpeed() : config.riptideMaxSpeed();
        Long riptideAt = lastRiptide.get(playerId);
        if (moveType == MovementType.ELYTRA && riptideAt != null && now - riptideAt < RIPTIDE_GRACE_MS) {
            max += config.riptideMaxSpeed();
        }
        max *= CheckMath.pingSlack(effectivePing(player));

        if (bps > max) {
            int c = consecutiveElytra.merge(playerId, 1, Integer::sum);
            if (c >= config.elytraViolations()) {
                boolean elytra = moveType == MovementType.ELYTRA;
                consecutiveElytra.put(playerId, 0);
                return handleViolation(player, elytra ? "ELYTRA" : "RIPTIDE",
                        lang.format(elytra ? "alert.elytra" : "alert.riptide", bps, max), bps, to);
            }
        } else {
            // Decay by one window instead of forgetting the streak: with a full reset, a flight
            // that slows down for one window in every few would never add up to a violation.
            consecutiveElytra.computeIfPresent(playerId, (k, v) -> v > 1 ? v - 1 : null);
        }
        return false;
    }

    /**
     * Handle a confirmed movement violation: log, alert, raise VL and optionally set back.
     *
     * @return true if the player was set back (teleported), so the caller skips updating
     *         the last-known position with the illegal location
     */
    private boolean handleViolation(Player player, String type, String details, double value, Location location) {
        return handleViolation(player, type, details, value, location, true);
    }

    /**
     * @param allowSetback false for checks whose evidence is already in the past (a landing,
     *                     a sprint), where teleporting the player back undoes nothing
     */
    private boolean handleViolation(Player player, String type, String details, double value, Location location,
                                    boolean allowSetback) {
        // Log to database
        if (database != null) {
            database.logAsync(player.getUniqueId(), "anticheat_" + type.toLowerCase(), value,
                    player.getName() + ": " + details + " @ " + CheckMath.formatLocation(location));
        }

        // Use alert manager if available (bundled alerts)
        if (alertManager != null) {
            alertManager.addAlert(player, type, details, value, location);
        } else {
            // Fallback: direct notification (only in debug mode to console)
            if (config.debugMode()) {
                String message = String.format(java.util.Locale.ROOT, "[AntiCheat] %s: %s - %s", player.getName(), type, details);
                plugin.getLogger().warning(message);
            }
        }

        // Raise violation level / run punishments
        if (violationManager != null) {
            violationManager.flag(player, type);
        }

        // Optional setback: teleport the player back to their last clean position.
        // teleportAsync (not teleport): a synchronous teleport is unsupported on Folia's
        // region threads, and this runs inside a PlayerMoveEvent handler. The pending mark is
        // set first because the teleport event may fire before teleportAsync returns.
        if (allowSetback && config.punishmentsSetback()) {
            UUID id = player.getUniqueId();
            Location back = lastLegitLocations.get(id);
            if (back == null) back = lastLocations.get(id);
            if (back != null && back.getWorld() != null) {
                Location target = back.clone();
                pendingSetbacks.put(id, new PendingSetback(target, System.currentTimeMillis()));
                player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
                return true;
            }
        }
        return false;
    }

    /**
     * True when every chunk a block-reading check might touch is already in memory — the
     * player's own chunk and those of the eight blocks around them.
     *
     * <p>One block of margin is enough for all of them: the ground scan offsets the footprint
     * corners by 0.3 (more for a scaled player, but still well under a block), and the
     * sideways tests look exactly one block out. A scan corner reaching into an unloaded chunk
     * would force a synchronous load (forbidden on a Folia region thread) and answer "no
     * block here" for ground that simply has not been read yet.
     */
    private static boolean neighbourhoodLoaded(Location loc) {
        org.bukkit.World world = loc.getWorld();
        if (world == null) return false;
        int minChunkX = (loc.getBlockX() - 1) >> 4;
        int maxChunkX = (loc.getBlockX() + 1) >> 4;
        int minChunkZ = (loc.getBlockZ() - 1) >> 4;
        int maxChunkZ = (loc.getBlockZ() + 1) >> 4;
        // Away from a chunk border all four bounds collapse to the same chunk, so this is a
        // single isChunkLoaded call in the overwhelmingly common case.
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!world.isChunkLoaded(cx, cz)) return false;
            }
        }
        return true;
    }

    /**
     * Distance in blocks from the player's feet down to the nearest thing they could
     * legitimately stand on or hang in, or -1 when nothing supportive lies within
     * {@code maxDepth}. Zero means supported at foot level.
     *
     * <p>Checks all four footprint corners, not just the centre — a sneaking player
     * overhangs an edge by up to half the hitbox width while still being supported. Also
     * checks the block at foot level itself: standing on trapdoors, slabs, carpets or snow
     * layers puts the supporting collision INSIDE that block, not below it.
     *
     * <p>Returning the depth rather than a boolean lets both vertical checks (hover =
     * nothing within 2, GroundSpoof = nothing within 3) share a single scan. Note: a
     * client that spoofs its on-ground flag can still evade the hover check — full
     * prevention needs packet-level checks. Main-thread only.
     */
    private int supportDepth(Player player, int maxDepth) {
        if (player.isClimbing()) return 0;
        return footprintDepth(player, maxDepth, this::isSupportive);
    }

    /**
     * The footprint scan behind {@link #supportDepth}: depth of the first block under any
     * hitbox corner (or the centre) matching {@code match}, or -1 within {@code maxDepth}.
     */
    private int footprintDepth(Player player, int maxDepth, java.util.function.Predicate<org.bukkit.block.Block> match) {
        Location loc = player.getLocation();
        // Hitbox half-width, scaled: the scale attribute resizes the player, and a wider
        // footprint stands on blocks the vanilla-width scan would miss.
        final double half = 0.3 * CheckMath.scale(player);
        final double[] dx = { 0, half, -half, half, -half };
        final double[] dz = { 0, half, half, -half, -half };
        // The feet block covers fractional-Y standing (slabs, trapdoors, carpets); at
        // integer Y the feet sit exactly on the boundary and support is the block below.
        final int feetY = (int) Math.floor(loc.getY());
        for (int k = 0; k <= maxDepth; k++) {
            for (int i = 0; i < dx.length; i++) {
                int bx = (int) Math.floor(loc.getX() + dx[i]);
                int bz = (int) Math.floor(loc.getZ() + dz[i]);
                if (match.test(loc.getWorld().getBlockAt(bx, feetY - k, bz))) return k;
            }
        }
        return -1;
    }

    /**
     * A block counts as support when it has real collision ({@code !isPassable()} covers
     * full blocks, slabs, stairs, trapdoors, carpets, fences, snow layers…) or is one of
     * the collision-free blocks players legitimately stand on or hang in: powder snow
     * (walkable with leather boots), scaffolding tops, cobwebs, climbables and liquids.
     */
    private boolean isSupportive(org.bukkit.block.Block block) {
        Material m = block.getType();
        // Air first: it is by far the most common result of a ground scan, and answering it
        // without the isPassable() call saves a block-data lookup on every sample.
        if (m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR) return false;
        if (m == Material.POWDER_SNOW || m == Material.SCAFFOLDING || m == Material.COBWEB) return true;
        if (m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN) return true;
        if (isClimbableMaterial(m)) return true;
        // Full blocks are settled by the material alone. isPassable() is still needed for
        // everything whose collision depends on its state — trapdoors, gates, doors — but
        // those are a small minority of what a ground scan walks over.
        if (m.isSolid()) return true;
        return !block.isPassable();
    }

    /**
     * Climbable block materials via the game's own tag — a hand-rolled list misses the
     * *_PLANT growth variants (CAVE_VINES_PLANT etc.) and any future climbable.
     */
    private static boolean isClimbableMaterial(Material m) {
        return m.isBlock() && org.bukkit.Tag.CLIMBABLE.isTagged(m);
    }

    /**
      * True when standing on the surface of water with nothing holding the player up.
      * The feet block must be AIR specifically, not merely "not water": a lily pad,
      * a waterlogged slab or any other thin block at foot level is legitimate footing
      * and would otherwise read as walking on water. Entities (a boat floating on the
      * surface) are checked separately for the same reason.
      */
    private boolean isOnWaterSurface(Player player) {
        Location loc = player.getLocation();
        Material at = loc.getBlock().getType();
        Material below = loc.getBlock().getRelative(0, -1, 0).getType();
        if (at != Material.AIR || below != Material.WATER) return false;
        // The centre column alone misreads every edge: a player on a pier, a shoreline block
        // or a one-wide bridge has water under their centre while a hitbox corner rests on
        // the block beside it. Any real footing under the footprint holds them up.
        if (footprintDepth(player, 1, this::isDryFooting) >= 0) return false;
        return !hasEntitySupport(player);
    }

    /** Footing that is not itself liquid — what a player crossing water may stand on. */
    private boolean isDryFooting(org.bukkit.block.Block block) {
        Material m = block.getType();
        if (m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN) return false;
        return isSupportive(block);
    }

    /**
     * True when an entity the player could be standing on sits just below their feet.
     * Boats, minecarts, rideable animals and other players are all solid footing that no
     * block lookup can see. Only called from the would-flag paths — it is a nearby-entity
     * query and too expensive for every movement packet.
     */
    private boolean hasEntitySupport(Player player) {
        double feetY = player.getLocation().getY();
        for (Entity e : player.getNearbyEntities(0.8, 2.0, 0.8)) {
            if (e instanceof org.bukkit.entity.Item || e instanceof org.bukkit.entity.Projectile) continue;
            // Its top surface must be at or just below the player's feet.
            double top = e.getBoundingBox().getMaxY();
            if (top <= feetY + 0.2 && top >= feetY - 2.0) return true;
        }
        return false;
    }

    /** True when the player occupies a climbable block (ladder/vine/scaffolding). */
    private boolean isOnClimbable(Player player) {
        return player.isClimbing() || isClimbableMaterial(player.getLocation().getBlock().getType());
    }

    /** True when a solid block is directly next to the player (feet or head level). */
    private boolean isAgainstWall(Player player) {
        Location loc = player.getLocation();
        int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] o : offsets) {
            if (loc.getBlock().getRelative(o[0], o[1], o[2]).getType().isSolid()) return true;
            if (loc.getBlock().getRelative(o[0], o[1] + 1, o[2]).getType().isSolid()) return true;
        }
        return false;
    }

    /**
     * True when the player is inside a block that slows their descent below the rate the
     * hover check accepts as falling ({@code movement.getY() < -0.08}).
     *
     * <p>Cobwebs and powder snow do physically what Slow Falling does, and Slow Falling is
     * already exempt. {@link #isSupportive} does count both as footing, but
     * {@link #supportDepth} only ever scans DOWNWARD from the feet — a web holding the player
     * at body height above a cave therefore reads as "airborne", while the player sinks too
     * slowly to ever reset the counter. Both halves of the hover signature at once, from
     * standing in a mineshaft.
     */
    static boolean isInFallSlowingBlock(Player player) {
        return isInFallSlowingBlock(player.getLocation());
    }

    /** Location-only form, so the block reasoning can be tested without a player. */
    static boolean isInFallSlowingBlock(Location loc) {
        Material at = loc.getBlock().getType();
        Material above = loc.getBlock().getRelative(0, 1, 0).getType();
        if (at == Material.COBWEB || above == Material.COBWEB
                || at == Material.POWDER_SNOW || above == Material.POWDER_SNOW) {
            return true;
        }
        // Sliding down a honey-block wall is the same situation seen sideways: the block
        // doing the slowing is BESIDE the player, where a downward scan never looks, and the
        // slide is far slower than the rate counted as falling.
        int[][] sides = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] s : sides) {
            if (loc.getBlock().getRelative(s[0], s[1], s[2]).getType() == Material.HONEY_BLOCK) return true;
            if (loc.getBlock().getRelative(s[0], s[1] + 1, s[2]).getType() == Material.HONEY_BLOCK) return true;
        }
        return false;
    }

    private boolean isNearLiquid(Player player) {
        Location loc = player.getLocation();
        // Check current block and 6 adjacent faces (7 checks instead of 27)
        Material current = loc.getBlock().getType();
        if (current == Material.WATER || current == Material.LAVA || current == Material.BUBBLE_COLUMN) return true;

        int[][] offsets = {{0,-1,0}, {0,1,0}, {1,0,0}, {-1,0,0}, {0,0,1}, {0,0,-1}};
        for (int[] o : offsets) {
            Material type = loc.getBlock().getRelative(o[0], o[1], o[2]).getType();
            if (type == Material.WATER || type == Material.LAVA || type == Material.BUBBLE_COLUMN) {
                return true;
            }
        }
        return false;
    }

    /** Ice speed multiplier within 3 blocks below (shared scan, on-foot constants). */
    private double iceMultiplierBelow(Location loc) {
        return CheckMath.iceMultiplierBelow(
                loc, Constants.ICE_SPEED_MULTIPLIER, Constants.BLUE_ICE_SPEED_MULTIPLIER);
    }

    /** Whether a rise now is the rebound of the fast descent recorded at {@code descentAt}. */
    static boolean isBounce(Long descentAt, long now) {
        return descentAt != null && now - descentAt >= 0 && now - descentAt <= BOUNCE_REVERSAL_MS;
    }

    /** True when a slime block is at the feet or up to two blocks below. */
    static boolean isNearSlimeBlock(Location loc) {
        org.bukkit.block.Block feet = loc.getBlock();
        return feet.getType() == Material.SLIME_BLOCK
                || feet.getRelative(0, -1, 0).getType() == Material.SLIME_BLOCK
                || feet.getRelative(0, -2, 0).getType() == Material.SLIME_BLOCK;
    }

    /**
     * True when a bed or shelf mushroom is at the feet or up to two blocks below. The feet
     * block matters: both are lower than a full cube, so a player standing on one has it in
     * their own block position.
     */
    static boolean isNearBedBounceBlock(Location loc) {
        org.bukkit.block.Block feet = loc.getBlock();
        return GameCompat.isBedLikeBounceBlock(feet.getType())
                || GameCompat.isBedLikeBounceBlock(feet.getRelative(0, -1, 0).getType())
                || GameCompat.isBedLikeBounceBlock(feet.getRelative(0, -2, 0).getType());
    }

    /**
     * True when a sulfur cube (26.2, a bouncy mob) is just below the player. Only asked while
     * rising, which is when a bounce shows, and only on servers that have the mob.
     */
    private boolean isOnBouncyEntity(Player player) {
        if (!SULFUR_CUBE_EXISTS) return false;
        double feetY = player.getLocation().getY();
        for (Entity e : player.getNearbyEntities(1.0, 2.5, 1.0)) {
            if (!GameCompat.isType(e, GameCompat.SULFUR_CUBE)) continue;
            double top = e.getBoundingBox().getMaxY();
            if (top <= feetY + 0.5 && top >= feetY - 2.5) return true;
        }
        return false;
    }

    private static final boolean SULFUR_CUBE_EXISTS = entityTypeExists(GameCompat.SULFUR_CUBE);

    private static boolean entityTypeExists(String name) {
        try {
            for (EntityType t : EntityType.values()) {
                if (t.name().equals(name)) return true;
            }
        } catch (Throwable ignored) {
            // type listing unavailable
        }
        return false;
    }

    /**
     * True when the player is inside or above a potent-sulfur geyser (26.2): a water column
     * standing on potent sulfur pushes everything above it upwards, up to about twenty blocks
     * high. Scans down through air and water only, so on ordinary ground it stops at the
     * first block below the feet.
     */
    static boolean isInGeyser(Location loc) {
        return isInGeyser(loc, true);
    }

    /**
     * {@link #isInGeyser(Location)} with a cheap pre-filter: the scan down only runs on servers
     * that have potent sulfur, and only while the player rises or has water at or directly
     * below the feet — a geyser pushes upwards, and its column is water.
     */
    static boolean isInGeyser(Location loc, boolean rising) {
        if (GameCompat.POTENT_SULFUR == null) return false;
        if (!geyserScanWorthwhile(loc, rising)) return false;
        return scanGeyser(loc);
    }

    /** The pre-filter of {@link #isInGeyser(Location, boolean)}, independent of the server version. */
    static boolean geyserScanWorthwhile(Location loc, boolean rising) {
        if (rising) return true;
        org.bukkit.block.Block feet = loc.getBlock();
        return isWaterColumn(feet.getType()) || isWaterColumn(feet.getRelative(0, -1, 0).getType());
    }

    private static boolean isWaterColumn(Material m) {
        return m == Material.WATER || m == Material.BUBBLE_COLUMN;
    }

    /** One move event's geyser lookup, computed at most once and only when asked. */
    static final class GeyserProbe {
        private final Location to;
        private final boolean rising;
        private int state; // 0 not computed, 1 no, 2 yes

        GeyserProbe(Location to, boolean rising) {
            this.to = to;
            this.rising = rising;
        }

        boolean get() {
            if (state == 0) state = isInGeyser(to, rising) ? 2 : 1;
            return state == 2;
        }
    }

    private static boolean scanGeyser(Location loc) {
        org.bukkit.block.Block b = loc.getBlock();
        int water = 0;
        for (int i = 0; i <= GEYSER_SCAN_DEPTH; i++) {
            Material m = b.getRelative(0, -i, 0).getType();
            if (m == Material.WATER || m == Material.BUBBLE_COLUMN) {
                water++;
            } else if (m == GameCompat.POTENT_SULFUR) {
                return water > 0;
            } else if (!m.isAir()) {
                return false;
            }
        }
        return false;
    }

    /**
     * Check if player is in or near a bubble column.
     */
    private boolean isNearBubbleColumn(Location loc) {
        Material at = loc.getBlock().getType();
        Material below = loc.getBlock().getRelative(0, -1, 0).getType();
        Material above = loc.getBlock().getRelative(0, 1, 0).getType();
        return at == Material.BUBBLE_COLUMN || below == Material.BUBBLE_COLUMN || above == Material.BUBBLE_COLUMN;
    }

    /**
     * Cleanup player data on disconnect to prevent memory leaks.
     * Removes all tracking data associated with the player.
     *
     * @param playerId UUID of the player to cleanup
     */
    public void cleanup(UUID playerId) {
        lastLocations.remove(playerId);
        lastMoveTime.remove(playerId);
        lastLegitLocations.remove(playerId);
        pendingSetbacks.remove(playerId);
        sampleFrom.remove(playerId);
        sampleAt.remove(playerId);
        sampleEvents.remove(playerId);
        burstFrom.remove(playerId);
        burstEvents.remove(playerId);
        speedBudget.remove(playerId);
        airWindows.remove(playerId);
        wasFlying.remove(playerId);
        consecutiveSpeedViolations.remove(playerId);
        consecutiveFlyViolations.remove(playerId);
        consecutiveHoverTicks.remove(playerId);
        hoverStartDy.remove(playerId);
        consecutiveGroundSpoof.remove(playerId);
        consecutiveNoSlow.remove(playerId);
        consecutiveJesus.remove(playerId);
        consecutiveSpider.remove(playerId);
        consecutiveStep.remove(playerId);
        consecutiveElytra.remove(playerId);
        elytraSampleFrom.remove(playerId);
        elytraSampleAt.remove(playerId);
        lastRiptide.remove(playerId);
        lastAscentDy.remove(playerId);
        consecutiveAscent.remove(playerId);
        recentKnockback.remove(playerId);
        recentTeleport.remove(playerId);
        recentJoin.remove(playerId);
        momentumGraceUntil.remove(playerId);
        flightEndedAirborne.remove(playerId);
        recentSlime.remove(playerId);
        recentBounce.remove(playerId);
        lastFastDescent.remove(playerId);
        recentPillar.remove(playerId);
        recentIce.remove(playerId);
        lungeCredit.remove(playerId);
        pendingLandings.remove(playerId);
        lastFallDamage.remove(playerId);
        noFallStreak.remove(playerId);
        noFallSpoofSamples.remove(playerId);
        noFallCounted.remove(playerId);
        clearSprintState(playerId);
        fallTracker.cleanup(playerId);
    }

    /**
     * Check if player is whitelisted (UUID or LuckPerms group).
     */
    public boolean isPlayerWhitelisted(Player player) {
        // Bedrock (Geyser/Floodgate) and legacy (ViaVersion) clients use different physics
        if (Exemptions.isBedrockExempt(player, config, geyser)) return true;
        if (Exemptions.isLegacyExempt(player, config)) return true;

        // Check UUID whitelist
        if (config.isWhitelistedPlayer(player.getUniqueId())) {
            return true;
        }

        // Check LuckPerms group whitelist
        if (luckPerms != null) {
            List<String> whitelistGroups = config.anticheatWhitelistGroups();
            if (luckPerms.isPlayerInWhitelistedGroup(player, whitelistGroups)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Movement types for detection.
     * Each type has its own speed threshold.
     */
    public enum MovementType {
        WALKING,
        SPRINTING,
        SNEAKING,
        SWIMMING,
        CLIMBING,
        RIDING_HORSE,
        RIDING_DONKEY,
        RIDING_LLAMA,
        RIDING_CAMEL,
        RIDING_PIG,
        RIDING_STRIDER,
        BOAT,
        MINECART,
        ELYTRA,
        RIPTIDE,
        CREATIVE_FLY,
        OTHER_VEHICLE
    }
}
