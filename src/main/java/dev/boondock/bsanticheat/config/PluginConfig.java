package dev.boondock.bsanticheat.config;

import dev.boondock.bsanticheat.util.Constants;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Configuration manager for BSAntiCheat.
 * Contains only anticheat-related settings.
 */
public class PluginConfig {

    private final JavaPlugin plugin;
    private FileConfiguration cfg;
    private final AsyncConfigSaver asyncSaver;

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.cfg = plugin.getConfig();
        this.asyncSaver = new AsyncConfigSaver(plugin);
        mergeDefaults();
        validateConfig();
    }

    public void reload() {
        plugin.reloadConfig();
        this.cfg = plugin.getConfig();
        mergeDefaults();
        validateConfig();
    }

    /**
     * Add any config keys present in the bundled default config.yml but missing from the
     * user's file (e.g. after a plugin update), keeping existing values. Mirrors how the
     * LanguageManager merges new language keys.
     */
    private void mergeDefaults() {
        try (InputStream is = plugin.getResource("config.yml")) {
            if (is == null) return;
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(is, StandardCharsets.UTF_8));
            boolean changed = false;
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key)) continue; // only leaf values
                if (!cfg.contains(key)) {
                    cfg.set(key, defaults.get(key));
                    changed = true;
                }
            }
            if (changed) {
                plugin.saveConfig();
                plugin.getLogger().info("[Config] Added missing config keys from defaults.");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Config] Could not merge default config keys: " + e.getMessage());
        }
    }

    private void validateConfig() {
        boolean hasErrors = false;

        double ratio = cfg.getDouble("anticheat.xray_stone_ore_ratio", 0.10);
        if (ratio < 0.0 || ratio > 1.0) {
            plugin.getLogger().warning("[Config] Invalid xray_stone_ore_ratio: " + ratio + ". Using 0.10");
            cfg.set("anticheat.xray_stone_ore_ratio", 0.10);
            hasErrors = true;
        }

        double walkSpeed = cfg.getDouble("anticheat.speed_thresholds.walk", 0.4);
        if (walkSpeed <= 0) {
            plugin.getLogger().warning("[Config] Invalid speed_thresholds.walk: " + walkSpeed + ". Using 0.4");
            cfg.set("anticheat.speed_thresholds.walk", 0.4);
            hasErrors = true;
        }

        double sprintSpeed = cfg.getDouble("anticheat.speed_thresholds.sprint", 0.6);
        if (sprintSpeed <= 0) {
            plugin.getLogger().warning("[Config] Invalid speed_thresholds.sprint: " + sprintSpeed + ". Using 0.6");
            cfg.set("anticheat.speed_thresholds.sprint", 0.6);
            hasErrors = true;
        }

        if (hasErrors) {
            asyncSaver.saveAsync();
        }
    }

    // Language
    public String language() {
        String lang = cfg.getString("language", "en");
        return lang != null && !lang.isEmpty() ? lang : "en";
    }

    // Debug
    public boolean debugMode() { return cfg.getBoolean("debug_mode", false); }

    // Lag handling: skip checks while recent TPS is below this (avoids lag false positives)
    public double lagExemptTps() { return cfg.getDouble("anticheat.lag_exempt_tps", 18.0); }

    // Database (SQLite only)
    public String sqliteFile() {
        String file = cfg.getString("database.sqlite_file", Constants.DEFAULT_SQLITE_PATH);
        return file != null && !file.isEmpty() ? file : Constants.DEFAULT_SQLITE_PATH;
    }
    public int poolMax() { return cfg.getInt("database.pool.max_pool_size", Constants.DB_DEFAULT_POOL_SIZE); }
    public int poolMinIdle() { return cfg.getInt("database.pool.minimum_idle", Constants.DB_DEFAULT_MIN_IDLE); }
    public long poolConnTimeoutMs() { return cfg.getLong("database.pool.connection_timeout_ms", Constants.DB_DEFAULT_CONNECTION_TIMEOUT_MS); }
    public int databaseRetentionDays() { return cfg.getInt("database.retention_days", 30); }
    public boolean fallbackFileLoggingEnabled() { return cfg.getBoolean("database.fallback_file_logging", true); }
    public String fallbackLogFile() {
        String path = cfg.getString("database.fallback_log_file", "plugins/BSAntiCheat/fallback.log");
        return path != null && !path.isEmpty() ? path : "plugins/BSAntiCheat/fallback.log";
    }

    // AntiCheat detection toggles
    public boolean xrayDetectionEnabled() { return cfg.getBoolean("anticheat.xray_detection", true); }
    public boolean movementChecksEnabled() { return cfg.getBoolean("anticheat.movement_checks", true); }
    public boolean speedDetectionEnabled() { return cfg.getBoolean("anticheat.speed_detection", true); }
    public boolean flyDetectionEnabled() { return cfg.getBoolean("anticheat.fly_detection", true); }
    public boolean groundSpoofDetectionEnabled() { return cfg.getBoolean("anticheat.groundspoof_detection", true); }
    public boolean noSlowDetectionEnabled() { return cfg.getBoolean("anticheat.noslow_detection", true); }
    public boolean teleportDetectionEnabled() { return cfg.getBoolean("anticheat.teleport_detection", true); }
    public boolean combatChecksEnabled() { return cfg.getBoolean("anticheat.combat_checks", true); }
    public boolean reachDetectionEnabled() { return cfg.getBoolean("anticheat.reach_detection", true); }
    public double reachDistance() { return cfg.getDouble("anticheat.reach_distance", 4.0); }
    public boolean killAuraDetectionEnabled() { return cfg.getBoolean("anticheat.killaura_detection", true); }
    public double killAuraMaxAngle() { return cfg.getDouble("anticheat.killaura_max_angle", 75.0); }
    public int killAuraMultiTargets() { return cfg.getInt("anticheat.killaura_multi_targets", 3); }
    public boolean killAuraPlayersOnly() { return cfg.getBoolean("anticheat.killaura_players_only", true); }
    public boolean worldChecksEnabled() { return cfg.getBoolean("anticheat.world_checks", true); }
    public boolean nukerDetectionEnabled() { return cfg.getBoolean("anticheat.nuker_detection", true); }
    public int nukerMaxBreaksPerSecond() { return cfg.getInt("anticheat.nuker_max_breaks_per_second", 15); }
    public boolean fastPlaceDetectionEnabled() { return cfg.getBoolean("anticheat.fastplace_detection", true); }
    public int fastPlaceMaxPerSecond() { return cfg.getInt("anticheat.fastplace_max_per_second", 12); }
    public boolean packetChecksEnabled() { return cfg.getBoolean("anticheat.packet_checks", true); }
    public boolean autoClickerDetectionEnabled() { return cfg.getBoolean("anticheat.autoclicker_detection", true); }
    public int autoClickerMaxCps() { return cfg.getInt("anticheat.autoclicker_max_cps", 16); }
    public boolean autoClickerConsistencyEnabled() { return cfg.getBoolean("anticheat.autoclicker_consistency", true); }
    public int autoClickerMinSamples() { return cfg.getInt("anticheat.autoclicker_min_samples", 15); }
    public int autoClickerMinCps() { return cfg.getInt("anticheat.autoclicker_min_cps", 2); }
    public int autoClickerMaxDeviationMs() { return cfg.getInt("anticheat.autoclicker_max_deviation_ms", 30); }
    public double autoClickerMaxCv() { return cfg.getDouble("anticheat.autoclicker_max_cv", 0.30); }
    public double autoClickerMaxOutlierRatio() { return cfg.getDouble("anticheat.autoclicker_max_outlier_ratio", 0.06); }
    public int autoClickerMinSignals() { return cfg.getInt("anticheat.autoclicker_min_signals", 2); }
    public boolean badPacketsDetectionEnabled() { return cfg.getBoolean("anticheat.badpackets_detection", true); }

    // XRay settings
    public int xrayTimewindowSeconds() { return cfg.getInt("anticheat.xray_timewindow_seconds", 60); }
    public List<String> xrayExcludedOres() { return cfg.getStringList("anticheat.xray_excluded_ores"); }
    public double xrayStoneOreRatio() { return cfg.getDouble("anticheat.xray_stone_ore_ratio", 0.10); }

    public int xrayThreshold(String oreType) {
        String key = oreType.toLowerCase().replace("_ore", "").replace("deepslate_", "");
        if (key.equals("ancient_debris")) return cfg.getInt("anticheat.xray_thresholds.ancient_debris", 3);
        return cfg.getInt("anticheat.xray_thresholds." + key, 10);
    }

    // Whitelist
    public boolean opsBypass() { return cfg.getBoolean("anticheat.ops_bypass", false); }
    public List<String> anticheatWhitelistPlayers() { return cfg.getStringList("anticheat.whitelist_players"); }
    public List<String> anticheatWhitelistGroups() { return cfg.getStringList("anticheat.whitelist_groups"); }

    // Movement speed thresholds
    public double speedThresholdWalk() { return cfg.getDouble("anticheat.speed_thresholds.walk", 0.4); }
    public double speedThresholdSprint() { return cfg.getDouble("anticheat.speed_thresholds.sprint", 0.6); }
    public double speedThresholdFly() { return cfg.getDouble("anticheat.speed_thresholds.fly", 1.5); }
    public double flyThreshold() { return cfg.getDouble("anticheat.speed_thresholds.vertical", 3.5); }
    public double teleportThreshold() { return cfg.getDouble("anticheat.speed_thresholds.teleport", 15.0); }
    public int speedViolationsThreshold() { return cfg.getInt("anticheat.speed_thresholds.violations_before_alert", 5); }
    public int flyViolationsThreshold() { return cfg.getInt("anticheat.speed_thresholds.fly_violations_before_alert", 10); }

    // Punishments / Violation levels
    public boolean punishmentsEnabled() { return cfg.getBoolean("anticheat.punishments.enabled", false); }
    public int punishmentsDecaySeconds() { return cfg.getInt("anticheat.punishments.decay_seconds", 300); }
    public boolean punishmentsSetback() { return cfg.getBoolean("anticheat.punishments.setback", false); }

    /** Punishment tiers as a sorted map of VL threshold -> console commands. */
    public Map<Integer, List<String>> punishmentTiers() {
        Map<Integer, List<String>> tiers = new TreeMap<>();
        ConfigurationSection sec = cfg.getConfigurationSection("anticheat.punishments.tiers");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                try {
                    tiers.put(Integer.parseInt(key), sec.getStringList(key));
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("[Config] Invalid punishment tier (must be a number): " + key);
                }
            }
        }
        return tiers;
    }

    // Restricted worlds
    public List<String> restrictedWorlds() { return cfg.getStringList("anticheat.restricted_worlds"); }
    public List<String> restrictedWorldOres() { return cfg.getStringList("anticheat.restricted_world_ores"); }
    public boolean isRestrictedWorld(String worldName) { return restrictedWorlds().contains(worldName); }

    // Whitelist management
    public void addWhitelistPlayer(String uuid) {
        List<String> list = new ArrayList<>(anticheatWhitelistPlayers());
        if (!list.contains(uuid)) { list.add(uuid); cfg.set("anticheat.whitelist_players", list); asyncSaver.saveAsync(); }
    }
    public void removeWhitelistPlayer(String uuid) {
        List<String> list = new ArrayList<>(anticheatWhitelistPlayers());
        if (list.remove(uuid)) { cfg.set("anticheat.whitelist_players", list); asyncSaver.saveAsync(); }
    }
    public void addWhitelistGroup(String group) {
        List<String> list = new ArrayList<>(anticheatWhitelistGroups());
        if (!list.contains(group)) { list.add(group); cfg.set("anticheat.whitelist_groups", list); asyncSaver.saveAsync(); }
    }
    public void removeWhitelistGroup(String group) {
        List<String> list = new ArrayList<>(anticheatWhitelistGroups());
        if (list.remove(group)) { cfg.set("anticheat.whitelist_groups", list); asyncSaver.saveAsync(); }
    }
    public void addExcludedOre(String ore) {
        List<String> list = new ArrayList<>(xrayExcludedOres());
        String upper = ore.toUpperCase();
        if (!list.contains(upper)) { list.add(upper); cfg.set("anticheat.xray_excluded_ores", list); asyncSaver.saveAsync(); }
    }
    public void removeExcludedOre(String ore) {
        List<String> list = new ArrayList<>(xrayExcludedOres());
        if (list.remove(ore.toUpperCase())) { cfg.set("anticheat.xray_excluded_ores", list); asyncSaver.saveAsync(); }
    }

    // Discord
    public boolean discordEnabled() { return cfg.getBoolean("discord.enabled", false); }
    public String discordWebhookUrl() { return cfg.getString("discord.webhook_url", ""); }
    public boolean discordAlertType(String type) { return cfg.getBoolean("discord.alert_types." + type, true); }

    // Silent players
    public List<String> silentPlayers() { return cfg.getStringList("alerts.silent_players"); }
    public void setSilentPlayers(List<String> players) { cfg.set("alerts.silent_players", players); asyncSaver.saveAsync(); }

    // Save
    public void saveAsync() { asyncSaver.saveAsync(); }
    public void saveSyncOnShutdown() { asyncSaver.saveSyncOnShutdown(); }
}
