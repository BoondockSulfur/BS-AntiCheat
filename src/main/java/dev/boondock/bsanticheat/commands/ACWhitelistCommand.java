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

    // Names resolved for stored UUIDs while running a command, reused by tab completion,
    // which runs on every keypress and must not read player data from disk.
    private static final java.util.Map<String, String> NAME_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

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

        // It's a player. Cache-only lookup — getOfflinePlayer(String) does a blocking
        // Mojang web request on the main thread for names the server doesn't know.
        OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(target);

        if (player == null || (!player.hasPlayedBefore() && !player.isOnline())) {
            sender.sendMessage(lang().get("general.player_not_found", "%player%", target));
            return;
        }

        if (player.getName() != null) NAME_CACHE.put(player.getUniqueId().toString(), player.getName());
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

        // A stored entry typed as-is (the UUID shown by /acwhitelist list) needs no lookup:
        // players who are no longer in the user cache can only be removed this way.
        if (config.removeWhitelistPlayer(target)) {
            sender.sendMessage(lang().get("acwhitelist.player_removed", "%player%", displayName(target)));
            return;
        }

        // A name: the user cache first (cache-only lookup, see handleAdd), then the names
        // the server still knows for the stored UUIDs.
        String entry = null;
        OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(target);
        if (player != null && config.anticheatWhitelistPlayers().contains(player.getUniqueId().toString())) {
            entry = player.getUniqueId().toString();
        } else {
            for (String stored : config.anticheatWhitelistPlayers()) {
                if (target.equalsIgnoreCase(knownName(stored))) {
                    entry = stored;
                    break;
                }
            }
        }

        if (entry == null || !config.removeWhitelistPlayer(entry)) {
            sender.sendMessage(lang().get("acwhitelist.player_not_listed", "%player%", target));
            return;
        }
        sender.sendMessage(lang().get("acwhitelist.player_removed", "%player%", displayName(entry)));
    }

    /**
     * The name the server knows for a stored whitelist entry, or null. UUID-based lookups
     * read local player data only; no web request is made.
     */
    private static String knownName(String stored) {
        try {
            String name = Bukkit.getOfflinePlayer(UUID.fromString(stored)).getName();
            if (name != null) NAME_CACHE.put(stored, name);
            return name;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Name for a stored entry from in-memory data only: an online player, or a name resolved
     * by an earlier command. Falls back to the entry itself.
     */
    private static String cachedName(String stored) {
        try {
            org.bukkit.entity.Player online = Bukkit.getPlayer(UUID.fromString(stored));
            if (online != null) return online.getName();
        } catch (IllegalArgumentException e) {
            return stored;
        }
        return NAME_CACHE.getOrDefault(stored, stored);
    }

    /** Name if known, otherwise the stored entry itself — never "null". */
    private static String displayName(String stored) {
        String name = knownName(stored);
        return name != null ? name : stored;
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
                    UUID.fromString(uuidStr);
                } catch (IllegalArgumentException e) {
                    sender.sendMessage(lang().get("acwhitelist.player_invalid", "%uuid%", uuidStr));
                    continue;
                }
                String name = knownName(uuidStr);
                sender.sendMessage(name != null
                        ? lang().get("acwhitelist.player_entry_named", "%player%", name, "%uuid%", uuidStr)
                        : lang().get("acwhitelist.player_entry", "%player%", uuidStr));
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
            if (args[0].equalsIgnoreCase("remove")) {
                for (String stored : config.anticheatWhitelistPlayers()) {
                    suggestions.add(cachedName(stored));
                }
                config.anticheatWhitelistGroups().forEach(g -> suggestions.add("group:" + g));
            }

            // Add group: prefix
            suggestions.add("group:");

            return suggestions.stream()
                .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                .distinct()
                .collect(Collectors.toList());
        }

        return List.of();
    }
}
