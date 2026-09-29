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
        dev.boondock.bsanticheat.util.Scheduler.runGlobalTimer(plugin, this::cleanupOldAlerts, 12000L, 12000L);
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

        // Check and start the cooldown for this type in one step
        long now = System.currentTimeMillis();
        if (!tryStartCooldown(playerId, type, now)) {
            return;
        }

        String locationStr = formatLocation(location);
        MovementAlert alert = new MovementAlert(type, details, value, now, locationStr);
        // Added under the map's lock: the cleanup drops emptied lists with computeIfPresent,
        // so a list fetched here can never be removed before the alert lands in it.
        playerAlerts.compute(playerId, (k, list) -> {
            if (list == null) list = new CopyOnWriteArrayList<>();
            list.add(alert);
            return list;
        });

        // Only log to console in debug mode
        if (config.debugMode()) {
            plugin.getLogger().info("[Movement] " + player.getName() + ": " + type + " - " + details);
        }

        // Notify per violation TYPE (the per-type cooldown above already limits spam), so
        // a player flagged for SPEED then FLY then TELEPORT produces one chat alert each
        // instead of only the first type.
        notifyAdmins(player, type, details, value, locationStr);
        sendDiscordAlert(player, type, details, value, locationStr);
    }

    /**
     * Atomically: true, and the cooldown started, if none is running for this player and
     * type. Runs under the outer map's lock, which the cleanup also takes, so an emptied
     * per-player map is never removed while a new cooldown is being put into it.
     */
    private boolean tryStartCooldown(UUID playerId, String type, long now) {
        return tryStartCooldown(alertCooldowns, playerId, type, now, Constants.ALERT_COOLDOWN_MS);
    }

    /** Static with its state passed in, so it can be tested without a scheduler behind it. */
    static boolean tryStartCooldown(Map<UUID, Map<String, Long>> alertCooldowns, UUID playerId,
                                    String type, long now, long cooldownMs) {
        boolean[] started = {false};
        alertCooldowns.compute(playerId, (k, cooldowns) -> {
            if (cooldowns == null) cooldowns = new ConcurrentHashMap<>();
            Long lastAlert = cooldowns.get(type);
            if (lastAlert == null || now - lastAlert >= cooldownMs) {
                cooldowns.put(type, now);
                started[0] = true;
            }
            return cooldowns;
        });
        return started[0];
    }

    private String formatLocation(Location loc) {
        if (loc == null) return lang.get("alert.location_unknown");
        return dev.boondock.bsanticheat.util.CheckMath.formatLocation(loc);
    }

    /**
     * Send a summary notification to admins.
     */
    private void notifyAdmins(Player suspect, String type, String details, double value, String location) {
        // Placeholders resolve against the SUSPECT, not the admin reading the alert — the
        // alert is about them, so %player_world% and friends should say where THEY are.
        String message = dev.boondock.bsanticheat.util.Messages.placeholders(suspect,
                lang.get("alert.notify_movement", "%player%", suspect.getName()));
        String detail = dev.boondock.bsanticheat.util.Messages.placeholders(suspect,
                lang.get("alert.notify_movement_detail",
                        "%type%", type, "%value%", String.format(java.util.Locale.ROOT, "%.2f", value),
                        "%pos%", location));

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

        // Real newlines here — escapeJson() in DiscordWebhook converts them to \n in
        // the JSON; a pre-escaped "\\n" would get double-escaped and show literally.
        String description = "**" + lang.get("alert.discord_player") + ":** " + player.getName()
                + "\n**" + lang.get("alert.discord_type") + ":** " + type
                + "\n**" + lang.get("alert.discord_details") + ":** " + details
                + "\n**" + lang.get("alert.discord_value") + ":** " + String.format(java.util.Locale.ROOT, "%.2f", value)
                + "\n**" + lang.get("alert.discord_position") + ":** " + location;

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
        alertCooldowns.remove(playerId);
    }

    /**
     * Clear all alerts.
     */
    public void clearAllAlerts() {
        playerAlerts.clear();
        alertCooldowns.clear();
    }

    /**
     * Cleanup alerts older than 30 minutes and expired cooldowns.
     */
    void cleanupOldAlerts() {
        long now = System.currentTimeMillis();
        long alertCutoff = now - (30 * 60 * 1000L);
        long cooldownCutoff = now - Constants.ALERT_COOLDOWN_MS;

        // Trim and remove-if-empty inside computeIfPresent: the same lock addAlert holds while
        // appending, so a concurrent alert or cooldown is never dropped with its container.
        for (UUID id : playerAlerts.keySet()) {
            playerAlerts.computeIfPresent(id, (k, alerts) -> {
                alerts.removeIf(alert -> alert.timestamp() < alertCutoff);
                return alerts.isEmpty() ? null : alerts;
            });
        }
        for (UUID id : alertCooldowns.keySet()) {
            alertCooldowns.computeIfPresent(id, (k, cooldowns) -> {
                cooldowns.entrySet().removeIf(cd -> cd.getValue() < cooldownCutoff);
                return cooldowns.isEmpty() ? null : cooldowns;
            });
        }
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
                    "%value%", String.format(java.util.Locale.ROOT, "%.2f", alert.value()), "%pos%", alert.location()));
        }

        return lines;
    }

    /**
     * Alert data record.
     */
    public record MovementAlert(String type, String details, double value, long timestamp, String location) {}
}
