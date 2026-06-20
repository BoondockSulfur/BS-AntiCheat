package dev.boondock.bsanticheat.commands;

import dev.boondock.bsanticheat.BSAntiCheat;
import dev.boondock.bsanticheat.anticheat.XRayDetector;
import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.lang.LanguageManager;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Command to manage XRay excluded ores.
 * Usage:
 * - /xrayores list - Show excluded ores
 * - /xrayores add <ore> - Add ore to exclusion list
 * - /xrayores remove <ore> - Remove ore from exclusion list
 * - /xrayores available - Show all available ores
 */
public class XRayOresCommand implements CommandExecutor, TabCompleter {

    private final BSAntiCheat plugin;
    private final PluginConfig config;
    private final XRayDetector xrayDetector;

    // Available ores that can be excluded
    private static final List<String> AVAILABLE_ORES = List.of(
            "COAL_ORE", "DEEPSLATE_COAL_ORE",
            "IRON_ORE", "DEEPSLATE_IRON_ORE",
            "COPPER_ORE", "DEEPSLATE_COPPER_ORE",
            "GOLD_ORE", "DEEPSLATE_GOLD_ORE",
            "REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE",
            "LAPIS_ORE", "DEEPSLATE_LAPIS_ORE",
            "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE",
            "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE",
            "ANCIENT_DEBRIS"
    );

    public XRayOresCommand(BSAntiCheat plugin, PluginConfig config, XRayDetector xrayDetector) {
        this.plugin = plugin;
        this.config = config;
        this.xrayDetector = xrayDetector;
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
            showHelp(sender);
            return true;
        }

        String action = args[0].toLowerCase();

        switch (action) {
            case "list" -> showExcludedOres(sender);
            case "available" -> showAvailableOres(sender);
            case "add" -> {
                if (args.length < 2) {
                    sender.sendMessage(lang().get("xrayores.usage_add"));
                    return true;
                }
                addOre(sender, args[1]);
            }
            case "remove" -> {
                if (args.length < 2) {
                    sender.sendMessage(lang().get("xrayores.usage_remove"));
                    return true;
                }
                removeOre(sender, args[1]);
            }
            default -> showHelp(sender);
        }

        return true;
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage(lang().get("xrayores.help_header"));
        sender.sendMessage(lang().get("xrayores.help_list"));
        sender.sendMessage(lang().get("xrayores.help_available"));
        sender.sendMessage(lang().get("xrayores.help_add"));
        sender.sendMessage(lang().get("xrayores.help_remove"));
    }

    private void showExcludedOres(CommandSender sender) {
        List<String> excluded = config.xrayExcludedOres();

        sender.sendMessage(lang().get("xrayores.list_header"));

        if (excluded.isEmpty()) {
            sender.sendMessage(lang().get("xrayores.list_none"));
        } else {
            for (String ore : excluded) {
                sender.sendMessage(lang().get("xrayores.list_entry", "%ore%", ore));
            }
        }

        sender.sendMessage(lang().get("xrayores.list_hint"));
    }

    private void showAvailableOres(CommandSender sender) {
        List<String> excluded = config.xrayExcludedOres();

        sender.sendMessage(lang().get("xrayores.available_header"));

        for (String ore : AVAILABLE_ORES) {
            boolean isExcluded = excluded.contains(ore);
            String status = isExcluded ? lang().get("xrayores.available_excluded") : lang().get("xrayores.available_tracked");
            sender.sendMessage(status + " \u00a7f" + ore);
        }
    }

    private void addOre(CommandSender sender, String oreName) {
        String upperOre = oreName.toUpperCase();

        // Validate ore name
        if (!AVAILABLE_ORES.contains(upperOre)) {
            // Check if it's valid without DEEPSLATE_ prefix
            if (AVAILABLE_ORES.contains(upperOre + "_ORE")) {
                upperOre = upperOre + "_ORE";
            } else if (!upperOre.endsWith("_ORE") && !upperOre.equals("ANCIENT_DEBRIS")) {
                sender.sendMessage(lang().get("xrayores.unknown_ore", "%ore%", oreName));
                sender.sendMessage(lang().get("xrayores.unknown_ore_hint"));
                return;
            }
        }

        // Check if Material exists
        try {
            Material.valueOf(upperOre);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(lang().get("xrayores.invalid_ore", "%ore%", oreName));
            return;
        }

        List<String> excluded = config.xrayExcludedOres();
        if (excluded.contains(upperOre)) {
            sender.sendMessage(lang().get("xrayores.already_excluded", "%ore%", upperOre));
            return;
        }

        config.addExcludedOre(upperOre);

        // Reload XRayDetector
        if (xrayDetector != null) {
            xrayDetector.reloadExcludedOres();
        }

        sender.sendMessage(lang().get("xrayores.added", "%ore%", upperOre));
        sender.sendMessage(lang().get("xrayores.added_hint"));
    }

    private void removeOre(CommandSender sender, String oreName) {
        String upperOre = oreName.toUpperCase();

        List<String> excluded = config.xrayExcludedOres();
        if (!excluded.contains(upperOre)) {
            sender.sendMessage(lang().get("xrayores.not_excluded", "%ore%", upperOre));
            return;
        }

        config.removeExcludedOre(upperOre);

        // Reload XRayDetector
        if (xrayDetector != null) {
            xrayDetector.reloadExcludedOres();
        }

        sender.sendMessage(lang().get("xrayores.removed", "%ore%", upperOre));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("bsanticheat.manage")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return List.of("list", "available", "add", "remove").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("add")) {
                // Show ores not yet excluded
                List<String> excluded = config.xrayExcludedOres();
                return AVAILABLE_ORES.stream()
                        .filter(ore -> !excluded.contains(ore))
                        .filter(ore -> ore.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (args[0].equalsIgnoreCase("remove")) {
                // Show only excluded ores
                return config.xrayExcludedOres().stream()
                        .filter(ore -> ore.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }
}
