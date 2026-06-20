package dev.boondock.bsanticheat.anticheat;


import dev.boondock.bsanticheat.alerts.AlertPreferenceManager;
import dev.boondock.bsanticheat.alerts.DiscordWebhook;
import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.lang.LanguageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages XRay alerts - collects them and provides a summary view.
 * Only sends summary notifications to chat, detailed info available via command.
 */
public class XRayAlertManager {

    private final Plugin plugin;
    private final PluginConfig config;
    private final LanguageManager lang;
    private final DiscordWebhook discordWebhook;

    // Store alerts per player
    private final Map<UUID, List<XRayAlert>> playerAlerts = new ConcurrentHashMap<>();

    // Track if we already notified admins about suspicious players
    private final Set<UUID> notifiedPlayers = ConcurrentHashMap.newKeySet();
    private AlertPreferenceManager preferenceManager;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public XRayAlertManager(Plugin plugin, PluginConfig config, LanguageManager lang) {
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
     * Add a new XRay alert for a player.
     */
    public void addAlert(Player player, String type, String details, int oreCount) {
        addAlert(player, type, details, oreCount, null, null);
    }

    /**
     * Add a new XRay alert for a player with ore breakdown.
     */
    public void addAlert(Player player, String type, String details, int oreCount, Map<String, Integer> oreBreakdown) {
        addAlert(player, type, details, oreCount, oreBreakdown, null);
    }

    /**
     * Add a new XRay alert for a player with ore breakdown and locations.
     */
    public void addAlert(Player player, String type, String details, int oreCount, Map<String, Integer> oreBreakdown, List<String> locations) {
        UUID playerId = player.getUniqueId();

        List<XRayAlert> alerts = playerAlerts.computeIfAbsent(playerId, k -> new CopyOnWriteArrayList<>());
        XRayAlert alert = new XRayAlert(type, details, oreCount, System.currentTimeMillis(), oreBreakdown, locations);
        alerts.add(alert);

        // Only log to console in debug mode
        if (config.debugMode()) {
            plugin.getLogger().info("[XRay] " + player.getName() + ": " + type + " - " + details);
        }

        // Only send one summary notification per player (reset after 5 minutes)
        if (!notifiedPlayers.contains(playerId)) {
            notifiedPlayers.add(playerId);
            notifyAdmins(player, oreBreakdown, locations);
            sendDiscordAlert(player, type, details, oreCount, oreBreakdown, locations);

            // Reset notification flag after 5 minutes
            Bukkit.getScheduler().runTaskLater(plugin, () -> notifiedPlayers.remove(playerId), 6000L);
        }
    }

    /**
     * Send a summary notification to admins.
     */
    private void notifyAdmins(Player suspect, Map<String, Integer> oreBreakdown, List<String> locations) {
        String message = lang.get("alert.notify_xray", "%player%", suspect.getName());
        LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();
        Component clickable = legacy.deserialize(message)
                .clickEvent(ClickEvent.runCommand("/xrayalerts " + suspect.getName()))
                .hoverEvent(HoverEvent.showText(legacy.deserialize(lang.get("alert.click_hint"))));

        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("bsanticheat.admin"))
                .filter(p -> preferenceManager == null ||
                        preferenceManager.shouldReceive(p, AlertPreferenceManager.AlertCategory.XRAY))
                .forEach(admin -> {
                    admin.sendMessage(clickable);
                    if (oreBreakdown != null && !oreBreakdown.isEmpty()) {
                        admin.sendMessage(lang.get("alert.notify_xray_ores", "%ores%", formatOreBreakdownShort(oreBreakdown)));
                    }
                    // Show last location if available
                    if (locations != null && !locations.isEmpty()) {
                        admin.sendMessage(lang.get("alert.notify_xray_pos", "%pos%", locations.get(locations.size() - 1)));
                    }
                });
    }

    /**
     * Send alert to Discord webhook.
     */
    private void sendDiscordAlert(Player player, String type, String details, int oreCount, Map<String, Integer> oreBreakdown, List<String> locations) {
        if (!config.discordEnabled()) return;

        String oreInfo = "";
        if (oreBreakdown != null && !oreBreakdown.isEmpty()) {
            oreInfo = "\\n**" + lang.get("alert.discord_ores") + ":** " + formatOreBreakdownShort(oreBreakdown);
        }

        String locationInfo = "";
        if (locations != null && !locations.isEmpty()) {
            locationInfo = "\\n**" + lang.get("alert.discord_positions") + ":**\\n" + String.join("\\n", locations);
        }

        String description = "**" + lang.get("alert.discord_player") + ":** " + player.getName()
                + "\\n**" + lang.get("alert.discord_type") + ":** " + type
                + "\\n**" + lang.get("alert.discord_details") + ":** " + details
                + oreInfo + locationInfo;

        discordWebhook.sendAlert(
                DiscordWebhook.AlertType.XRAY,
                lang.get("alert.discord_xray_title", "%player%", player.getName()),
                description,
                oreCount
        );
    }

    /**
     * Format ore breakdown as short string.
     */
    private String formatOreBreakdownShort(Map<String, Integer> breakdown) {
        if (breakdown == null || breakdown.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        breakdown.forEach((ore, count) -> {
            if (sb.length() > 0) sb.append(", ");
            // Shorten ore name: DIAMOND_ORE -> Diamond
            String shortName = ore.replace("_ORE", "").replace("DEEPSLATE_", "");
            shortName = shortName.substring(0, 1) + shortName.substring(1).toLowerCase();
            sb.append(shortName).append(": ").append(count);
        });
        return sb.toString();
    }

    /**
     * Get all alerts for a player.
     */
    public List<XRayAlert> getAlerts(UUID playerId) {
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
    }

    /**
     * Clear all alerts.
     */
    public void clearAllAlerts() {
        playerAlerts.clear();
        notifiedPlayers.clear();
    }

    /**
     * Cleanup alerts older than 30 minutes.
     */
    private void cleanupOldAlerts() {
        long cutoff = System.currentTimeMillis() - (30 * 60 * 1000L);

        playerAlerts.forEach((uuid, alerts) -> {
            alerts.removeIf(alert -> alert.timestamp() < cutoff);
            if (alerts.isEmpty()) {
                playerAlerts.remove(uuid);
                notifiedPlayers.remove(uuid);
            }
        });
    }

    /**
     * Format alerts for display.
     */
    public List<String> formatAlerts(UUID playerId) {
        List<String> lines = new ArrayList<>();
        List<XRayAlert> alerts = getAlerts(playerId);

        if (alerts.isEmpty()) {
            lines.add(lang.get("alert.none"));
            return lines;
        }

        for (XRayAlert alert : alerts) {
            String time = TIME_FORMAT.format(Instant.ofEpochMilli(alert.timestamp()));
            lines.add(lang.get("alert.entry_xray",
                    "%time%", time, "%type%", alert.type(), "%details%", alert.details(),
                    "%count%", String.valueOf(alert.oreCount())));

            // Show ore breakdown if available
            if (alert.oreBreakdown() != null && !alert.oreBreakdown().isEmpty()) {
                lines.add(lang.get("alert.entry_xray_ores", "%ores%", formatOreBreakdownShort(alert.oreBreakdown())));
            }

            // Show locations if available
            if (alert.locations() != null && !alert.locations().isEmpty()) {
                lines.add(lang.get("alert.entry_xray_positions"));
                for (String loc : alert.locations()) {
                    lines.add(lang.get("alert.entry_xray_position", "%pos%", loc));
                }
            }
        }

        return lines;
    }

    /**
     * Alert data record.
     */
    public record XRayAlert(String type, String details, int oreCount, long timestamp, Map<String, Integer> oreBreakdown, List<String> locations) {}
}
