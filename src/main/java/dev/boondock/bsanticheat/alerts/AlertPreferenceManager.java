package dev.boondock.bsanticheat.alerts;

import dev.boondock.bsanticheat.config.PluginConfig;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-player alert preferences (silent mode).
 * Allows admins to mute all alerts or specific alert categories.
 *
 * Categories:
 * - ALL: Mutes everything
 * - XRAY: XRay detection alerts
 * - MOVEMENT: Movement/Speed/Fly alerts
 * - PERFORMANCE: Performance alerts (TPS drops, high MSPT, heap warnings)
 *
 * @since 2.3.1
 */
public class AlertPreferenceManager {

    private final Plugin plugin;
    private final PluginConfig config;

    // Per-player muted categories (in-memory, resets on relog unless persistent)
    private final Map<UUID, Set<AlertCategory>> mutedCategories = new ConcurrentHashMap<>();

    public AlertPreferenceManager(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;

        // Load persistent silent players from config
        loadPersistentPreferences();
    }

    /**
     * Toggle all alerts for a player.
     * @return true if now silenced, false if now receiving
     */
    public boolean toggleAll(UUID playerId) {
        Set<AlertCategory> muted = mutedCategories.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());

        if (muted.contains(AlertCategory.ALL)) {
            muted.remove(AlertCategory.ALL);
            if (muted.isEmpty()) {
                mutedCategories.remove(playerId);
            }
            updatePersistence(playerId);
            return false;
        } else {
            muted.add(AlertCategory.ALL);
            updatePersistence(playerId);
            return true;
        }
    }

    /**
     * Toggle a specific alert category for a player.
     * @return true if now silenced, false if now receiving
     */
    public boolean toggleCategory(UUID playerId, AlertCategory category) {
        if (category == AlertCategory.ALL) {
            return toggleAll(playerId);
        }

        Set<AlertCategory> muted = mutedCategories.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());

        if (muted.contains(category)) {
            muted.remove(category);
            if (muted.isEmpty()) {
                mutedCategories.remove(playerId);
            }
            updatePersistence(playerId);
            return false;
        } else {
            muted.add(category);
            updatePersistence(playerId);
            return true;
        }
    }

    /**
     * Check if a player should receive alerts of a given category.
     * @return true if the player should receive the alert
     */
    public boolean shouldReceive(UUID playerId, AlertCategory category) {
        Set<AlertCategory> muted = mutedCategories.get(playerId);
        if (muted == null || muted.isEmpty()) {
            return true;
        }

        // ALL mutes everything
        if (muted.contains(AlertCategory.ALL)) {
            return false;
        }

        return !muted.contains(category);
    }

    /**
     * Check if a player should receive alerts (convenience for Player).
     */
    public boolean shouldReceive(Player player, AlertCategory category) {
        return shouldReceive(player.getUniqueId(), category);
    }

    /**
     * Get muted categories for a player.
     */
    public Set<AlertCategory> getMutedCategories(UUID playerId) {
        Set<AlertCategory> muted = mutedCategories.get(playerId);
        if (muted == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(muted);
    }

    /**
     * Reset all preferences for a player (unmute everything).
     */
    public void resetAll(UUID playerId) {
        mutedCategories.remove(playerId);
        updatePersistence(playerId);
    }

    /**
     * Check if a player has any muted categories.
     */
    public boolean hasAnyMuted(UUID playerId) {
        Set<AlertCategory> muted = mutedCategories.get(playerId);
        return muted != null && !muted.isEmpty();
    }

    /**
     * Cleanup player data on disconnect (non-persistent preferences only).
     */
    public void cleanup(UUID playerId) {
        // Only remove if not in persistent list
        List<String> persistentPlayers = config.silentPlayers();
        if (!persistentPlayers.contains(playerId.toString())) {
            mutedCategories.remove(playerId);
        }
    }

    /**
     * Load persistent preferences from config.
     */
    private void loadPersistentPreferences() {
        List<String> silentPlayers = config.silentPlayers();
        for (String entry : silentPlayers) {
            try {
                // Format: "uuid" (all muted) or "uuid:category1,category2"
                String[] parts = entry.split(":", 2);
                UUID playerId = UUID.fromString(parts[0]);

                Set<AlertCategory> muted = ConcurrentHashMap.newKeySet();
                if (parts.length == 1) {
                    muted.add(AlertCategory.ALL);
                } else {
                    for (String cat : parts[1].split(",")) {
                        try {
                            muted.add(AlertCategory.valueOf(cat.trim().toUpperCase()));
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().warning("[AlertPreferences] Invalid alert category: " + cat.trim());
                        }
                    }
                }

                if (!muted.isEmpty()) {
                    mutedCategories.put(playerId, muted);
                }
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("[AlertPreferences] Invalid entry in silent_players: " + entry);
            }
        }
    }

    /**
     * Update persistent config when preferences change.
     */
    private void updatePersistence(UUID playerId) {
        List<String> silentPlayers = new ArrayList<>(config.silentPlayers());
        // Remove old entry for this player
        silentPlayers.removeIf(e -> e.startsWith(playerId.toString()));

        Set<AlertCategory> muted = mutedCategories.get(playerId);
        if (muted != null && !muted.isEmpty()) {
            if (muted.contains(AlertCategory.ALL)) {
                silentPlayers.add(playerId.toString());
            } else {
                StringJoiner joiner = new StringJoiner(",");
                for (AlertCategory cat : muted) {
                    joiner.add(cat.name());
                }
                silentPlayers.add(playerId + ":" + joiner);
            }
        }

        config.setSilentPlayers(silentPlayers);
    }

    /**
     * Alert categories that can be individually muted.
     */
    public enum AlertCategory {
        ALL("Alle Alerts", "All Alerts"),
        XRAY("XRay-Erkennung", "XRay Detection"),
        MOVEMENT("Movement-Checks", "Movement Checks");

        private final String displayNameDe;
        private final String displayNameEn;

        AlertCategory(String displayNameDe, String displayNameEn) {
            this.displayNameDe = displayNameDe;
            this.displayNameEn = displayNameEn;
        }

        public String getDisplayName(String lang) {
            return "de".equalsIgnoreCase(lang) ? displayNameDe : displayNameEn;
        }
    }
}
