package dev.boondock.bsanticheat.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;

/**
 * Migrates anticheat settings from old PerformanceAnalyzer config on first run.
 */
public class ConfigMigrator {

    private final Plugin plugin;

    public ConfigMigrator(Plugin plugin) {
        this.plugin = plugin;
    }

    public void migrateFromPerformanceAnalyzer() {
        // Resolve via our own data folder's parent — a relative path would depend on the
        // process working directory, which is not guaranteed to be the server root.
        File pluginsDir = plugin.getDataFolder().getParentFile();
        if (pluginsDir == null) return;
        File oldConfig = new File(new File(pluginsDir, "PerformanceAnalyzer"), "config.yml");
        if (!oldConfig.exists()) return;
        if (plugin.getConfig().getInt("config_version", 0) > 0) return;

        plugin.getLogger().info("[Migration] Found PerformanceAnalyzer config, migrating anticheat settings...");
        try {
            YamlConfiguration old = YamlConfiguration.loadConfiguration(oldConfig);

            if (old.contains("anticheat")) {
                for (String key : old.getConfigurationSection("anticheat").getKeys(true)) {
                    Object value = old.get("anticheat." + key);
                    if (value != null && !(value instanceof org.bukkit.configuration.ConfigurationSection)) {
                        plugin.getConfig().set("anticheat." + key, value);
                    }
                }
            }
            if (old.contains("discord")) {
                for (String key : old.getConfigurationSection("discord").getKeys(true)) {
                    Object value = old.get("discord." + key);
                    if (value != null && !(value instanceof org.bukkit.configuration.ConfigurationSection)) {
                        plugin.getConfig().set("discord." + key, value);
                    }
                }
            }
            if (old.contains("alerts.silent_players")) {
                plugin.getConfig().set("alerts.silent_players", old.getStringList("alerts.silent_players"));
            }
            if (old.contains("language")) {
                plugin.getConfig().set("language", old.getString("language"));
            }

            plugin.getConfig().set("config_version", 1);
            plugin.saveConfig();
            plugin.getLogger().info("[Migration] Successfully migrated settings from PerformanceAnalyzer.");
        } catch (Exception e) {
            plugin.getLogger().warning("[Migration] Failed to migrate: " + e.getMessage());
        }
    }
}
