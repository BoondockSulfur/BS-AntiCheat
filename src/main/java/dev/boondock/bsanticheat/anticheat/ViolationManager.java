package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.api.ViolationEvent;
import dev.boondock.bsanticheat.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central violation-level (VL) and punishment handler.
 *
 * <p>Every confirmed detection calls {@link #flag(Player, String)}, which raises the
 * player's VL for that check type. VL decays over time (see {@code decay_seconds}).
 * When a check's VL crosses a configured tier, the tier's console commands are run.
 *
 * <p>The whole system is a no-op unless {@code anticheat.punishments.enabled} is true,
 * so servers that only want alerts are unaffected.
 */
public class ViolationManager {

    private final Plugin plugin;
    private final PluginConfig config;

    // playerId -> (checkType -> violation level state)
    private final Map<UUID, Map<String, Vl>> data = new ConcurrentHashMap<>();

    public ViolationManager(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * Register a violation for a check type and run any punishment tiers that are crossed.
     *
     * @return the player's new VL for this check (0 if punishments are disabled)
     */
    public int flag(Player player, String checkType) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Map<String, Vl> perCheck = data.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        Vl vl = perCheck.computeIfAbsent(checkType, k -> new Vl());

        int before, after;
        synchronized (vl) {
            applyDecay(vl, now);
            before = (int) Math.floor(vl.points);
            vl.points += 1.0;
            vl.lastUpdate = now;
            after = (int) Math.floor(vl.points);
        }

        // VL is always tracked (for /bsac info, placeholders, the API event).
        fireViolationEvent(player, checkType, after);

        // Punishment tiers are opt-in.
        if (config.punishmentsEnabled() && after > before) {
            runTiers(player, checkType, before, after);
        }
        return after;
    }

    private void fireViolationEvent(Player player, String checkType, int vl) {
        ViolationEvent event = new ViolationEvent(player, checkType, vl);
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getPluginManager().callEvent(event);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(event));
        }
    }

    /** Current (decayed) VL for a check type, without adding a violation. */
    public int getViolations(UUID playerId, String checkType) {
        Map<String, Vl> perCheck = data.get(playerId);
        if (perCheck == null) return 0;
        Vl vl = perCheck.get(checkType);
        if (vl == null) return 0;
        synchronized (vl) {
            applyDecay(vl, System.currentTimeMillis());
            return (int) Math.floor(vl.points);
        }
    }

    /** Snapshot of all non-zero (decayed) VL for a player, keyed by check type. */
    public Map<String, Integer> getAllViolations(UUID playerId) {
        Map<String, Integer> out = new HashMap<>();
        Map<String, Vl> perCheck = data.get(playerId);
        if (perCheck == null) return out;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Vl> e : perCheck.entrySet()) {
            Vl vl = e.getValue();
            int points;
            synchronized (vl) {
                applyDecay(vl, now);
                points = (int) Math.floor(vl.points);
            }
            if (points > 0) out.put(e.getKey(), points);
        }
        return out;
    }

    /** Remove all tracked VL for a player (call on quit). */
    public void cleanup(UUID playerId) {
        data.remove(playerId);
    }

    private void applyDecay(Vl vl, long now) {
        int decaySeconds = config.punishmentsDecaySeconds();
        if (decaySeconds <= 0 || vl.lastUpdate == 0) {
            vl.lastUpdate = now;
            return;
        }
        double elapsedSeconds = (now - vl.lastUpdate) / 1000.0;
        double decayed = elapsedSeconds / decaySeconds; // 1 VL lost per decaySeconds
        if (decayed > 0) {
            vl.points = Math.max(0.0, vl.points - decayed);
            vl.lastUpdate = now;
        }
    }

    /**
     * Run the commands of every tier whose threshold lies in (before, after].
     */
    private void runTiers(Player player, String checkType, int before, int after) {
        Map<Integer, List<String>> tiers = config.punishmentTiers();
        if (tiers.isEmpty()) return;

        for (Map.Entry<Integer, List<String>> tier : tiers.entrySet()) {
            int threshold = tier.getKey();
            if (threshold > before && threshold <= after) {
                for (String raw : tier.getValue()) {
                    String command = raw
                            .replace("%player%", player.getName())
                            .replace("%check%", checkType)
                            .replace("%vl%", String.valueOf(after));
                    dispatch(command);
                }
            }
        }
    }

    /** Dispatch a console command on the main thread. */
    private void dispatch(String command) {
        if (command == null || command.isBlank()) return;
        Runnable task = () -> {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            } catch (Exception e) {
                plugin.getLogger().warning("[Punishment] Failed to run command '" + command + "': " + e.getMessage());
            }
        };
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /** Per-check violation-level state. */
    private static final class Vl {
        double points;
        long lastUpdate;
    }
}
