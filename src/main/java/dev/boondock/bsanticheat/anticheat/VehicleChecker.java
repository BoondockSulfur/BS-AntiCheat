package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.lang.LanguageManager;
import dev.boondock.bsanticheat.util.Constants;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Camel;
import org.bukkit.entity.Donkey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
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
    private MovementAlertManager alertManager;
    private ViolationManager violationManager;

    // Keyed by the riding player's UUID (cleaned up on quit)
    private final Map<UUID, Integer> consecutiveBoatFly = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> consecutiveVehicleSpeed = new ConcurrentHashMap<>();

    public VehicleChecker(Plugin plugin, PluginConfig config, DatabaseManager database, LanguageManager lang) {
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

        if (Exemptions.isExempt(player, config, luckPerms)) return;
        if (ServerLoad.isLagging(config)) {
            consecutiveBoatFly.remove(playerId);
            consecutiveVehicleSpeed.remove(playerId);
            return;
        }

        // --- Boat-Fly: boat stays airborne without falling ---
        if (vehicle instanceof Boat) {
            double dy = to.getY() - from.getY();
            if (dy > -0.01 && isAirborne(to)) {
                int c = consecutiveBoatFly.merge(playerId, 1, Integer::sum);
                if (c >= Constants.BOATFLY_VIOLATIONS) {
                    handleViolation(player, "BOATFLY", lang.format("alert.boatfly", c), dy, to);
                    consecutiveBoatFly.put(playerId, 0);
                }
            } else {
                consecutiveBoatFly.remove(playerId);
            }
        }

        // --- Vehicle speed: per-event distance is ~one tick of movement → ×20 = b/s ---
        double bps = from.distance(to) * 20.0;
        double max = maxSpeedFor(vehicle, to);
        int ping = player.getPing();
        if (ping > 100) {
            max *= 1.0 + (Math.sqrt(ping - 100) / 100.0);
        }

        if (bps > max) {
            int c = consecutiveVehicleSpeed.merge(playerId, 1, Integer::sum);
            if (c >= Constants.VEHICLE_SPEED_VIOLATIONS) {
                handleViolation(player, "VEHICLE_SPEED",
                        lang.format("alert.vehicle_speed", vehicle.getType().name(), bps, max), bps, to);
                consecutiveVehicleSpeed.put(playerId, 0);
            }
        } else {
            consecutiveVehicleSpeed.remove(playerId);
        }
    }

    /**
     * True when nothing supports the vehicle: no solid block and no liquid/bubble column
     * in its own block or the two blocks below (slabs, waterlogged blocks and wave
     * bobbing on the water surface therefore never trigger it).
     */
    private boolean isAirborne(Location loc) {
        Block block = loc.getBlock();
        for (int i = 0; i <= 2; i++) {
            Material m = block.getRelative(0, -i, 0).getType();
            if (m.isSolid() || m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN) {
                return false;
            }
        }
        return true;
    }

    /** Per-type speed ceiling in blocks per second, with ice multipliers for boats. */
    private double maxSpeedFor(Vehicle vehicle, Location to) {
        if (vehicle instanceof Boat) {
            double max = Constants.BOAT_MAX_SPEED;
            Material below = to.getBlock().getRelative(0, -1, 0).getType();
            if (below == Material.BLUE_ICE) {
                max *= Constants.VEHICLE_BLUE_ICE_SPEED_MULTIPLIER;
            } else if (below == Material.ICE || below == Material.PACKED_ICE || below == Material.FROSTED_ICE) {
                max *= Constants.VEHICLE_ICE_SPEED_MULTIPLIER;
            }
            return max;
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
            database.logAsync("anticheat_" + type.toLowerCase(), value, player.getName() + ": " + details);
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
    }
}
