package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.integration.GeyserHook;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.lang.LanguageManager;
import dev.boondock.bsanticheat.util.Constants;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * World-interaction checks: Nuker / FastBreak (breaking too many blocks per second) and
 * FastPlace (placing too many blocks per second).
 *
 * <p>Event-based and rate-only, so thresholds are generous to avoid false positives from
 * legitimate fast mining (Efficiency + Haste on instamine blocks). Creative is exempt.
 */
public class WorldChecker implements Listener {

    private static final long WINDOW_MS = 1000L;

    private final Plugin plugin;
    private final PluginConfig config;
    private final DatabaseManager database;
    private final LanguageManager lang;
    private LuckPermsHook luckPerms;
    private GeyserHook geyser;
    private MovementAlertManager alertManager;
    private ViolationManager violationManager;

    private final Map<UUID, ConcurrentLinkedDeque<Long>> breaks = new ConcurrentHashMap<>();
    private final Map<UUID, ConcurrentLinkedDeque<Long>> places = new ConcurrentHashMap<>();
    // Scaffold: consecutive places made while not looking at the block
    private final Map<UUID, Integer> consecutiveScaffold = new ConcurrentHashMap<>();
    // FastBreak: when the player started digging which block
    private final Map<UUID, DigState> digStart = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> consecutiveFastBreak = new ConcurrentHashMap<>();

    private record DigState(long startMs, long expectedMs, String world, int x, int y, int z) {
        boolean matches(Block block) {
            return block.getX() == x && block.getY() == y && block.getZ() == z
                    && block.getWorld().getName().equals(world);
        }
    }

    public WorldChecker(Plugin plugin, PluginConfig config, DatabaseManager database, LanguageManager lang) {
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

    /** Track when a player starts digging a block (for the per-block FastBreak timing). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        if (!config.worldChecksEnabled() || !config.fastBreakDetectionEnabled()) return;
        UUID id = event.getPlayer().getUniqueId();
        if (event.getInstaBreak()) {
            digStart.remove(id); // instamine has no measurable dig time
            return;
        }
        Block b = event.getBlock();
        // Expected dig time measured NOW: the player's state at dig start (on ground /
        // airborne, haste, tool) is what governed most of the actual dig.
        digStart.put(id, new DigState(System.currentTimeMillis(), expectedDigMs(b, event.getPlayer()),
                b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
    }

    /**
     * Expected dig time in ms from getBreakSpeed() (damage per tick, tool/enchants/haste/
     * conditions included), or 0 when instamined / not measurable.
     */
    private long expectedDigMs(Block block, Player player) {
        float speed = block.getBreakSpeed(player);
        if (speed <= 0.0f || speed >= 1.0f) return 0L;
        return (long) Math.ceil(1.0 / speed) * 50L;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockDamageAbort(BlockDamageAbortEvent event) {
        digStart.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!config.worldChecksEnabled()) return;
        if (ServerLoad.isLagging(config)) return;
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        DigState dig = digStart.remove(id);
        if (Exemptions.isExempt(player, config, luckPerms, geyser)) return;

        // Nuker: too many blocks per second
        if (config.nukerDetectionEnabled()) {
            int rate = recordAndCount(breaks, id);
            int max = config.nukerMaxBreaksPerSecond();
            if (rate > max) {
                breaks.get(id).clear(); // reset so it must re-accumulate
                handleViolation(player, "NUKER", lang.format("alert.nuker", rate, max), rate, event.getBlock().getLocation());
            }
        }

        // FastBreak: block broken clearly faster than its expected break time. The
        // expected time is measured at BOTH dig start and dig end and the smaller one
        // wins: the player's state can legitimately change mid-dig (landing from a jump,
        // a haste beacon kicking in, a tool switch) and judging only the end state reads
        // the on-ground/boosted dig as impossibly fast. Instamine and very short digs
        // are skipped.
        if (config.fastBreakDetectionEnabled() && dig != null && dig.matches(event.getBlock())) {
            long expectedEndMs = expectedDigMs(event.getBlock(), player);
            if (expectedEndMs > 0L && dig.expectedMs() > 0L) {
                long expectedMs = Math.min(dig.expectedMs(), expectedEndMs);
                long actualMs = System.currentTimeMillis() - dig.startMs();
                if (expectedMs >= Constants.FASTBREAK_MIN_EXPECTED_MS
                        && actualMs < (long) (expectedMs * config.fastBreakTolerance())) {
                    int c = consecutiveFastBreak.merge(id, 1, Integer::sum);
                    if (config.debugMode()) {
                        plugin.getLogger().info(String.format("[FASTBREAK-DEBUG] %s actual=%dms expected=%dms (%d/%d)",
                                player.getName(), actualMs, expectedMs, c, config.fastBreakViolations()));
                    }
                    if (c >= config.fastBreakViolations()) {
                        handleViolation(player, "FASTBREAK",
                                lang.format("alert.fastbreak", actualMs, expectedMs), actualMs,
                                event.getBlock().getLocation());
                        consecutiveFastBreak.put(id, 0);
                    }
                } else {
                    consecutiveFastBreak.remove(id);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!config.worldChecksEnabled()) return;
        if (ServerLoad.isLagging(config)) return;
        Player player = event.getPlayer();
        if (Exemptions.isExempt(player, config, luckPerms, geyser)) return;
        UUID id = player.getUniqueId();

        // FastPlace: too many blocks per second
        if (config.fastPlaceDetectionEnabled()) {
            int rate = recordAndCount(places, id);
            int max = config.fastPlaceMaxPerSecond();
            if (rate > max) {
                places.get(id).clear();
                handleViolation(player, "FASTPLACE", lang.format("alert.fastplace", rate, max), rate, event.getBlock().getLocation());
            }
        }

        // Scaffold: block placed far outside the player's view (placing "blind" while
        // bridging/towering). The angle is measured to the nearest point of the CLICKED
        // block, not the placed block's center: placing a block at your own feet
        // legitimately puts the new block below/behind you (95°+ off aim) while the face
        // you actually clicked was in view the whole time.
        if (config.scaffoldDetectionEnabled()) {
            Block against = event.getBlockAgainst();
            org.bukkit.Location eye = player.getEyeLocation();
            double nx = Math.max(against.getX(), Math.min(eye.getX(), against.getX() + 1.0));
            double ny = Math.max(against.getY(), Math.min(eye.getY(), against.getY() + 1.0));
            double nz = Math.max(against.getZ(), Math.min(eye.getZ(), against.getZ() + 1.0));
            Vector look = eye.getDirection();
            Vector toBlock = new Vector(nx, ny, nz).subtract(eye.toVector());
            if (toBlock.lengthSquared() > 1.0e-6) {
                double angle = Math.toDegrees(look.angle(toBlock));
                if (config.debugMode()) {
                    plugin.getLogger().info(String.format("[SCAFFOLD-DEBUG] %s angle=%.0f (max %.0f)",
                            player.getName(), angle, config.scaffoldMaxAngle()));
                }
                if (angle > config.scaffoldMaxAngle()) {
                    int c = consecutiveScaffold.merge(id, 1, Integer::sum);
                    if (c >= config.scaffoldViolations()) {
                        handleViolation(player, "SCAFFOLD",
                                lang.format("alert.scaffold", angle), angle, event.getBlock().getLocation());
                        consecutiveScaffold.put(id, 0);
                    }
                } else {
                    consecutiveScaffold.remove(id);
                }
            }
        }
    }

    /** Add a timestamp, trim to the sliding window and return the current count. */
    private int recordAndCount(Map<UUID, ConcurrentLinkedDeque<Long>> map, UUID id) {
        long now = System.currentTimeMillis();
        long cutoff = now - WINDOW_MS;
        ConcurrentLinkedDeque<Long> deque = map.computeIfAbsent(id, k -> new ConcurrentLinkedDeque<>());
        deque.addLast(now);
        Long head;
        while ((head = deque.peekFirst()) != null && head < cutoff) {
            deque.pollFirst();
        }
        return deque.size();
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
        breaks.remove(playerId);
        places.remove(playerId);
        consecutiveScaffold.remove(playerId);
        digStart.remove(playerId);
        consecutiveFastBreak.remove(playerId);
    }
}
