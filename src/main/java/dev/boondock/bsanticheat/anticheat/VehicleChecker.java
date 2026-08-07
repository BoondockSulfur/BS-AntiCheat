package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.integration.GeyserHook;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.lang.LanguageManager;
import dev.boondock.bsanticheat.util.CheckMath;
import dev.boondock.bsanticheat.util.Constants;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Camel;
import org.bukkit.entity.Donkey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Mule;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.entity.Strider;
import org.bukkit.entity.Vehicle;
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
    // A single vehicle move larger than this is a teleport (Multiverse portal, /tp of a
    // ridden horse, a plugin repositioning the boat), not movement. Vanilla vehicles stay
    // far below one block per tick; the elytra-class speeds do not apply to vehicles.
    private static final double VEHICLE_TELEPORT_DISTANCE = 8.0;

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
        if (!from.getWorld().equals(to.getWorld())) return;

        if (Exemptions.isExempt(player, config, luckPerms, geyser)) return;
        if (ServerLoad.isLagging(config)) {
            consecutiveBoatFly.remove(playerId);
            consecutiveVehicleSpeed.remove(playerId);
            return;
        }

        // A teleport is not a speed violation. VehicleMoveEvent has no teleport flag, so
        // an implausible single-tick jump is treated as one: reset the streaks and skip.
        if (from.distance(to) > VEHICLE_TELEPORT_DISTANCE) {
            consecutiveBoatFly.remove(playerId);
            consecutiveVehicleSpeed.remove(playerId);
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
        if (vehicle instanceof Boat) {
            double dy = to.getY() - from.getY();
            if (dy > -0.01 && !iceMomentum && isAirborne(to)) {
                int c = consecutiveBoatFly.merge(playerId, 1, Integer::sum);
                if (c >= config.boatFlyViolations()) {
                    handleViolation(player, "BOATFLY", lang.format("alert.boatfly", c), dy, to);
                    consecutiveBoatFly.put(playerId, 0);
                }
            } else {
                consecutiveBoatFly.remove(playerId);
            }
        }

        // --- Vehicle speed: per-event distance is ~one tick of movement → ×20 = b/s ---
        double bps = from.distance(to) * 20.0;
        double max = maxSpeedFor(vehicle, iceMult)
                * dev.boondock.bsanticheat.util.CheckMath.pingSlack(effectivePing(player));

        if (bps > max) {
            int c = consecutiveVehicleSpeed.merge(playerId, 1, Integer::sum);
            if (c >= config.vehicleSpeedViolations()) {
                handleViolation(player, "VEHICLE_SPEED",
                        lang.format("alert.vehicle_speed", vehicle.getType().name(), bps, max), bps, to);
                consecutiveVehicleSpeed.put(playerId, 0);
            }
        } else {
            consecutiveVehicleSpeed.remove(playerId);
        }
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

    /** Ice multiplier within 3 blocks below the boat (shared scan, vehicle constants). */
    private double iceMultiplierBelow(Location to) {
        return dev.boondock.bsanticheat.util.CheckMath.iceMultiplierBelow(
                to, Constants.VEHICLE_ICE_SPEED_MULTIPLIER, Constants.VEHICLE_BLUE_ICE_SPEED_MULTIPLIER);
    }

    /** Per-type speed ceiling in blocks per second, with the (remembered) boat ice multiplier. */
    private double maxSpeedFor(Vehicle vehicle, double iceMult) {
        // A Speed potion on the mount is legitimate and raises its ceiling; without this
        // a fast horse under Speed II blows past the flat limit.
        double potion = 1.0;
        if (vehicle instanceof LivingEntity le && le.hasPotionEffect(PotionEffectType.SPEED)) {
            var eff = le.getPotionEffect(PotionEffectType.SPEED);
            if (eff != null) potion += Constants.SPEED_POTION_MULTIPLIER_PER_LEVEL * (eff.getAmplifier() + 1);
        }
        return baseMaxSpeedFor(vehicle, iceMult) * potion;
    }

    private double baseMaxSpeedFor(Vehicle vehicle, double iceMult) {
        if (vehicle instanceof Boat) {
            return Constants.BOAT_MAX_SPEED * iceMult;
        }
        if (vehicle instanceof Minecart) return Constants.MINECART_MAX_SPEED;
        if (vehicle instanceof Horse) return Constants.HORSE_MAX_SPEED;
        if (vehicle instanceof Donkey || vehicle instanceof Mule) return Constants.DONKEY_MAX_SPEED;
        if (vehicle instanceof Llama) return Constants.LLAMA_MAX_SPEED;
        if (vehicle instanceof Camel) return Constants.CAMEL_MAX_SPEED;
        if (vehicle instanceof Pig) return Constants.PIG_MAX_SPEED;
        if (vehicle instanceof Strider) return Constants.STRIDER_MAX_SPEED;
        return Constants.OTHER_VEHICLE_MAX_SPEED;
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
        consecutiveBoatFly.remove(playerId);
        consecutiveVehicleSpeed.remove(playerId);
        recentIce.remove(playerId);
    }
}
