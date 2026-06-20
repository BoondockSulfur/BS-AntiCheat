package dev.boondock.bsanticheat.commands;

import dev.boondock.bsanticheat.BSAntiCheat;
import dev.boondock.bsanticheat.lang.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Umbrella admin command: /bsac &lt;reload|info|version&gt; [player].
 */
public class BSACCommand implements CommandExecutor, TabCompleter {

    private final BSAntiCheat plugin;

    public BSACCommand(BSAntiCheat plugin) {
        this.plugin = plugin;
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

        if (args.length == 0) {
            sender.sendMessage(lang().get("bsac.usage"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadPlugin();
                sender.sendMessage(lang().get("general.config_reloaded"));
            }
            case "version" -> sender.sendMessage(lang().get("bsac.version",
                    "%version%", plugin.getDescription().getVersion()));
            case "info" -> {
                if (args.length < 2) {
                    sender.sendMessage(lang().get("bsac.usage"));
                    return true;
                }
                showInfo(sender, args[1]);
            }
            default -> sender.sendMessage(lang().get("bsac.usage"));
        }
        return true;
    }

    private void showInfo(CommandSender sender, String playerName) {
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            sender.sendMessage(lang().get("general.player_not_found", "%player%", playerName));
            return;
        }

        sender.sendMessage(lang().get("bsac.info_header", "%player%", target.getName()));
        Map<String, Integer> violations = plugin.violationManager() != null
                ? plugin.violationManager().getAllViolations(target.getUniqueId())
                : Map.of();

        if (violations.isEmpty()) {
            sender.sendMessage(lang().get("bsac.info_none"));
            return;
        }
        violations.forEach((check, vl) -> sender.sendMessage(
                lang().get("bsac.info_entry", "%check%", check, "%vl%", String.valueOf(vl))));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("bsanticheat.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("reload", "info", "version").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("info")) {
            List<String> names = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(p -> names.add(p.getName()));
            return names.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
