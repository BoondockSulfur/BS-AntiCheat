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
 * Umbrella admin command: /bsac &lt;reload|info|version|test&gt; [player].
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
            case "test" -> runTest(sender, args);
            default -> sender.sendMessage(lang().get("bsac.usage"));
        }
        return true;
    }

    /**
     * Raise a player's violation level by hand: {@code /bsac test <player> <CHECK> [count]}.
     *
     * <p>There is no other way to reach the punishment ladder without actually cheating.
     * Most checks cannot be tripped by a human at all — Speed and Fly need a modified client,
     * and Nuker and FastPlace need rates no hand achieves — so an admin who configures tiers
     * has no way to find out whether they work until somebody trips them for real. This runs
     * the REAL path: the same {@code flag()} every check calls, so the levels, the decay, the
     * tier matching and the commands all behave exactly as they will in earnest.
     *
     * <p>Which also means it really punishes. The tier's commands run, a kick kicks, and it
     * is logged with the name of whoever asked for it.
     */
    private void runTest(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(lang().get("bsac.test_usage"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(lang().get("general.player_not_found", "%player%", args[1]));
            return;
        }
        if (plugin.violationManager() == null) {
            sender.sendMessage(lang().get("bsac.test_unavailable"));
            return;
        }
        String check = args[2].toUpperCase(java.util.Locale.ROOT);
        int count = 1;
        if (args.length >= 4) {
            try {
                count = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage(lang().get("bsac.test_usage"));
                return;
            }
            // Capped: this is a diagnostic, not a way to drive somebody to a ban tier with
            // one command by accident.
            if (count < 1 || count > 50) {
                sender.sendMessage(lang().get("bsac.test_usage"));
                return;
            }
        }

        plugin.getLogger().warning("[Punishment] TEST: " + sender.getName() + " raised "
                + target.getName() + "'s " + check + " violation level by " + count
                + " by hand. Any tier this crosses runs for real.");

        int vl = 0;
        for (int i = 0; i < count; i++) {
            vl = plugin.violationManager().flag(target, check);
            // A punishment may have removed them; carrying on would be flagging a ghost.
            if (!target.isOnline()) break;
        }
        sender.sendMessage(lang().get("bsac.test_done",
                "%player%", target.getName(), "%check%", check, "%vl%", String.valueOf(vl)));
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
            return List.of("reload", "info", "version", "test").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("test"))) {
            List<String> names = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(p -> names.add(p.getName()));
            return names.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("test")) {
            return java.util.stream.Stream.of("SPEED", "FLY", "REACH", "KILLAURA", "NUKER",
                            "FASTPLACE", "XRAY_THRESHOLD", "AUTOCLICKER", "TIMER")
                    .filter(s -> s.startsWith(args[2].toUpperCase(java.util.Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
