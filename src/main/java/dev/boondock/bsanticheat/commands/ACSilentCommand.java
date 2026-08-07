package dev.boondock.bsanticheat.commands;

import dev.boondock.bsanticheat.alerts.AlertPreferenceManager;
import dev.boondock.bsanticheat.alerts.AlertPreferenceManager.AlertCategory;
import dev.boondock.bsanticheat.lang.LanguageManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Toggle anticheat alert notifications (silent mode).
 */
public class ACSilentCommand implements CommandExecutor, TabCompleter {

    private final LanguageManager lang;
    private final AlertPreferenceManager preferences;

    public ACSilentCommand(Plugin plugin, LanguageManager lang, AlertPreferenceManager preferences) {
        this.lang = lang;
        this.preferences = preferences;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("bsanticheat.admin")) {
            sender.sendMessage(lang.get("general.no_permission"));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("general.only_players"));
            return true;
        }

        if (args.length == 0) {
            boolean silenced = preferences.toggleAll(player.getUniqueId());
            player.sendMessage(lang.get(silenced ? "acsilent.toggled_on" : "acsilent.toggled_off",
                "%category%", "ALL"));
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("list")) {
            showStatus(player);
            return true;
        }

        if (sub.equals("reset")) {
            preferences.resetAll(player.getUniqueId());
            player.sendMessage(lang.get("acsilent.toggled_off", "%category%", "ALL"));
            return true;
        }

        AlertCategory category;
        try {
            category = AlertCategory.valueOf(sub.toUpperCase());
        } catch (IllegalArgumentException e) {
            player.sendMessage(lang.get("acsilent.usage"));
            return true;
        }

        boolean silenced = preferences.toggleCategory(player.getUniqueId(), category);
        player.sendMessage(lang.get(silenced ? "acsilent.toggled_on" : "acsilent.toggled_off",
            "%category%", category.getDisplayName(lang.getCurrentLanguage())));
        return true;
    }

    private void showStatus(Player player) {
        player.sendMessage(lang.get("acsilent.list_header"));
        Set<AlertCategory> muted = preferences.getMutedCategories(player.getUniqueId());

        if (muted.isEmpty()) {
            player.sendMessage(lang.get("acsilent.list_none"));
        } else if (muted.contains(AlertCategory.ALL)) {
            player.sendMessage(lang.get("acsilent.list_muted", "%categories%", "ALL"));
        } else {
            String cats = muted.stream()
                .map(c -> c.getDisplayName(lang.getCurrentLanguage()))
                .collect(Collectors.joining(", "));
            player.sendMessage(lang.get("acsilent.list_muted", "%categories%", cats));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            completions.add("list");
            completions.add("reset");
            for (AlertCategory cat : AlertCategory.values()) {
                completions.add(cat.name().toLowerCase());
            }
            String input = args[0].toLowerCase();
            return completions.stream().filter(s -> s.startsWith(input)).collect(Collectors.toList());
        }
        return List.of();
    }
}
