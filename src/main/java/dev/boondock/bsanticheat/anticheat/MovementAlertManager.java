package dev.boondock.bsanticheat.anticheat;


import dev.boondock.bsanticheat.alerts.AlertPreferenceManager;
import dev.boondock.bsanticheat.alerts.DiscordWebhook;
import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.lang.LanguageManager;
import dev.boondock.bsanticheat.util.Constants;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages Movement/Speed/Fly alerts - collects them and provides a summary view.
 * Only sends summary notifications to chat, detailed info available via command.
 */
public class MovementAlertManager {

    private final Plugin plugin;
    private final PluginConfig config;
    private final LanguageManager lang;
    private final DiscordWebhook discordWebhook;

    // Store alerts per player
    private final Map<UUID, List<MovementAlert>> playerAlerts = new ConcurrentHashMap<>();

    // Track if we already notified admins about suspicious players
    private final Set<UUID> notifiedPlayers = ConcurrentHashMap.newKeySet();

    // Cooldown per player per type (to prevent spam)
    private final Map<UUID, Map<String, Long>> alertCooldowns = new ConcurrentHashMap<>();
    private AlertPreferenceManager preferenceManager;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    // Use centralized constant for cooldown

    public MovementAlertManager(Plugin plugin, PluginConfig config, LanguageManager lang) {
        this.plugin = plugin;
        this.config = config;
        this.lang = lang;
        this.discordWebhook = new DiscordWebhook(plugin, config, lang);

        // Schedule cleanup of old alerts every 10 minutes
        Bukkit.getScheduler().runTaskTimer(plugin, this::cleanupOldAlerts, 12000L, 12000L);
    }

    /**
     * Set the alert preference manager for silent mode support.
     */
    public void setPreferenceManager(AlertPreferenceManager preferenceManager) {
        this.preferenceManager = preferenceManager;
    }

    /**
     * Add a new movement alert for a player.
     */
    public void addAlert(Player player, String type, String details, double value, Location location) {
        UUID playerId = player.getUniqueId();

        // Check cooldown for this type
        if (isOnCooldown(playerId, type)) {
            return;
        }

        List<MovementAlert> alerts = playerAlerts.computeIfAbsent(playerId, k -> new CopyOnWriteArrayList<>());

        String locationStr = formatLocation(location);
        MovementAlert alert = new MovementAlert(type, details, value, System.currentTimeMillis(), locationStr);
        alerts.add(alert);

        // Set cooldown
        setCooldown(playerId, type);

        // Only log to console in debug mode
        if (config.debugMode()) {
            plugin.getLogger().info("[Movement] " + player.getName() + ": " + type + " - " + details);
        }

        // Only send one summary notification per player (reset after 5 minutes)
        if (!notifiedPlayers.contains(playerId)) {
            notifiedPlayers.add(playerId);
            notifyAdmins(player, type, details, value, locationStr);
            sendDiscordAlert(player, type, details, value, locationStr);

            // Reset notification flag after 5 minutes
            Bukkit.getScheduler().runTaskLater(plugin, () -> notifiedPlayers.remove(playerId), 6000L);
        }
    }

    private boolean isOnCooldown(UUID playerId, String type) {
        Map<String, Long> cooldowns = alertCooldowns.get(playerId);
        if (cooldowns == null) return false;

        Long lastAlert = cooldowns.get(type);
        if (lastAlert == null) return false;

        return System.currentTimeMillis() - lastAlert < Constants.ALERT_COOLDOWN_MS;
    }

    private void setCooldown(UUID playerId, String type) {
        alertCooldowns.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                .put(type, System.currentTimeMillis());
    }

    private String formatLocation(Location loc) {
        if (loc == null) return lang.get("alert.location_unknown");
        String worldName = loc.getWorld() != null ? loc.getWorld().getName() : "unknown";
        return String.format("%s [%d, %d, %d]", worldName, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    /**
     * Send a summary notification to admins.
     */
    private void notifyAdmins(Player suspect, String type, String details, double value, String location) {
        String message = lang.get("alert.notify_movement", "%player%", suspect.getName());
        String detail = lang.get("alert.notify_movement_detail",
                "%type%", type, "%value%", String.format("%.2f", value), "%pos%", location);

        LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();
        Component clickable = legacy.deserialize(message)
                .clickEvent(ClickEvent.runCommand("/movealerts " + suspect.getName()))
                .hoverEvent(HoverEvent.showText(legacy.deserialize(lang.get("alert.click_hint"))));
        Component detailComp = legacy.deserialize(detail);

        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("bsanticheat.admin"))
                .filter(p -> preferenceManager == null ||
                        preferenceManager.shouldReceive(p, AlertPreferenceManager.AlertCategory.MOVEMENT))
                .forEach(admin -> {
                    admin.sendMessage(clickable);
                    admin.sendMessage(detailComp);
                });
    }

    /**
     * Send alert to Discord webhook.
     */
    private void sendDiscordAlert(Player player, String type, String details, double value, String location) {
        if (!config.discordEnabled()) return;

        String description = "**" + lang.get("alert.discord_player") + ":** " + player.getName()
                + "\\n**" + lang.get("alert.discord_type") + ":** " + type
                + "\\n**" + lang.get("alert.discord_details") + ":** " + details
                + "\\n**" + lang.get("alert.discord_value") + ":** " + String.format("%.2f", value)
                + "\\n**" + lang.get("alert.discord_position") + ":** " + location;

        discordWebhook.sendAlert(
                DiscordWebhook.AlertType.MOVEMENT,
                lang.get("alert.discord_movement_title", "%player%", player.getName()),
                description,
                value
        );
    }

    /**
     * Get all alerts for a player.
     */
    public List<MovementAlert> getAlerts(UUID playerId) {
        return playerAlerts.getOrDefault(playerId, Collections.emptyList());
    }

    /**
     * Get all players with alerts.
     */
    public Set<UUID> getSuspiciousPlayers() {
        return new HashSet<>(playerAlerts.keySet());
    }

    /**
     * Get total alert count for a player.
     */
    public int getAlertCount(UUID playerId) {
        return getAlerts(playerId).size();
    }

    /**
     * Clear alerts for a player (e.g., after review).
     */
    public void clearAlerts(UUID playerId) {
        playerAlerts.remove(playerId);
        notifiedPlayers.remove(playerId);
        alertCooldowns.remove(playerId);
    }

    /**
     * Clear all alerts.
     */
    public void clearAllAlerts() {
        playerAlerts.clear();
        notifiedPlayers.clear();
        alertCooldowns.clear();
    }

    /**
     * Cleanup alerts older than 30 minutes and expired cooldowns.
     */
    private void cleanupOldAlerts() {
        long now = System.currentTimeMillis();
        long alertCutoff = now - (30 * 60 * 1000L);
        long cooldownCutoff = now - Constants.ALERT_COOLDOWN_MS;

        // Clean up old alerts
        playerAlerts.entrySet().removeIf(entry -> {
            List<MovementAlert> alerts = entry.getValue();
            alerts.removeIf(alert -> alert.timestamp() < alertCutoff);

            // Remove empty entries and cleanup tracking
            if (alerts.isEmpty()) {
                notifiedPlayers.remove(entry.getKey());
                return true; // Remove this entry
            }
            return false;
        });

        // Clean up expired cooldowns (more efficient)
        alertCooldowns.entrySet().removeIf(entry -> {
            Map<String, Long> cooldowns = entry.getValue();
            cooldowns.entrySet().removeIf(cd -> cd.getValue() < cooldownCutoff);
            return cooldowns.isEmpty();
        });
    }

    /**
     * Format alerts for display.
     */
    public List<String> formatAlerts(UUID playerId) {
        List<String> lines = new ArrayList<>();
        List<MovementAlert> alerts = getAlerts(playerId);

        if (alerts.isEmpty()) {
            lines.add(lang.get("alert.none"));
            return lines;
        }

        for (MovementAlert alert : alerts) {
            String time = TIME_FORMAT.format(Instant.ofEpochMilli(alert.timestamp()));
            lines.add(lang.get("alert.entry_movement",
                    "%time%", time, "%type%", alert.type(), "%details%", alert.details()));
            lines.add(lang.get("alert.entry_movement_value",
                    "%value%", String.format("%.2f", alert.value()), "%pos%", alert.location()));
        }

        return lines;
    }

    /**
     * Alert data record.
     */
    public record MovementAlert(String type, String details, double value, long timestamp, String location) {}
}
