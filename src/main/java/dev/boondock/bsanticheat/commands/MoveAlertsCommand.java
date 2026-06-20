package dev.boondock.bsanticheat.commands;

import dev.boondock.bsanticheat.BSAntiCheat;
import dev.boondock.bsanticheat.anticheat.MovementAlertManager;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.lang.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Command to view and manage Movement/Speed/Fly alerts.
 * Usage:
 * - /movealerts - Show all suspicious players
 * - /movealerts <player> - Show alerts for specific player
 * - /movealerts clear <player> - Clear alerts for player
 * - /movealerts clearall - Clear all alerts
 */
public class MoveAlertsCommand implements CommandExecutor, TabCompleter {

    private final BSAntiCheat plugin;
    private final MovementAlertManager alertManager;

    public MoveAlertsCommand(BSAntiCheat plugin, MovementAlertManager alertManager) {
        this.plugin = plugin;
        this.alertManager = alertManager;
    }

    private LanguageManager lang() {
        return plugin.lang();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("bsanticheat.admin")) {
            sender.sendMessage(lang().get("general.no_permission"));
            return true;
        }

        // Check if AntiCheat is enabled
        if (alertManager == null) {
            sender.sendMessage(lang().get("movement.disabled"));
            sender.sendMessage(lang().get("movement.disabled_hint"));
            return true;
        }

        // No args - show suspicious players list
        if (args.length == 0) {
            showSuspiciousList(sender);
            return true;
        }

        String action = args[0].toLowerCase();

        // Clear all alerts
        if (action.equals("clearall")) {
            alertManager.clearAllAlerts();
            sender.sendMessage(lang().get("movement.alerts_cleared_all"));
            return true;
        }

        // Clear specific player
        if (action.equals("clear") && args.length >= 2) {
            String playerName = args[1];
            OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);

            if (target.hasPlayedBefore() || target.isOnline()) {
                alertManager.clearAlerts(target.getUniqueId());

                // Check for --db flag to also delete database entries
                boolean clearDb = args.length >= 3 && args[2].equalsIgnoreCase("--db");
                if (clearDb) {
                    DatabaseManager db = plugin.database();
                    if (db != null) {
                        int deleted = db.deleteAntiCheatLogs(playerName, "anticheat_speed")
                                + db.deleteAntiCheatLogs(playerName, "anticheat_fly")
                                + db.deleteAntiCheatLogs(playerName, "anticheat_teleport");
                        sender.sendMessage(lang().get("movement.db_cleared",
                                "%player%", playerName, "%count%", String.valueOf(deleted)));
                    }
                } else {
                    sender.sendMessage(lang().get("movement.alerts_cleared_player", "%player%", playerName));
                    sender.sendMessage(lang().get("movement.db_clear_hint", "%player%", playerName));
                }
            } else {
                sender.sendMessage(lang().get("general.player_not_found", "%player%", playerName));
            }
            return true;
        }

        // Show alerts for specific player
        String playerName = args[0];
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);

        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(lang().get("general.player_not_found", "%player%", playerName));
            return true;
        }

        showPlayerAlerts(sender, target);
        return true;
    }

    private void showSuspiciousList(CommandSender sender) {
        Set<UUID> suspicious = alertManager.getSuspiciousPlayers();

        if (suspicious.isEmpty()) {
            sender.sendMessage(lang().get("movement.no_suspicious"));
            return;
        }

        sender.sendMessage(lang().get("movement.list_header"));

        for (UUID uuid : suspicious) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
            String name = player.getName() != null ? player.getName() : uuid.toString();
            int alertCount = alertManager.getAlertCount(uuid);
            String status = player.isOnline() ? lang().get("movement.status_online") : lang().get("movement.status_offline");

            sender.sendMessage(lang().get("movement.list_entry",
                    "%status%", status,
                    "%player%", name,
                    "%count%", String.valueOf(alertCount)));
        }

        sender.sendMessage(lang().get("movement.list_hint_details"));
        sender.sendMessage(lang().get("movement.list_hint_clear"));
    }

    private void showPlayerAlerts(CommandSender sender, OfflinePlayer target) {
        String name = target.getName() != null ? target.getName() : target.getUniqueId().toString();
        List<String> alerts = alertManager.formatAlerts(target.getUniqueId());

        sender.sendMessage(lang().get("movement.detail_header", "%player%", name));
        alerts.forEach(sender::sendMessage);

        if (alertManager.getAlertCount(target.getUniqueId()) > 0) {
            sender.sendMessage(lang().get("movement.detail_hint_clear", "%player%", name));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("bsanticheat.admin")) {
            return Collections.emptyList();
        }

        if (alertManager == null) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            completions.add("clearall");
            completions.add("clear");

            // Add suspicious player names
            for (UUID uuid : alertManager.getSuspiciousPlayers()) {
                OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
                if (player.getName() != null) {
                    completions.add(player.getName());
                }
            }

            return completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("clear")) {
            return alertManager.getSuspiciousPlayers().stream()
                    .map(Bukkit::getOfflinePlayer)
                    .map(OfflinePlayer::getName)
                    .filter(Objects::nonNull)
                    .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("clear")) {
            String input = args[2].toLowerCase();
            if ("--db".startsWith(input)) {
                return List.of("--db");
            }
        }

        return Collections.emptyList();
    }
}
