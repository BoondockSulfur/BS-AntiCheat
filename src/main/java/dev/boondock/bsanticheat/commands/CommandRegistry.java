package dev.boondock.bsanticheat.commands;

import dev.boondock.bsanticheat.BSAntiCheat;
import dev.boondock.bsanticheat.config.PluginConfig;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

/**
 * Registers all BSAntiCheat commands.
 */
public class CommandRegistry {

    private final BSAntiCheat plugin;

    public CommandRegistry(BSAntiCheat plugin) {
        this.plugin = plugin;
    }

    public void registerAll() {
        PluginConfig config = plugin.configAdapter();

        // Commands use plugin reference to access lang(), database(), etc.
        register("bsac", new BSACCommand(plugin));
        register("acwhitelist", new ACWhitelistCommand(plugin, config));
        register("xrayalerts", new XRayAlertsCommand(plugin, plugin.xrayAlertManager()));
        register("xrayores", new XRayOresCommand(plugin, config, plugin.xrayDetector()));
        register("movealerts", new MoveAlertsCommand(plugin, plugin.movementAlertManager()));
        register("acsilent", new ACSilentCommand(plugin, plugin.lang(), plugin.alertPreferenceManager()));
    }

    private <T extends CommandExecutor & TabCompleter> void register(String name, T handler) {
        PluginCommand cmd = plugin.getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        } else {
            plugin.getLogger().warning("Command not found in plugin.yml: " + name);
        }
    }
}
