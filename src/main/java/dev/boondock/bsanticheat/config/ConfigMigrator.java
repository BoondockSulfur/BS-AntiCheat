package dev.boondock.bsanticheat.config;

import dev.boondock.bsanticheat.util.Constants;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Imports the anticheat settings of the legacy PerformanceAnalyzer, which shipped the
 * anticheat as part of itself before it became this plugin.
 *
 * <p>A current PerformanceAnalyzer has no {@code anticheat} section and its {@code discord}
 * and {@code alerts} sections belong to its own features (incident alerts, /perfsilent).
 * Importing those would point BSAntiCheat at PerformanceAnalyzer's webhook and mute every
 * anticheat alert for its silenced players, so only a config that actually carries an
 * {@code anticheat} section is migrated, only once (on the install that creates our
 * config.yml), and only keys BSAntiCheat itself knows.
 */
public class ConfigMigrator {

    /** Written after a migration so it never runs a second time over the admin's edits. */
    public static final String MARKER_KEY = "config_version";

    /** Sections whose keys are admin data; copied as a whole instead of key by key. */
    private static final List<String> ADMIN_DEFINED_SECTIONS = List.of(
            "anticheat.punishments.tiers",
            "anticheat.xray_thresholds");

    /** Anticheat alert types in the legacy combined plugin's discord.alert_types. */
    private static final List<String> ANTICHEAT_ALERT_TYPES = List.of("xray", "movement");

    /**
     * Sections the old migration filled with PerformanceAnalyzer keys. Any key below them
     * that the bundled default config does not know is foreign and is removed on start.
     */
    private static final List<String> CLEANED_SECTIONS = List.of("discord");

    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("en", "de");

    private final Plugin plugin;

    public ConfigMigrator(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Migrate from a legacy PerformanceAnalyzer config, if there is one.
     *
     * @param freshInstall true when our config.yml did not exist before
     *                     {@code saveDefaultConfig()} ran on this start
     * @return true when settings were imported and config.yml was written
     */
    public boolean migrateFromPerformanceAnalyzer(boolean freshInstall) {
        if (!freshInstall) return false;
        // Resolve via our own data folder's parent — a relative path would depend on the
        // process working directory, which is not guaranteed to be the server root.
        File pluginsDir = plugin.getDataFolder().getParentFile();
        if (pluginsDir == null) return false;
        File oldConfig = new File(new File(pluginsDir, "PerformanceAnalyzer"), "config.yml");
        if (!oldConfig.exists()) return false;
        FileConfiguration target = plugin.getConfig();
        if (target.contains(MARKER_KEY, true)) return false;

        try {
            YamlConfiguration old = YamlConfiguration.loadConfiguration(oldConfig);
            if (!old.isConfigurationSection("anticheat")) return false;

            Configuration defaults = loadBundledDefaults(plugin);
            if (defaults == null) return false;

            plugin.getLogger().info("[Migration] Found a legacy PerformanceAnalyzer config with anticheat"
                    + " settings, migrating them...");
            int copied = migrate(old, target, defaults);
            target.set(MARKER_KEY, Constants.CONFIG_VERSION);
            plugin.saveConfig();
            plugin.getLogger().info("[Migration] Migrated " + copied + " settings from PerformanceAnalyzer.");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[Migration] Failed to migrate: " + e.getMessage());
            return false;
        }
    }

    /**
     * Copy the anticheat-relevant part of a legacy config into {@code target}.
     * Silent players are never imported: PerformanceAnalyzer's list is its own, and a
     * {@code uuid} entry there would mute every anticheat alert for that player.
     *
     * @return the number of values copied; 0 when {@code old} has no anticheat section
     */
    static int migrate(ConfigurationSection old, ConfigurationSection target, Configuration defaults) {
        ConfigurationSection anticheat = old.getConfigurationSection("anticheat");
        if (anticheat == null) return 0;
        int copied = 0;

        for (String section : ADMIN_DEFINED_SECTIONS) {
            ConfigurationSection src = old.getConfigurationSection(section);
            if (src == null) continue;
            target.set(section, null);
            for (String key : src.getKeys(true)) {
                if (src.isConfigurationSection(key)) continue;
                target.set(section + "." + key, src.get(key));
                copied++;
            }
        }

        for (String key : anticheat.getKeys(true)) {
            String path = "anticheat." + key;
            if (anticheat.isConfigurationSection(key) || isInAdminSection(path)) continue;
            // Only keys this plugin reads; the legacy plugin had settings that no longer exist.
            if (!defaults.contains(path, true)) continue;
            target.set(path, anticheat.get(key));
            copied++;
        }

        // The webhook only when the legacy config routed anticheat alerts through it.
        boolean hasAnticheatAlertTypes = false;
        for (String type : ANTICHEAT_ALERT_TYPES) {
            String path = "discord.alert_types." + type;
            if (old.contains(path, true)) {
                target.set(path, old.get(path));
                copied++;
                hasAnticheatAlertTypes = true;
            }
        }
        if (hasAnticheatAlertTypes) {
            for (String path : List.of("discord.enabled", "discord.webhook_url")) {
                if (old.contains(path, true)) {
                    target.set(path, old.get(path));
                    copied++;
                }
            }
        }

        String language = old.getString("language");
        if (language != null && SUPPORTED_LANGUAGES.contains(language.toLowerCase(java.util.Locale.ROOT))) {
            target.set("language", language.toLowerCase(java.util.Locale.ROOT));
            copied++;
        }
        return copied;
    }

    private static boolean isInAdminSection(String path) {
        for (String section : ADMIN_DEFINED_SECTIONS) {
            if (path.equals(section) || path.startsWith(section + ".")) return true;
        }
        return false;
    }

    /**
     * Remove keys an earlier version of the migration imported from a current
     * PerformanceAnalyzer (e.g. {@code discord.alert_types.incident_opened}). Runs on every
     * start and only touches keys the bundled default config does not contain, so a second
     * run finds nothing and changes nothing.
     *
     * @return the removed paths; empty when nothing was changed
     */
    static List<String> removeForeignKeys(ConfigurationSection cfg, Configuration defaults) {
        List<String> removed = new ArrayList<>();
        for (String section : CLEANED_SECTIONS) {
            ConfigurationSection sec = cfg.getConfigurationSection(section);
            if (sec == null) continue;
            for (String key : sec.getKeys(true)) {
                if (sec.isConfigurationSection(key)) continue;
                String path = section + "." + key;
                if (!defaults.contains(path, true)) removed.add(path);
            }
        }
        for (String path : removed) cfg.set(path, null);
        // Sections left empty by the removal and unknown to the defaults go as well.
        for (String section : CLEANED_SECTIONS) {
            ConfigurationSection sec = cfg.getConfigurationSection(section);
            if (sec == null) continue;
            List<String> emptySections = new ArrayList<>();
            for (String key : sec.getKeys(true)) {
                String path = section + "." + key;
                if (sec.isConfigurationSection(key) && !defaults.contains(path, true)
                        && sec.getConfigurationSection(key).getKeys(true).isEmpty()) {
                    emptySections.add(path);
                }
            }
            for (String path : emptySections) cfg.set(path, null);
        }
        return removed;
    }

    /** The config.yml inside the plugin jar, or null when it cannot be read. */
    static Configuration loadBundledDefaults(Plugin plugin) {
        try (InputStream is = plugin.getResource("config.yml")) {
            if (is == null) return null;
            return YamlConfiguration.loadConfiguration(new InputStreamReader(is, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }
}
