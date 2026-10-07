package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.integration.GeyserHook;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.lang.LanguageManager;
import dev.boondock.bsanticheat.util.CheckMath;
import dev.boondock.bsanticheat.util.Constants;
import dev.boondock.bsanticheat.util.GameCompat;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Camel;
import org.bukkit.entity.Donkey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Horse;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Mule;
import org.bukkit.entity.Pig;
import org.bukkit.entity.SkeletonHorse;
import org.bukkit.entity.Player;
import org.bukkit.entity.Strider;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.ZombieHorse;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vehicle-based movement checks. PlayerMoveEvent does not fire while a player rides a
 * vehicle, so Boat-Fly and vehicle speed cheats were invisible to MovementChecker —
 * this listener closes that gap via VehicleMoveEvent.
 *
 * <ul>
 *   <li>BOATFLY — a ridden boat staying airborne without falling across consecutive
 *       samples (vanilla boats always fall when nothing supports them).</li>
 *   <li>VEHICLE_SPEED — a ridden vehicle exceeding its per-type speed ceiling
 *       (Constants, blocks per second; ice multipliers for boats).</li>
 * </ul>
 *
 * Note: a boat hovering perfectly still fires no move events and is not detected —
 * catching that needs packet-level checks. All practical boat-fly movement is covered.
 */
public class VehicleChecker implements Listener {

    private final Plugin plugin;
    private final PluginConfig config;
    private final DatabaseManager database;
    private final LanguageManager lang;
    private LuckPermsHook luckPerms;
    private GeyserHook geyser;
    private MovementAlertManager alertManager;
    private ViolationManager violationManager;
    private TransactionManager transactionManager;

    // Keyed by the riding player's UUID (cleaned up on quit)
    private final Map<UUID, Integer> consecutiveBoatFly = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> consecutiveVehicleSpeed = new ConcurrentHashMap<>();
    // Ice-momentum memory for boats: [0]=timestamp(ms), [1]=ice speed multiplier. A boat
    // at ice speed lifts off briefly over every bump/gap in the road — without the memory
    // the allowance would collapse to the base speed mid-hop while the momentum persists.
    private final Map<UUID, double[]> recentIce = new ConcurrentHashMap<>();
    private static final long ICE_MOMENTUM_GRACE_MS = 3000;
    // Start of the current speed sample. Speed is measured across a fixed span of real time
    // instead of per move event, for the reason documented on the elytra check: a move event
    // is not reliably one tick, and a packet gap while riding over loading chunks delivers a
    // single event carrying several ticks of travel. Multiplying that by 20 invents speed
    // that was never driven. Dividing by the time that actually passed makes a gap harmless,
    // because distance and elapsed time grow together.
    private final Map<UUID, Location> speedSampleFrom = new ConcurrentHashMap<>();
    private final Map<UUID, Long> speedSampleAt = new ConcurrentHashMap<>();
    private static final long SPEED_SAMPLE_WINDOW_MS = 250;
    // A single vehicle move larger than this is a teleport (Multiverse portal, /tp of a
    // ridden horse, a plugin repositioning the boat), not movement. Vanilla vehicles stay
    // far below one block per tick; the elytra-class speeds do not apply to vehicles.
    private static final double VEHICLE_TELEPORT_DISTANCE = 8.0;
    // Nautilus dash credit per rider: [0] = blocks available, [1] = last refill (ms).
    private final Map<UUID, double[]> dashCredit = new ConcurrentHashMap<>();

    public VehicleChecker(Plugin plugin, PluginConfig config, DatabaseManager database, LanguageManager lang) {
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

    /** Round-trip latency (ms) for lag compensation (see {@link dev.boondock.bsanticheat.util.CheckMath}). */
    private int effectivePing(Player player) {
        return dev.boondock.bsanticheat.util.CheckMath.effectivePing(transactionManager, player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleMove(VehicleMoveEvent event) {
        if (!config.vehicleChecksEnabled()) return;

        Vehicle vehicle = event.getVehicle();
        Player player = null;
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof Player p) {
                player = p;
                break;
            }
        }
        // Riderless vehicles (dispenser boats, mob-ridden) are not our problem
        if (player == null) return;

        UUID playerId = player.getUniqueId();
        Location from = event.getFrom();
        Location to = event.getTo();
        if (!from.getWorld().equals(to.getWorld())) {
            boatAir.remove(playerId);
            return;
        }

        if (Exemptions.isExempt(player, config, luckPerms, geyser)) {
            boatAir.remove(playerId);
            return;
        }
        if (ServerLoad.isLagging(config, player)) {
            consecutiveBoatFly.remove(playerId);
            consecutiveVehicleSpeed.remove(playerId);
            boatAir.remove(playerId);
            resetSpeedSample(playerId);
            return;
        }

        // A teleport is not a speed violation. VehicleMoveEvent has no teleport flag, so
        // an implausible single-tick jump is treated as one: reset the streaks and skip.
        if (from.distance(to) > VEHICLE_TELEPORT_DISTANCE) {
            consecutiveBoatFly.remove(playerId);
            consecutiveVehicleSpeed.remove(playerId);
            boatAir.remove(playerId);
            resetSpeedSample(playerId);
            return;
        }

        // Ice contact memory for boats (used by both the speed ceiling and Boat-Fly:
        // a ramp launch off blue ice keeps a legit boat rising/airborne for a while).
        double iceMult = 1.0;
        if (vehicle instanceof Boat) {
            iceMult = iceMultiplierBelow(to);
            long nowMs = System.currentTimeMillis();
            if (iceMult > 1.0) {
                recentIce.put(playerId, new double[]{nowMs, iceMult});
            } else {
                double[] lastIce = recentIce.get(playerId);
                if (lastIce != null && nowMs - (long) lastIce[0] < ICE_MOMENTUM_GRACE_MS) {
                    iceMult = lastIce[1];
                }
            }
        }
        boolean iceMomentum = iceMult > 1.0;

        // --- Boat-Fly: boat stays airborne without falling ---
        // Boats only: flying mounts (happy ghast) legitimately stay airborne.
        if (vehicle instanceof Boat) {
            double dy = to.getY() - from.getY();
            boolean airborne = !iceMomentum && isAirborne(to);
            if (dy > -0.01 && airborne) {
                int c = consecutiveBoatFly.merge(playerId, 1, Integer::sum);
                if (c >= config.boatFlyViolations()) {
                    handleViolation(player, "BOATFLY", lang.format("alert.boatfly", c), dy, to);
                    consecutiveBoatFly.put(playerId, 0);
                    boatAir.remove(playerId);
                }
            } else {
                consecutiveBoatFly.remove(playerId);
            }
            checkBoatGravity(player, playerId, from, to, airborne);
        } else {
            boatAir.remove(playerId);
        }

        // --- Vehicle speed, averaged over a real elapsed window (see the field comment) ---
        long now = System.currentTimeMillis();
        Location sampleFrom = speedSampleFrom.get(playerId);
        Long sampleAt = speedSampleAt.get(playerId);
        // Open a new window: no baseline yet, a world change, or a stale one (the player
        // stopped riding in between, so the two points are not connected by one journey).
        if (sampleFrom == null || sampleAt == null || sampleFrom.getWorld() == null
                || !sampleFrom.getWorld().equals(to.getWorld())
                || now - sampleAt > SPEED_SAMPLE_WINDOW_MS * 4) {
            speedSampleFrom.put(playerId, to.clone());
            speedSampleAt.put(playerId, now);
            return;
        }
        long elapsed = now - sampleAt;
        if (elapsed < SPEED_SAMPLE_WINDOW_MS) return; // keep accumulating

        double distance = sampleFrom.distance(to);
        double bps = distance / (elapsed / 1000.0);
        speedSampleFrom.put(playerId, to.clone());
        speedSampleAt.put(playerId, now);

        double max = maxSpeedFor(vehicle, iceMult)
                * dev.boondock.bsanticheat.util.CheckMath.pingSlack(effectivePing(player));

        boolean overSpeed = bps > max;
        if (overSpeed && GameCompat.isNautilus(vehicle)) {
            // The dash covers the excess while its credit lasts.
            double excess = distance - max * (elapsed / 1000.0);
            overSpeed = !drawDashCredit(playerId, excess, now);
        }

        if (overSpeed) {
            int c = consecutiveVehicleSpeed.merge(playerId, 1, Integer::sum);
            if (c >= config.vehicleSpeedViolations()) {
                handleViolation(player, "VEHICLE_SPEED",
                        lang.format("alert.vehicle_speed", vehicle.getType().name(), bps, max), bps, to);
                consecutiveVehicleSpeed.put(playerId, 0);
            }
        } else {
            // Decay by one window instead of forgetting the streak, so an over-speed that
            // pauses for a single window every few windows still adds up.
            consecutiveVehicleSpeed.computeIfPresent(playerId, (k, v) -> v > 1 ? v - 1 : null);
        }
    }

    // ==================== Boat gravity ====================

    // Boat physics in the air: 0.04 b/t of gravity per tick. The expected rise is taken as the
    // larger of the undamped model and one with living-entity drag (0.98): undamped rises
    // higher, damped falls slower, and the bound has to be lenient in both directions.
    private static final double BOAT_GRAVITY = 0.04;
    private static final double BOAT_DRAG = 0.98;
    // Highest vertical speed a boat is assumed to leave the ground with when nothing more is
    // known. A boat launched faster than this (bubble column, explosion) shows the launch in
    // its first airborne sample, which then becomes the starting speed of the window instead.
    private static final double BOAT_LAUNCH_SPEED = 0.2;
    // Airborne time before the window is judged, and the leeway on the expected fall.
    private static final double BOAT_MIN_AIR_TICKS = 20.0;
    private static final double BOAT_FALL_TOLERANCE = 1.0;

    /** One uninterrupted airborne stretch of a ridden boat. */
    private static final class AirWindow {
        final double startY;
        final long startAt;
        final double v0;
        int events;
        long lastAt;

        AirWindow(double startY, long startAt, double v0) {
            this.startY = startY;
            this.startAt = startAt;
            this.v0 = v0;
        }
    }

    private final Map<UUID, AirWindow> boatAir = new ConcurrentHashMap<>();

    /**
     * Boat-Fly that keeps moving vertically. The per-sample streak above only counts samples
     * that do not sink at all, so a boat sinking by a few hundredths per tick, or bobbing up
     * and down around one height, glides forever. Here the whole airborne stretch is compared
     * with free fall instead: after {@link #BOAT_MIN_AIR_TICKS} ticks without support a boat
     * must have dropped at least as far as gravity drags it, starting from the fastest
     * plausible upward speed.
     *
     * <p>Time is the smaller of real elapsed ticks and the number of move events: a
     * connection stall stretches the arrival times without adding any flight time, and
     * judging a stretched window against a longer fall would flag a laggy player.
     */
    void checkBoatGravity(Player player, UUID playerId, Location from, Location to, boolean airborne) {
        if (!airborne) {
            boatAir.remove(playerId);
            return;
        }
        long now = System.currentTimeMillis();
        AirWindow w = boatAir.get(playerId);
        // A silence longer than a packet gap (dismounted, stalled connection) disconnects the
        // stretch: the stored start height no longer belongs to this flight.
        if (w != null && now - w.lastAt > Constants.MOVEMENT_MAX_GAP_MS) w = null;
        if (w == null) {
            double firstDy = to.getY() - from.getY();
            w = new AirWindow(from.getY(), now - 50L, Math.max(BOAT_LAUNCH_SPEED, firstDy));
            boatAir.put(playerId, w);
        }
        w.events++;
        w.lastAt = now;
        double ticks = Math.min((now - w.startAt) / 50.0, w.events);
        if (ticks < BOAT_MIN_AIR_TICKS) return;

        double rise = to.getY() - w.startY;
        double allowed = Math.max(
                CheckMath.ballisticRise(w.v0, BOAT_GRAVITY, 1.0, ticks),
                CheckMath.ballisticRise(w.v0, BOAT_GRAVITY, BOAT_DRAG, ticks)) + BOAT_FALL_TOLERANCE;
        if (rise <= allowed) return;

        boatAir.remove(playerId);
        consecutiveBoatFly.remove(playerId);
        handleViolation(player, "BOATFLY", lang.format("alert.boatfly", w.events), rise, to);
    }

    /**
     * True when nothing supports the vehicle: no collidable block (isPassable covers
     * trapdoors, slabs, carpets and fences that isSolid misjudges) and no liquid/bubble
     * column in its own block or the two blocks below (waterlogged blocks and wave
     * bobbing on the water surface therefore never trigger it).
     */
    private boolean isAirborne(Location loc) {
        Block block = loc.getBlock();
        for (int i = 0; i <= 2; i++) {
            Block b = block.getRelative(0, -i, 0);
            Material m = b.getType();
            if (!b.isPassable() || m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN) {
                return false;
            }
        }
        return true;
    }

    /** Drop the speed baseline, so the next move opens a fresh window instead of measuring
     *  across a teleport or a lag pause. */
    private void resetSpeedSample(UUID playerId) {
        speedSampleFrom.remove(playerId);
        speedSampleAt.remove(playerId);
    }

    /** Ice multiplier within 3 blocks below the boat (shared scan, vehicle constants). */
    private double iceMultiplierBelow(Location to) {
        return dev.boondock.bsanticheat.util.CheckMath.iceMultiplierBelow(
                to, Constants.VEHICLE_ICE_SPEED_MULTIPLIER, Constants.VEHICLE_BLUE_ICE_SPEED_MULTIPLIER);
    }

    /**
     * Take {@code excess} blocks from the rider's nautilus dash credit, which refills at one
     * dash ({@link Constants#NAUTILUS_DASH_BLOCKS}) per {@link Constants#NAUTILUS_DASH_INTERVAL_MS}
     * and holds at most one dash.
     *
     * @return true when the credit covered the excess
     */
    boolean drawDashCredit(UUID playerId, double excess, long now) {
        double cap = Constants.NAUTILUS_DASH_BLOCKS;
        double[] c = dashCredit.computeIfAbsent(playerId, k -> new double[]{cap, now});
        double refill = (now - c[1]) * cap / Constants.NAUTILUS_DASH_INTERVAL_MS;
        c[0] = Math.min(cap, c[0] + Math.max(0.0, refill));
        c[1] = now;
        if (excess <= c[0]) {
            c[0] -= Math.max(0.0, excess);
            return true;
        }
        c[0] = 0.0;
        return false;
    }

    /** Per-type speed ceiling in blocks per second, with the (remembered) boat ice multiplier. */
    double maxSpeedFor(Vehicle vehicle, double iceMult) {
        // A Speed potion on the mount is legitimate and raises its ceiling; without this
        // a fast horse under Speed II blows past the flat limit.
        double potion = 1.0;
        double cap = baseMaxSpeedFor(vehicle, iceMult);
        if (vehicle instanceof LivingEntity le) {
            if (le.hasPotionEffect(PotionEffectType.SPEED)) {
                var eff = le.getPotionEffect(PotionEffectType.SPEED);
                if (eff != null) potion += Constants.SPEED_POTION_MULTIPLIER_PER_LEVEL * (eff.getAmplifier() + 1);
            }
            // The mount's own speed attribute over its base value covers every modifier
            // (potions, items, plugins); the larger of the two is used, never both.
            Attribute speedAttr = vehicle instanceof HappyGhast ? Attribute.FLYING_SPEED : Attribute.MOVEMENT_SPEED;
            cap *= Math.max(potion, modifierRatio(le, speedAttr));
            // Land mounts that are steered: a raised movement_speed (plugin horses) lifts the
            // flat ceiling. Never lowers it.
            if (isSteeredLandMount(vehicle)) {
                double attr = attributeValue(le, Attribute.MOVEMENT_SPEED);
                cap = Math.max(cap, attr * Constants.MOUNT_BPS_PER_SPEED_UNIT * Constants.MOUNT_ATTRIBUTE_MARGIN);
            }
            return cap;
        }
        return cap * potion;
    }

    private static boolean isSteeredLandMount(Vehicle vehicle) {
        return vehicle instanceof Horse || vehicle instanceof ZombieHorse || vehicle instanceof SkeletonHorse
                || vehicle instanceof Donkey || vehicle instanceof Mule || vehicle instanceof Camel;
    }

    /** Attribute value over base value (≥ 1), or 1 when unavailable. */
    private static double modifierRatio(LivingEntity entity, Attribute attribute) {
        try {
            AttributeInstance inst = entity.getAttribute(attribute);
            if (inst == null || inst.getBaseValue() <= 0) return 1.0;
            return Math.max(1.0, inst.getValue() / inst.getBaseValue());
        } catch (Throwable t) {
            return 1.0;
        }
    }

    private static double attributeValue(LivingEntity entity, Attribute attribute) {
        try {
            AttributeInstance inst = entity.getAttribute(attribute);
            return inst == null ? 0.0 : inst.getValue();
        } catch (Throwable t) {
            return 0.0;
        }
    }

    double baseMaxSpeedFor(Vehicle vehicle, double iceMult) {
        if (vehicle instanceof Boat) {
            return Constants.BOAT_MAX_SPEED * iceMult;
        }
        if (vehicle instanceof Minecart) return Constants.MINECART_MAX_SPEED;
        // Mounts newer than the compile API, by type name.
        double byName = maxSpeedByTypeName(GameCompat.typeName(vehicle));
        if (byName > 0) return byName;
        if (vehicle instanceof Horse || vehicle instanceof ZombieHorse || vehicle instanceof SkeletonHorse) {
            return Constants.HORSE_MAX_SPEED;
        }
        if (vehicle instanceof Donkey || vehicle instanceof Mule) return Constants.DONKEY_MAX_SPEED;
        if (vehicle instanceof Llama) return Constants.LLAMA_MAX_SPEED;
        if (vehicle instanceof Camel) return Constants.CAMEL_MAX_SPEED;
        if (vehicle instanceof Pig) return Constants.PIG_MAX_SPEED;
        if (vehicle instanceof Strider) return Constants.STRIDER_MAX_SPEED;
        return Constants.OTHER_VEHICLE_MAX_SPEED;
    }

    /**
     * Ceiling for mount types identified by name (happy ghast, nautilus, zombie nautilus),
     * or 0 when the name is not one of them. Camel husks are Camels and handled by type.
     */
    static double maxSpeedByTypeName(String typeName) {
        return switch (typeName) {
            case GameCompat.HAPPY_GHAST -> Constants.HAPPY_GHAST_MAX_SPEED;
            case GameCompat.NAUTILUS, GameCompat.ZOMBIE_NAUTILUS -> Constants.NAUTILUS_MAX_SPEED;
            default -> 0.0;
        };
    }

    private void handleViolation(Player player, String type, String details, double value, Location location) {
        if (database != null) {
            database.logAsync(player.getUniqueId(), "anticheat_" + type.toLowerCase(), value,
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
        consecutiveBoatFly.remove(playerId);
        consecutiveVehicleSpeed.remove(playerId);
        recentIce.remove(playerId);
        boatAir.remove(playerId);
        dashCredit.remove(playerId);
        resetSpeedSample(playerId);
    }
}
