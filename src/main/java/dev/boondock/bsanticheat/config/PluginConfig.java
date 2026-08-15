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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Configuration manager for BSAntiCheat.
 * Contains only anticheat-related settings.
 */
public class PluginConfig {

    private final JavaPlugin plugin;
    // volatile + publish-after-prepare: config values are read from async tasks and
    // Netty threads; without this a /bsac reload could expose a half-merged config.
    private volatile FileConfiguration cfg;
    private final AsyncConfigSaver asyncSaver;

    // Hot-path membership caches. These lists are consulted on every movement, every hit
    // and every block break, and cfg.getStringList() allocates a fresh ArrayList on each
    // call — far too expensive there. Rebuilt on load/reload and on every mutation below.
    private volatile Set<String> whitelistPlayersSet = Set.of();
    private volatile Set<String> xrayExemptWorldsSet = Set.of();
    private volatile Set<String> restrictedWorldsSet = Set.of();

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.asyncSaver = new AsyncConfigSaver(plugin);
        FileConfiguration fresh = plugin.getConfig();
        mergeDefaults(fresh);
        validateConfig(fresh);
        this.cfg = fresh;
        rebuildCaches();
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration fresh = plugin.getConfig();
        mergeDefaults(fresh);
        validateConfig(fresh);
        this.cfg = fresh;
        rebuildCaches();
    }

    /** Rebuild the hot-path lookup sets from the current config. */
    private void rebuildCaches() {
        whitelistPlayersSet = toSet(cfg.getStringList("anticheat.whitelist_players"));
        xrayExemptWorldsSet = toSet(cfg.getStringList("anticheat.xray_exempt_worlds"));
        restrictedWorldsSet = toSet(cfg.getStringList("anticheat.restricted_worlds"));
    }

    private static Set<String> toSet(List<String> values) {
        Set<String> set = new HashSet<>();
        for (String v : values) {
            if (v != null) set.add(v);
        }
        return Collections.unmodifiableSet(set);
    }

    /**
     * Add any config keys present in the bundled default config.yml but missing from the
     * user's file (e.g. after a plugin update), keeping existing values. Mirrors how the
     * LanguageManager merges new language keys.
     */
    private void mergeDefaults(FileConfiguration cfg) {
        try (InputStream is = plugin.getResource("config.yml")) {
            if (is == null) return;
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(is, StandardCharsets.UTF_8));
            boolean changed = false;
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key)) continue; // only leaf values
                // contains(key, true) ignores Bukkit's auto-loaded jar defaults, so this
                // checks the user's actual file (otherwise every key looks "present").
                if (!cfg.contains(key, true)) {
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

    private void validateConfig(FileConfiguration cfg) {
        boolean hasErrors = false;

        double ratio = cfg.getDouble("anticheat.xray_stone_ore_ratio", 0.15);
        if (ratio < 0.0 || ratio > 1.0) {
            plugin.getLogger().warning("[Config] Invalid xray_stone_ore_ratio: " + ratio + ". Using 0.15");
            cfg.set("anticheat.xray_stone_ore_ratio", 0.15);
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

    // Transaction latency: ticks between ping packets per player (read once at startup)
    public long transactionIntervalTicks() {
        return cfg.getLong("anticheat.transaction_interval_ticks", Constants.TRANSACTION_INTERVAL_TICKS);
    }

    // Exempt Bedrock (Geyser/Floodgate) players from checks — their client physics differ
    public boolean exemptBedrockPlayers() { return cfg.getBoolean("anticheat.exempt_bedrock_players", true); }
    // Exempt legacy clients (via ViaVersion) below the given protocol — opt-in, off by default
    public boolean exemptLegacyClients() { return cfg.getBoolean("anticheat.exempt_legacy_clients", false); }
    public int legacyProtocolThreshold() { return cfg.getInt("anticheat.legacy_protocol_threshold", 767); }

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
    // FP-prone movement micro-heuristics — off by default (opt-in), see config.yml notes.
    public boolean noSlowDetectionEnabled() { return cfg.getBoolean("anticheat.noslow_detection", false); }
    public boolean jesusDetectionEnabled() { return cfg.getBoolean("anticheat.jesus_detection", false); }
    public boolean spiderDetectionEnabled() { return cfg.getBoolean("anticheat.spider_detection", false); }
    public boolean stepDetectionEnabled() { return cfg.getBoolean("anticheat.step_detection", false); }
    public boolean teleportDetectionEnabled() { return cfg.getBoolean("anticheat.teleport_detection", true); }
    public boolean elytraDetectionEnabled() { return cfg.getBoolean("anticheat.elytra_detection", true); }
    public boolean vehicleChecksEnabled() { return cfg.getBoolean("anticheat.vehicle_checks", true); }
    // Off by default: the condition cannot be satisfied by a vanilla-computed crit, so it
    // only fires on damage events synthesised by other plugins. See config.yml.
    public boolean criticalsDetectionEnabled() { return cfg.getBoolean("anticheat.criticals_detection", false); }
    // Off by default: vanilla allows attacking with the main hand while an offhand shield
    // is raised, so "attack while isBlocking" fires on ordinary sword+shield play.
    public boolean autoBlockDetectionEnabled() { return cfg.getBoolean("anticheat.autoblock_detection", false); }
    // Off by default — the displacement measurement needs rework before it is FP-safe.
    public boolean velocityDetectionEnabled() { return cfg.getBoolean("anticheat.velocity_detection", false); }
    public int velocityViolations() { return cfg.getInt("anticheat.thresholds.velocity_violations", Constants.VELOCITY_VIOLATIONS); }
    public double velocityMinApplyRatio() { return cfg.getDouble("anticheat.thresholds.velocity_min_apply_ratio", Constants.VELOCITY_MIN_APPLY_RATIO); }
    public boolean fastBreakDetectionEnabled() { return cfg.getBoolean("anticheat.fastbreak_detection", true); }
    public boolean inventoryChecksEnabled() { return cfg.getBoolean("anticheat.inventory_checks", true); }
    public boolean inventoryMoveDetectionEnabled() { return cfg.getBoolean("anticheat.inventorymove_detection", true); }
    public boolean chestStealerDetectionEnabled() { return cfg.getBoolean("anticheat.cheststealer_detection", true); }
    public boolean fastUseDetectionEnabled() { return cfg.getBoolean("anticheat.fastuse_detection", true); }
    public boolean bowSpamDetectionEnabled() { return cfg.getBoolean("anticheat.bowspam_detection", true); }
    public boolean autoTotemDetectionEnabled() { return cfg.getBoolean("anticheat.autototem_detection", true); }
    public boolean crasherDetectionEnabled() { return cfg.getBoolean("anticheat.crasher_detection", true); }
    public boolean packetFloodDetectionEnabled() { return cfg.getBoolean("anticheat.packetflood_detection", true); }
    public int packetFloodMaxPerSecond() { return cfg.getInt("anticheat.packetflood_max_per_second", 500); }
    public int packetFloodWindows() { return cfg.getInt("anticheat.packetflood_windows", Constants.PACKETFLOOD_WINDOWS); }

    // ---- Calibration thresholds (phase 4.4): each defaults to its Constants value, so the
    // behaviour is unchanged unless an admin overrides it in config.yml (no rebuild needed).
    public int groundSpoofViolations() { return cfg.getInt("anticheat.thresholds.groundspoof_violations", Constants.GROUNDSPOOF_VIOLATIONS); }
    public int noSlowViolations() { return cfg.getInt("anticheat.thresholds.noslow_violations", Constants.NOSLOW_VIOLATIONS); }
    public double noSlowSpeedMultiplier() { return cfg.getDouble("anticheat.thresholds.noslow_speed_multiplier", Constants.NOSLOW_SPEED_MULTIPLIER); }
    public int jesusViolations() { return cfg.getInt("anticheat.thresholds.jesus_violations", Constants.JESUS_VIOLATIONS); }
    public int spiderViolations() { return cfg.getInt("anticheat.thresholds.spider_violations", Constants.SPIDER_VIOLATIONS); }
    public int stepViolations() { return cfg.getInt("anticheat.thresholds.step_violations", Constants.STEP_VIOLATIONS); }
    public double stepMaxHeight() { return cfg.getDouble("anticheat.thresholds.step_max_height", Constants.STEP_MAX_HEIGHT); }
    public int elytraViolations() { return cfg.getInt("anticheat.thresholds.elytra_violations", Constants.ELYTRA_VIOLATIONS); }
    public int scaffoldViolations() { return cfg.getInt("anticheat.thresholds.scaffold_violations", Constants.SCAFFOLD_VIOLATIONS); }
    public int boatFlyViolations() { return cfg.getInt("anticheat.thresholds.boatfly_violations", Constants.BOATFLY_VIOLATIONS); }
    // Rate checks need repeated windows over the limit: a plugin breaking several blocks in
    // one action, or a bundle of place packets, crosses a per-second cap once without anyone
    // cheating. See WorldChecker.
    // X-Ray: how many separate deposits the ore must come from before a count over the
    // threshold is treated as evidence. One vein is never proof, however big it is.
    public int xrayMinVeins() { return cfg.getInt("anticheat.xray_min_veins", 3); }
    // Count only ore that was hidden in rock when broken. Ore taken off an open cave wall
    // was seen, not located — and clearing a cave produces the same statistics as X-Ray.
    public boolean xrayRequireHidden() { return cfg.getBoolean("anticheat.xray_require_hidden", true); }
    public int nukerViolations() { return cfg.getInt("anticheat.thresholds.nuker_violations", 3); }
    public int fastPlaceViolations() { return cfg.getInt("anticheat.thresholds.fastplace_violations", 3); }
    public int killAuraMultiViolations() { return cfg.getInt("anticheat.thresholds.killaura_multi_violations", 2); }
    public int vehicleSpeedViolations() { return cfg.getInt("anticheat.thresholds.vehicle_speed_violations", Constants.VEHICLE_SPEED_VIOLATIONS); }
    public int criticalsViolations() { return cfg.getInt("anticheat.thresholds.criticals_violations", Constants.CRITICALS_VIOLATIONS); }
    public int reachViolations() { return cfg.getInt("anticheat.thresholds.reach_violations", Constants.REACH_VIOLATIONS); }
    public int killAuraAngleViolations() { return cfg.getInt("anticheat.thresholds.killaura_angle_violations", Constants.KILLAURA_ANGLE_VIOLATIONS); }
    public int autoBlockViolations() { return cfg.getInt("anticheat.thresholds.autoblock_violations", Constants.AUTOBLOCK_VIOLATIONS); }
    public int fastBreakViolations() { return cfg.getInt("anticheat.thresholds.fastbreak_violations", Constants.FASTBREAK_VIOLATIONS); }
    public double fastBreakTolerance() { return cfg.getDouble("anticheat.thresholds.fastbreak_tolerance", Constants.FASTBREAK_TOLERANCE); }
    public int inventoryMoveViolations() { return cfg.getInt("anticheat.thresholds.inventorymove_violations", Constants.INVENTORYMOVE_VIOLATIONS); }
    public double inventoryMoveMinSpeed() { return cfg.getDouble("anticheat.thresholds.inventorymove_min_speed", Constants.INVENTORYMOVE_MIN_SPEED); }
    public int chestStealerMinClicks() { return cfg.getInt("anticheat.thresholds.cheststealer_min_clicks", Constants.CHESTSTEALER_MIN_CLICKS); }
    public long chestStealerMaxIntervalMs() { return cfg.getLong("anticheat.thresholds.cheststealer_max_interval_ms", Constants.CHESTSTEALER_MAX_INTERVAL_MS); }
    public long chestStealerMinIntervalMs() { return cfg.getLong("anticheat.thresholds.cheststealer_min_interval_ms", Constants.CHESTSTEALER_MIN_INTERVAL_MS); }
    public int fastUseViolations() { return cfg.getInt("anticheat.thresholds.fastuse_violations", Constants.FASTUSE_VIOLATIONS); }
    public long fastUseMinIntervalMs() { return cfg.getLong("anticheat.thresholds.fastuse_min_interval_ms", Constants.FASTUSE_MIN_INTERVAL_MS); }
    public int bowSpamViolations() { return cfg.getInt("anticheat.thresholds.bowspam_violations", Constants.BOWSPAM_VIOLATIONS); }
    public long bowSpamMinIntervalMs() { return cfg.getLong("anticheat.thresholds.bowspam_min_interval_ms", Constants.BOWSPAM_MIN_INTERVAL_MS); }
    public double bowSpamMinForce() { return cfg.getDouble("anticheat.thresholds.bowspam_min_force", Constants.BOWSPAM_MIN_FORCE); }
    public long autoTotemMaxReactionMs() { return cfg.getLong("anticheat.thresholds.autototem_max_reaction_ms", Constants.AUTOTOTEM_MAX_REACTION_MS); }
    public boolean combatChecksEnabled() { return cfg.getBoolean("anticheat.combat_checks", true); }
    public boolean reachDetectionEnabled() { return cfg.getBoolean("anticheat.reach_detection", true); }
    public double reachDistance() { return cfg.getDouble("anticheat.reach_distance", 4.0); }
    public boolean killAuraDetectionEnabled() { return cfg.getBoolean("anticheat.killaura_detection", true); }
    public double killAuraMaxAngle() { return cfg.getDouble("anticheat.killaura_max_angle", 75.0); }
    public int killAuraMultiTargets() { return cfg.getInt("anticheat.killaura_multi_targets", 3); }
    public boolean killAuraPlayersOnly() { return cfg.getBoolean("anticheat.killaura_players_only", true); }
    public boolean worldChecksEnabled() { return cfg.getBoolean("anticheat.world_checks", true); }
    public boolean nukerDetectionEnabled() { return cfg.getBoolean("anticheat.nuker_detection", true); }
    public int nukerMaxBreaksPerSecond() { return cfg.getInt("anticheat.nuker_max_breaks_per_second", 25); }
    public boolean fastPlaceDetectionEnabled() { return cfg.getBoolean("anticheat.fastplace_detection", true); }
    public int fastPlaceMaxPerSecond() { return cfg.getInt("anticheat.fastplace_max_per_second", 12); }
    public boolean scaffoldDetectionEnabled() { return cfg.getBoolean("anticheat.scaffold_detection", true); }
    public double scaffoldMaxAngle() { return cfg.getDouble("anticheat.scaffold_max_angle", 80.0); }
    public boolean packetChecksEnabled() { return cfg.getBoolean("anticheat.packet_checks", true); }
    public boolean autoClickerDetectionEnabled() { return cfg.getBoolean("anticheat.autoclicker_detection", true); }
    public int autoClickerMaxCps() { return cfg.getInt("anticheat.autoclicker_max_cps", 25); }
    public boolean autoClickerConsistencyEnabled() { return cfg.getBoolean("anticheat.autoclicker_consistency", false); }
    public int autoClickerMinSamples() { return cfg.getInt("anticheat.autoclicker_min_samples", 15); }
    public int autoClickerMinCps() { return cfg.getInt("anticheat.autoclicker_min_cps", 2); }
    public int autoClickerMaxDeviationMs() { return cfg.getInt("anticheat.autoclicker_max_deviation_ms", 30); }
    public double autoClickerMaxCv() { return cfg.getDouble("anticheat.autoclicker_max_cv", 0.30); }
    public double autoClickerMaxOutlierRatio() { return cfg.getDouble("anticheat.autoclicker_max_outlier_ratio", 0.06); }
    public int autoClickerMinSignals() { return cfg.getInt("anticheat.autoclicker_min_signals", 3); }
    public boolean badPacketsDetectionEnabled() { return cfg.getBoolean("anticheat.badpackets_detection", true); }
    public boolean timerDetectionEnabled() { return cfg.getBoolean("anticheat.timer_detection", true); }
    public long timerMaxBalanceMs() { return cfg.getLong("anticheat.timer_max_balance_ms", 200L); }
    public long timerSustainedMs() { return cfg.getLong("anticheat.timer_sustained_ms", Constants.TIMER_SUSTAINED_MS); }
    // KillAura rotation GCD (experimental, off by default — calibrate with debug_mode)
    public boolean killAuraRotationDetectionEnabled() { return cfg.getBoolean("anticheat.killaura_rotation_detection", false); }
    public int killAuraRotationSamples() { return cfg.getInt("anticheat.killaura_rotation_samples", 20); }
    public long killAuraRotationMinGcd() { return cfg.getLong("anticheat.killaura_rotation_min_gcd", 8000L); }
    // AimSnap: robotic snap-to-target-and-back rotation (catches rotation-spoofing scaffold/killaura)
    public boolean aimSnapDetectionEnabled() { return cfg.getBoolean("anticheat.aimsnap_detection", true); }
    public double aimSnapMinAngle() { return cfg.getDouble("anticheat.aimsnap_min_angle", 40.0); }
    public double aimSnapReturnAngle() { return cfg.getDouble("anticheat.aimsnap_return_angle", 15.0); }
    public long aimSnapWindowMs() { return cfg.getLong("anticheat.aimsnap_window_ms", 3000L); }
    public int aimSnapThreshold() { return cfg.getInt("anticheat.aimsnap_threshold", 3); }

    // XRay settings
    public int xrayTimewindowSeconds() { return cfg.getInt("anticheat.xray_timewindow_seconds", 60); }
    public List<String> xrayExcludedOres() { return cfg.getStringList("anticheat.xray_excluded_ores"); }
    public double xrayStoneOreRatio() { return cfg.getDouble("anticheat.xray_stone_ore_ratio", 0.15); }
    /** Hot path (every block break) — backed by the cached set. */
    public boolean isXrayExemptWorld(String worldName) { return xrayExemptWorldsSet.contains(worldName); }
    public int xrayRareCombinedThreshold() { return cfg.getInt("anticheat.xray_rare_combined_threshold", Constants.XRAY_RARE_COMBINED_THRESHOLD); }

    public int xrayThreshold(String oreType) {
        String key = oreType.toLowerCase().replace("_ore", "").replace("deepslate_", "");
        if (key.equals("ancient_debris")) return cfg.getInt("anticheat.xray_thresholds.ancient_debris", 3);
        return cfg.getInt("anticheat.xray_thresholds." + key, 10);
    }

    // Whitelist
    public boolean opsBypass() { return cfg.getBoolean("anticheat.ops_bypass", false); }
    public List<String> anticheatWhitelistPlayers() { return cfg.getStringList("anticheat.whitelist_players"); }
    public List<String> anticheatWhitelistGroups() { return cfg.getStringList("anticheat.whitelist_groups"); }
    /** Hot path (every movement, hit and block break) — backed by the cached set. */
    public boolean isWhitelistedPlayer(java.util.UUID playerId) {
        return whitelistPlayersSet.contains(playerId.toString());
    }

    // Movement speed thresholds
    public double speedThresholdWalk() { return cfg.getDouble("anticheat.speed_thresholds.walk", 0.4); }
    public double speedThresholdSprint() { return cfg.getDouble("anticheat.speed_thresholds.sprint", 0.6); }
    public double speedThresholdFly() { return cfg.getDouble("anticheat.speed_thresholds.fly", 1.5); }
    public double flyThreshold() { return cfg.getDouble("anticheat.speed_thresholds.vertical", 3.5); }
    public double teleportThreshold() { return cfg.getDouble("anticheat.speed_thresholds.teleport", 15.0); }
    public int speedViolationsThreshold() { return cfg.getInt("anticheat.speed_thresholds.violations_before_alert", 5); }
    public int flyViolationsThreshold() { return cfg.getInt("anticheat.speed_thresholds.fly_violations_before_alert", 10); }
    // Elytra/Riptide ceilings in blocks per second. Server-specific: item plugins and
    // custom rockets shift what is legitimately reachable, so these are configurable
    // rather than compiled in.
    public double elytraMaxSpeed() { return cfg.getDouble("anticheat.speed_thresholds.elytra_bps", Constants.ELYTRA_MAX_SPEED); }
    public double riptideMaxSpeed() { return cfg.getDouble("anticheat.speed_thresholds.riptide_bps", Constants.RIPTIDE_MAX_SPEED); }

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
    public List<String> restrictedWorldOres() { return cfg.getStringList("anticheat.restricted_world_ores"); }
    public boolean isRestrictedWorld(String worldName) { return restrictedWorldsSet.contains(worldName); }

    // Whitelist management
    public void addWhitelistPlayer(String uuid) {
        List<String> list = new ArrayList<>(anticheatWhitelistPlayers());
        if (!list.contains(uuid)) { list.add(uuid); cfg.set("anticheat.whitelist_players", list); rebuildCaches(); asyncSaver.saveAsync(); }
    }
    public void removeWhitelistPlayer(String uuid) {
        List<String> list = new ArrayList<>(anticheatWhitelistPlayers());
        if (list.remove(uuid)) { cfg.set("anticheat.whitelist_players", list); rebuildCaches(); asyncSaver.saveAsync(); }
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
    public void saveSyncOnShutdown() { asyncSaver.saveSyncOnShutdown(); }
}
