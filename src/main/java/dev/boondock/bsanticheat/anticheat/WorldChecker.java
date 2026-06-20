package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.lang.LanguageManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.Plugin;

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
    private MovementAlertManager alertManager;
    private ViolationManager violationManager;

    private final Map<UUID, ConcurrentLinkedDeque<Long>> breaks = new ConcurrentHashMap<>();
    private final Map<UUID, ConcurrentLinkedDeque<Long>> places = new ConcurrentHashMap<>();

    public WorldChecker(Plugin plugin, PluginConfig config, DatabaseManager database, LanguageManager lang) {
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!config.worldChecksEnabled() || !config.nukerDetectionEnabled()) return;
        Player player = event.getPlayer();
        if (Exemptions.isExempt(player, config, luckPerms)) return;

        int rate = recordAndCount(breaks, player.getUniqueId());
        int max = config.nukerMaxBreaksPerSecond();
        if (rate > max) {
            breaks.get(player.getUniqueId()).clear(); // reset so it must re-accumulate
            handleViolation(player, "NUKER", lang.format("alert.nuker", rate, max), rate, event.getBlock().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!config.worldChecksEnabled() || !config.fastPlaceDetectionEnabled()) return;
        Player player = event.getPlayer();
        if (Exemptions.isExempt(player, config, luckPerms)) return;

        int rate = recordAndCount(places, player.getUniqueId());
        int max = config.fastPlaceMaxPerSecond();
        if (rate > max) {
            places.get(player.getUniqueId()).clear();
            handleViolation(player, "FASTPLACE", lang.format("alert.fastplace", rate, max), rate, event.getBlock().getLocation());
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
    }
}
