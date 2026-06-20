package dev.boondock.bsanticheat.commands;

import dev.boondock.bsanticheat.BSAntiCheat;
import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.lang.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class ACWhitelistCommand implements CommandExecutor, TabCompleter {

    private final BSAntiCheat plugin;
    private final PluginConfig config;

    public ACWhitelistCommand(BSAntiCheat plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    private LanguageManager lang() {
        return plugin.lang();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("bsanticheat.manage")) {
            sender.sendMessage(lang().get("general.no_permission"));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String action = args[0].toLowerCase();

        switch (action) {
            case "add" -> handleAdd(sender, args);
            case "remove" -> handleRemove(sender, args);
            case "list" -> handleList(sender);
            default -> sendHelp(sender);
        }

        return true;
    }

    private void handleAdd(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(lang().get("acwhitelist.usage_add"));
            return;
        }

        String target = args[1];

        // Check if it's a group
        if (target.startsWith("group:")) {
            String groupName = target.substring(6);
            config.addWhitelistGroup(groupName);
            sender.sendMessage(lang().get("acwhitelist.group_added", "%group%", groupName));
            return;
        }

        // It's a player
        @SuppressWarnings("deprecation")
        OfflinePlayer player = Bukkit.getOfflinePlayer(target);

        if (player == null || !player.hasPlayedBefore()) {
            sender.sendMessage(lang().get("general.player_not_found", "%player%", target));
            return;
        }

        config.addWhitelistPlayer(player.getUniqueId().toString());
        sender.sendMessage(lang().get("acwhitelist.player_added", "%player%", player.getName()));
    }

    private void handleRemove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(lang().get("acwhitelist.usage_remove"));
            return;
        }

        String target = args[1];

        // Check if it's a group
        if (target.startsWith("group:")) {
            String groupName = target.substring(6);
            config.removeWhitelistGroup(groupName);
            sender.sendMessage(lang().get("acwhitelist.group_removed", "%group%", groupName));
            return;
        }

        // It's a player
        @SuppressWarnings("deprecation")
        OfflinePlayer player = Bukkit.getOfflinePlayer(target);

        if (player == null) {
            sender.sendMessage(lang().get("general.player_not_found", "%player%", target));
            return;
        }

        config.removeWhitelistPlayer(player.getUniqueId().toString());
        sender.sendMessage(lang().get("acwhitelist.player_removed", "%player%", player.getName()));
    }

    private void handleList(CommandSender sender) {
        sender.sendMessage(lang().get("acwhitelist.header"));

        // List players
        List<String> players = config.anticheatWhitelistPlayers();
        if (players.isEmpty()) {
            sender.sendMessage(lang().get("acwhitelist.players_none"));
        } else {
            sender.sendMessage(lang().get("acwhitelist.players_label"));
            for (String uuidStr : players) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
                    sender.sendMessage(lang().get("acwhitelist.player_entry", "%player%", player.getName()));
                } catch (IllegalArgumentException e) {
                    sender.sendMessage(lang().get("acwhitelist.player_invalid", "%uuid%", uuidStr));
                }
            }
        }

        // List groups
        List<String> groups = config.anticheatWhitelistGroups();
        if (groups.isEmpty()) {
            sender.sendMessage(lang().get("acwhitelist.groups_none"));
        } else {
            sender.sendMessage(lang().get("acwhitelist.groups_label"));
            for (String group : groups) {
                sender.sendMessage(lang().get("acwhitelist.group_entry", "%group%", group));
            }
        }

        sender.sendMessage(lang().get("acwhitelist.footer"));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(lang().get("acwhitelist.help_title"));
        sender.sendMessage(lang().get("acwhitelist.help_add_player"));
        sender.sendMessage(lang().get("acwhitelist.help_add_group"));
        sender.sendMessage(lang().get("acwhitelist.help_remove_player"));
        sender.sendMessage(lang().get("acwhitelist.help_remove_group"));
        sender.sendMessage(lang().get("acwhitelist.help_list"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("bsanticheat.manage")) {
            return List.of();
        }

        if (args.length == 1) {
            return Arrays.asList("add", "remove", "list").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove"))) {
            List<String> suggestions = new ArrayList<>();

            // Add online player names
            Bukkit.getOnlinePlayers().forEach(p -> suggestions.add(p.getName()));

            // Add group: prefix
            suggestions.add("group:");

            return suggestions.stream()
                .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                .collect(Collectors.toList());
        }

        return List.of();
    }
}
