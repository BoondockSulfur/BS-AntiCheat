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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Configuration manager for BSAntiCheat.
 * Contains only anticheat-related settings.
 */
public class PluginConfig {

    private final JavaPlugin plugin;
    // Copy-on-write: the published configuration is never mutated after it is assigned.
    // Values are read from region threads, async tasks and Netty threads, and a
    // YamlConfiguration is backed by plain LinkedHashMaps, so a set() on one thread while
    // another reads (or serialises for a save) is a data race. Every change builds a copy
    // under mutationLock and publishes it through this volatile field instead.
    private volatile FileConfiguration cfg;
    private final Object mutationLock = new Object();
    private final AsyncConfigSaver asyncSaver;

    // Hot-path membership caches. These lists are consulted on every movement, every hit
    // and every block break, and cfg.getStringList() allocates a fresh ArrayList on each
    // call — far too expensive there. Rebuilt on load/reload and on every mutation below.
    private volatile Set<String> whitelistPlayersSet = Set.of();
    private volatile List<String> whitelistGroupsList = List.of();
    private volatile Set<String> xrayExemptWorldsSet = Set.of();
    private volatile Set<String> restrictedWorldsSet = Set.of();

    // Whether THIS PLUGIN has changed a config value since the file was last read.
    //
    // The shutdown save only writes when this is set. An unconditional save would make the
    // plugin the last writer of a file it had not edited: an admin who changes a threshold
    // in config.yml while the server runs, without /bsac reload, would have the edit
    // overwritten by the in-memory values at the next stop. Only the whitelist and
    // ore-exclusion commands, and the validator repairing an invalid value, set it.
    private final AtomicBoolean pluginModified = new AtomicBoolean(false);

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.asyncSaver = new AsyncConfigSaver(plugin, this::snapshotYaml);
        FileConfiguration fresh = plugin.getConfig();
        mergeDefaults(fresh);
        boolean repaired = validateConfig(fresh);
        synchronized (mutationLock) {
            this.cfg = fresh;
            rebuildCaches();
        }
        if (repaired) markAndSave();
    }

    public void reload() {
        // Changes whose save is still queued must reach the file before it is re-read;
        // otherwise the reload drops them and resets pluginModified.
        asyncSaver.flushPending();
        plugin.reloadConfig();
        FileConfiguration fresh = plugin.getConfig();
        mergeDefaults(fresh);
        boolean repaired = validateConfig(fresh);
        synchronized (mutationLock) {
            this.cfg = fresh;
            rebuildCaches();
            // Memory and file agree again, so nothing of ours is pending.
            pluginModified.set(false);
        }
        if (repaired) markAndSave();
    }

    /** The current configuration as YAML, serialised under the mutation lock. */
    private String snapshotYaml() {
        synchronized (mutationLock) {
            return cfg.saveToString();
        }
    }

    /** A private, mutable copy of {@code src} with the same defaults. */
    private static FileConfiguration copyOf(FileConfiguration src) {
        YamlConfiguration copy = new YamlConfiguration();
        try {
            copy.loadFromString(src.saveToString());
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            throw new IllegalStateException("Config could not be copied", e);
        }
        if (src.getDefaults() != null) copy.setDefaults(src.getDefaults());
        return copy;
    }

    /**
     * Apply a change to a copy of the current configuration and publish the copy.
     * Nothing is published or saved when {@code change} reports that it changed nothing.
     */
    private void mutate(Predicate<FileConfiguration> change) {
        synchronized (mutationLock) {
            FileConfiguration copy = copyOf(cfg);
            if (!change.test(copy)) return;
            cfg = copy;
            rebuildCaches();
        }
        markAndSave();
    }

    /** {@link #mutate} for a string list: {@code change} edits the list and returns true if it did. */
    private void mutateList(String path, Predicate<List<String>> change) {
        // Cheap pre-check on the published list, so a no-op command costs no copy.
        if (!change.test(new ArrayList<>(cfg.getStringList(path)))) return;
        mutate(copy -> {
            // Re-applied inside the lock: another change may have landed in between.
            List<String> current = new ArrayList<>(copy.getStringList(path));
            if (!change.test(current)) return false;
            copy.set(path, current);
            return true;
        });
    }

    /** Rebuild the hot-path lookup sets from the current config. */
    private void rebuildCaches() {
        whitelistPlayersSet = toSet(cfg.getStringList("anticheat.whitelist_players"));
        whitelistGroupsList = toList(cfg.getStringList("anticheat.whitelist_groups"));
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

    private static List<String> toList(List<String> values) {
        List<String> list = new ArrayList<>(values.size());
        for (String v : values) {
            if (v != null) list.add(v);
        }
        return List.copyOf(list);
    }

    /**
     * Sections whose KEYS are the admin's data rather than the plugin's settings.
     *
     * <p>Merging cannot help here, because it cannot tell "missing because the plugin added
     * it" from "missing because I deleted it". The shipped punishment tier is keyed by its VL
     * threshold, so an admin who changes 40 to 10 — which everyone does, 40 is far out of
     * reach — has deleted key "40" as far as the merge can see, and it puts the tier back
     * with the default kick message next to their own. That is the "the plugin resets my kick
     * message" report: the edited line survives, the original returns beside it and keeps
     * firing. Same for an ore removed from xray_thresholds.
     *
     * <p>Below these paths a key is only filled in while the section itself is absent, i.e.
     * on a config that predates it entirely.
     */
    private static final List<String> ADMIN_DEFINED_SECTIONS = List.of(
            "anticheat.punishments.tiers",
            "anticheat.xray_thresholds");

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
                if (isAdminDefined(cfg, key)) continue;
                if (!cfg.contains(key, true)) {
                    cfg.set(key, defaults.get(key));
                    // ...along with the reasoning that ships above it. A merged key arrives
                    // as a bare value otherwise, so an admin updating the plugin gets every
                    // new switch without a word about what it is for, while a fresh install
                    // gets the full explanation.
                    List<String> comments = defaults.getComments(key);
                    if (!comments.isEmpty()) cfg.setComments(key, comments);
                    changed = true;
                }
            }
            // Keys an earlier migration imported from PerformanceAnalyzer's own features.
            // Checked on every start; it only removes what the defaults do not contain.
            List<String> foreign = ConfigMigrator.removeForeignKeys(cfg, defaults);
            if (!foreign.isEmpty()) {
                plugin.getLogger().info("[Config] Removed settings that belong to another plugin: "
                        + String.join(", ", foreign));
            }
            if (changed) {
                plugin.getLogger().info("[Config] Added missing config keys from defaults.");
            }
            if (changed || !foreign.isEmpty()) {
                // Not published yet, so no other thread can see this object.
                asyncSaver.writeNow(cfg.saveToString());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Config] Could not merge default config keys: " + e.getMessage());
        }
    }

    /**
     * True when this key lives inside a section the admin owns and that section already
     * exists in their file — so its contents are theirs, complete, and none of the shipped
     * entries may be added back.
     */
    private static boolean isAdminDefined(FileConfiguration cfg, String key) {
        for (String section : ADMIN_DEFINED_SECTIONS) {
            if (key.startsWith(section + ".") && cfg.contains(section, true)) return true;
        }
        return false;
    }

    /**
     * Allowed range per numeric setting. A value outside it is replaced by the default with
     * one warning: most of these fail silently otherwise — a violation count of 0 flags on
     * every sample, a window or rate limit of 0 disables its check, a negative pool size
     * stops the database from starting. Bounds are inclusive.
     */
    private record Range(String path, double min, double max, Number def) {}

    private static final double INF = Double.MAX_VALUE;

    private static final List<Range> RANGES = List.of(
            // General / database
            new Range("anticheat.lag_exempt_tps", 0, 20, 18.0),
            new Range("anticheat.transaction_interval_ticks", 1, 200, Constants.TRANSACTION_INTERVAL_TICKS),
            new Range("anticheat.legacy_protocol_threshold", 1, INF, 767),
            new Range("database.pool.max_pool_size", 1, 100, Constants.DB_DEFAULT_POOL_SIZE),
            new Range("database.pool.minimum_idle", 0, 100, Constants.DB_DEFAULT_MIN_IDLE),
            // HikariCP rejects connection timeouts below 250 ms.
            new Range("database.pool.connection_timeout_ms", 250, INF, Constants.DB_DEFAULT_CONNECTION_TIMEOUT_MS),
            // 0 keeps logs forever (auto-cleanup off).
            new Range("database.retention_days", 0, INF, 30),
            // Packet
            new Range("anticheat.packetflood_max_per_second", 1, INF, 500),
            new Range("anticheat.packetflood_windows", 1, INF, Constants.PACKETFLOOD_WINDOWS),
            // Streak counts: 0 would flag on every single sample.
            new Range("anticheat.thresholds.groundspoof_violations", 1, INF, Constants.GROUNDSPOOF_VIOLATIONS),
            new Range("anticheat.thresholds.noslow_violations", 1, INF, Constants.NOSLOW_VIOLATIONS),
            new Range("anticheat.thresholds.jesus_violations", 1, INF, Constants.JESUS_VIOLATIONS),
            new Range("anticheat.thresholds.spider_violations", 1, INF, Constants.SPIDER_VIOLATIONS),
            new Range("anticheat.thresholds.step_violations", 1, INF, Constants.STEP_VIOLATIONS),
            new Range("anticheat.thresholds.sustained_ascent_violations", 1, INF, Constants.SUSTAINED_ASCENT_VIOLATIONS),
            new Range("anticheat.thresholds.elytra_violations", 1, INF, Constants.ELYTRA_VIOLATIONS),
            new Range("anticheat.thresholds.scaffold_violations", 1, INF, Constants.SCAFFOLD_VIOLATIONS),
            new Range("anticheat.thresholds.nuker_violations", 1, INF, 3),
            new Range("anticheat.thresholds.fastplace_violations", 1, INF, 3),
            new Range("anticheat.thresholds.killaura_multi_violations", 1, INF, 2),
            new Range("anticheat.thresholds.boatfly_violations", 1, INF, Constants.BOATFLY_VIOLATIONS),
            new Range("anticheat.thresholds.vehicle_speed_violations", 1, INF, Constants.VEHICLE_SPEED_VIOLATIONS),
            new Range("anticheat.thresholds.reach_violations", 1, INF, Constants.REACH_VIOLATIONS),
            new Range("anticheat.thresholds.killaura_angle_violations", 1, INF, Constants.KILLAURA_ANGLE_VIOLATIONS),
            new Range("anticheat.thresholds.criticals_violations", 1, INF, Constants.CRITICALS_VIOLATIONS),
            new Range("anticheat.thresholds.autoblock_violations", 1, INF, Constants.AUTOBLOCK_VIOLATIONS),
            new Range("anticheat.thresholds.velocity_violations", 1, INF, Constants.VELOCITY_VIOLATIONS),
            new Range("anticheat.thresholds.fastbreak_violations", 1, INF, Constants.FASTBREAK_VIOLATIONS),
            new Range("anticheat.thresholds.inventorymove_violations", 1, INF, Constants.INVENTORYMOVE_VIOLATIONS),
            new Range("anticheat.thresholds.cheststealer_min_clicks", 1, INF, Constants.CHESTSTEALER_MIN_CLICKS),
            new Range("anticheat.thresholds.fastuse_violations", 1, INF, Constants.FASTUSE_VIOLATIONS),
            new Range("anticheat.thresholds.bowspam_violations", 1, INF, Constants.BOWSPAM_VIOLATIONS),
            // Threshold factors and distances
            new Range("anticheat.thresholds.noslow_speed_multiplier", 0.01, INF, Constants.NOSLOW_SPEED_MULTIPLIER),
            new Range("anticheat.thresholds.step_max_height", 0.01, INF, Constants.STEP_MAX_HEIGHT),
            new Range("anticheat.thresholds.sustained_ascent_min_decay", 0, INF, Constants.SUSTAINED_ASCENT_MIN_DECAY),
            new Range("anticheat.thresholds.reach_max_latency_blocks", 0, INF, Constants.REACH_MAX_LATENCY_BLOCKS),
            new Range("anticheat.thresholds.reach_max_ping_ms", 1, INF, Constants.REACH_MAX_PING_MS),
            new Range("anticheat.thresholds.velocity_min_apply_ratio", 0.01, 1, Constants.VELOCITY_MIN_APPLY_RATIO),
            new Range("anticheat.thresholds.fly_hover_max_drop", 0, INF, Constants.FLY_HOVER_MAX_DROP),
            new Range("anticheat.thresholds.fastbreak_tolerance", 0.01, 1, Constants.FASTBREAK_TOLERANCE),
            new Range("anticheat.thresholds.inventorymove_min_speed", 0.01, INF, Constants.INVENTORYMOVE_MIN_SPEED),
            new Range("anticheat.thresholds.cheststealer_max_interval_ms", 1, INF, Constants.CHESTSTEALER_MAX_INTERVAL_MS),
            new Range("anticheat.thresholds.cheststealer_min_interval_ms", 0, INF, Constants.CHESTSTEALER_MIN_INTERVAL_MS),
            new Range("anticheat.thresholds.fastuse_min_interval_ms", 1, INF, Constants.FASTUSE_MIN_INTERVAL_MS),
            new Range("anticheat.thresholds.bowspam_min_interval_ms", 1, INF, Constants.BOWSPAM_MIN_INTERVAL_MS),
            new Range("anticheat.thresholds.bowspam_min_force", 0, 1, 0.9),
            new Range("anticheat.thresholds.autototem_max_reaction_ms", 1, INF, Constants.AUTOTOTEM_MAX_REACTION_MS),
            // Combat. Below vanilla survival reach (3.0) every ordinary hit is flagged.
            new Range("anticheat.reach_distance", 3.0, INF, 4.0),
            new Range("anticheat.killaura_max_angle", 1, 180, 75.0),
            // One target is every hit; "multi" needs at least two.
            new Range("anticheat.killaura_multi_targets", 2, INF, 3),
            new Range("anticheat.killaura_rotation_samples", 2, INF, 20),
            new Range("anticheat.killaura_rotation_min_gcd", 0, INF, 8000L),
            // World
            new Range("anticheat.nuker_max_breaks_per_second", 1, INF, 25),
            new Range("anticheat.fastplace_max_per_second", 1, INF, 12),
            new Range("anticheat.scaffold_max_angle", 1, 180, 80.0),
            // Packet-level
            new Range("anticheat.autoclicker_max_cps", 1, INF, 25),
            new Range("anticheat.autoclicker_min_samples", 2, INF, 15),
            new Range("anticheat.autoclicker_min_cps", 0, INF, 2),
            new Range("anticheat.autoclicker_max_deviation_ms", 0, INF, 30),
            new Range("anticheat.autoclicker_max_cv", 0, INF, 0.30),
            new Range("anticheat.autoclicker_max_outlier_ratio", 0, 1, 0.06),
            new Range("anticheat.autoclicker_min_signals", 1, 3, 3),
            new Range("anticheat.timer_max_balance_ms", 1, INF, 200L),
            new Range("anticheat.timer_sustained_ms", 0, INF, Constants.TIMER_SUSTAINED_MS),
            new Range("anticheat.timer_min_growth_ms", 0, INF, Constants.TIMER_MIN_GROWTH_MS),
            new Range("anticheat.timer_max_rtt_compensation_ms", 0, INF, Constants.TIMER_MAX_RTT_COMPENSATION_MS),
            new Range("anticheat.aimsnap_min_angle", 1, 180, 40.0),
            new Range("anticheat.aimsnap_return_angle", 0, 180, 15.0),
            new Range("anticheat.aimsnap_window_ms", 1, INF, 3000L),
            new Range("anticheat.aimsnap_threshold", 1, INF, 3),
            new Range("anticheat.thresholds.pingspoof_violations", 1, INF, Constants.PINGSPOOF_VIOLATIONS),
            // Below one client frame at low frame rates the comparison is noise.
            new Range("anticheat.thresholds.pingspoof_divergence_ms", 100, INF, Constants.PINGSPOOF_DIVERGENCE_MS),
            // Crystal / anchor aura
            new Range("anticheat.thresholds.crystalaura_min_break_ms", 0, INF, Constants.CRYSTALAURA_MIN_BREAK_MS),
            new Range("anticheat.thresholds.crystalaura_max_breaks_per_second", 1, INF, Constants.CRYSTALAURA_MAX_BREAKS_PER_SECOND),
            new Range("anticheat.thresholds.crystalaura_max_angle", 1, 180, Constants.CRYSTALAURA_MAX_ANGLE),
            new Range("anticheat.thresholds.crystalaura_violations", 1, INF, Constants.CRYSTALAURA_VIOLATIONS),
            new Range("anticheat.thresholds.anchoraura_min_interval_ms", 0, INF, Constants.ANCHORAURA_MIN_INTERVAL_MS),
            new Range("anticheat.thresholds.anchoraura_violations", 1, INF, Constants.ANCHORAURA_VIOLATIONS),
            // NoFall / Sprint / Mace
            new Range("anticheat.thresholds.nofall_violations", 1, INF, Constants.NOFALL_VIOLATIONS),
            new Range("anticheat.thresholds.nofall_min_extra_distance", 1, INF, Constants.NOFALL_MIN_EXTRA_DISTANCE),
            new Range("anticheat.thresholds.sprint_min_duration_ms", 250, INF, Constants.SPRINT_MIN_DURATION_MS),
            new Range("anticheat.thresholds.sprint_omni_max_angle", 60, 180, Constants.SPRINT_OMNI_MAX_ANGLE),
            new Range("anticheat.thresholds.sprint_omni_violations", 1, INF, Constants.SPRINT_OMNI_VIOLATIONS),
            new Range("anticheat.thresholds.mace_violations", 1, INF, Constants.MACE_VIOLATIONS),
            new Range("anticheat.thresholds.mace_min_excess", 1, INF, Constants.MACE_MIN_EXCESS),
            // XRay
            new Range("anticheat.xray_timewindow_seconds", 1, INF, 60),
            new Range("anticheat.xray_min_veins", 1, INF, 3),
            new Range("anticheat.xray_max_count_per_vein", 1, INF, Constants.XRAY_MAX_COUNT_PER_VEIN),
            new Range("anticheat.xray_stone_window_seconds", 1, INF, Constants.XRAY_STONE_WINDOW_SECONDS),
            new Range("anticheat.xray_min_stone_for_ratio", 1, INF, Constants.XRAY_MIN_STONE_FOR_RATIO_CHECK),
            new Range("anticheat.xray_profile_window_seconds", 1, INF, Constants.XRAY_PROFILE_WINDOW_SECONDS),
            new Range("anticheat.xray_profile_min_sample", 1, INF, Constants.XRAY_PROFILE_MIN_SAMPLE),
            new Range("anticheat.xray_profile_max_y_stddev", 0.01, INF, Constants.XRAY_PROFILE_MAX_Y_STDDEV),
            new Range("anticheat.xray_profile_min_corridor", 0, 1, Constants.XRAY_PROFILE_MIN_CORRIDOR),
            new Range("anticheat.xray_profile_ore_band", 0, INF, Constants.XRAY_PROFILE_ORE_BAND),
            new Range("anticheat.xray_stone_ore_ratio", 0, 1, 0.15),
            new Range("anticheat.xray_rare_combined_threshold", 1, INF, Constants.XRAY_RARE_COMBINED_THRESHOLD),
            // Movement speeds
            new Range("anticheat.speed_thresholds.walk", 0.01, INF, 0.4),
            new Range("anticheat.speed_thresholds.sprint", 0.01, INF, 0.6),
            new Range("anticheat.speed_thresholds.fly", 0.01, INF, 1.5),
            new Range("anticheat.speed_thresholds.vertical", 0.01, INF, 3.5),
            new Range("anticheat.speed_thresholds.teleport", 0.01, INF, 15.0),
            new Range("anticheat.speed_thresholds.violations_before_alert", 1, INF, 5),
            new Range("anticheat.speed_thresholds.fly_violations_before_alert", 1, INF, 10),
            new Range("anticheat.speed_thresholds.elytra_bps", 1, INF, Constants.ELYTRA_MAX_SPEED),
            new Range("anticheat.speed_thresholds.riptide_bps", 1, INF, Constants.RIPTIDE_MAX_SPEED));

    /**
     * Replace out-of-range values by their default, one warning each.
     *
     * @return true when a value was repaired, i.e. the file needs writing
     */
    private boolean validateConfig(FileConfiguration cfg) {
        boolean hasErrors = false;

        for (Range range : RANGES) {
            hasErrors |= repairIfOutOfRange(cfg, range);
        }

        // Per-ore thresholds: admin-defined keys, so each present value is checked.
        ConfigurationSection ores = cfg.getConfigurationSection("anticheat.xray_thresholds");
        if (ores != null) {
            for (String ore : ores.getKeys(false)) {
                hasErrors |= repairIfOutOfRange(cfg, new Range("anticheat.xray_thresholds." + ore, 1, INF,
                        ore.equals("ancient_debris") ? 3 : 10));
            }
        }

        // Pool: minimum_idle above max_pool_size makes HikariCP refuse to start.
        int poolMax = cfg.getInt("database.pool.max_pool_size", Constants.DB_DEFAULT_POOL_SIZE);
        if (cfg.getInt("database.pool.minimum_idle", Constants.DB_DEFAULT_MIN_IDLE) > poolMax) {
            plugin.getLogger().warning("[Config] database.pool.minimum_idle is larger than max_pool_size ("
                    + poolMax + "). Using " + poolMax);
            cfg.set("database.pool.minimum_idle", poolMax);
            hasErrors = true;
        }

        // decay_seconds: 0 is a valid choice (decay off) but means violation levels never
        // go down, so every false positive counts towards a punishment for the whole uptime.
        int decay = cfg.getInt("anticheat.punishments.decay_seconds", 300);
        if (decay < 0) {
            plugin.getLogger().warning("[Config] Invalid anticheat.punishments.decay_seconds: " + decay
                    + " (must be 0 or more). Using 300");
            cfg.set("anticheat.punishments.decay_seconds", 300);
            hasErrors = true;
        } else if (decay == 0 && cfg.getBoolean("anticheat.punishments.enabled", false)) {
            plugin.getLogger().warning("[Config] anticheat.punishments.decay_seconds is 0: violation levels"
                    + " never decay, so false positives add up until the next restart.");
        }

        // Not repaired, only reported: an existing value is the admin's decision and the
        // merge above only ever ADDS keys, so a default that changes after release never
        // reaches a server that already has the key. This default changed because the check
        // misreads vanilla shift-drag (see config.yml); silently flipping an existing value
        // would overrule a choice somebody may have made deliberately.
        if (cfg.getBoolean("anticheat.cheststealer_detection", false)) {
            plugin.getLogger().warning("[Config] cheststealer_detection is ON. It ships OFF"
                    + " since 1.0.6: the check reads vanilla shift-drag as inhuman clicking"
                    + " and produces false alerts. Set it to false in"
                    + " config.yml unless you know why you want it.");
        }

        return hasErrors;
    }

    /** Validate one numeric setting; absent keys are left to the getter's default. */
    private boolean repairIfOutOfRange(FileConfiguration cfg, Range range) {
        if (!cfg.contains(range.path(), true)) return false;
        Object raw = cfg.get(range.path());
        if (raw instanceof Number n) {
            double v = n.doubleValue();
            if (!Double.isNaN(v) && v >= range.min() && v <= range.max()) return false;
        }
        String bounds = range.max() == INF
                ? "at least " + formatBound(range.min())
                : formatBound(range.min()) + " to " + formatBound(range.max());
        plugin.getLogger().warning("[Config] Invalid " + range.path() + ": " + raw
                + " (allowed: " + bounds + "). Using " + range.def());
        cfg.set(range.path(), range.def());
        return true;
    }

    private static String formatBound(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
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
    // Off by default: an uncalibrated heuristic. It watches for a climb that does not decay
    // the way gravity requires — see MovementChecker#checkSustainedAscent — and covers the
    // ascent the hover check does not count. Calibrate with debug_mode before enabling it.
    public boolean sustainedAscentDetectionEnabled() { return cfg.getBoolean("anticheat.sustained_ascent_detection", false); }
    public int sustainedAscentViolations() { return cfg.getInt("anticheat.thresholds.sustained_ascent_violations", Constants.SUSTAINED_ASCENT_VIOLATIONS); }
    /** Vertical speed a climbing player must shed per tick to read as thrown rather than flown. */
    public double sustainedAscentMinDecay() { return cfg.getDouble("anticheat.thresholds.sustained_ascent_min_decay", Constants.SUSTAINED_ASCENT_MIN_DECAY); }
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
    /** Off by default — the interval model cannot tell a bot from a vanilla shift-drag; see config.yml. */
    public boolean chestStealerDetectionEnabled() { return cfg.getBoolean("anticheat.cheststealer_detection", false); }
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
    /** How much one deposit may contribute to a per-ore threshold (see Constants). */
    public int xrayMaxCountPerVein() {
        return cfg.getInt("anticheat.xray_max_count_per_vein", Constants.XRAY_MAX_COUNT_PER_VEIN);
    }
    /** Stone breaks needed before the shape veto may fire. */
    public int xrayProfileMinSample() {
        return cfg.getInt("anticheat.xray_profile_min_sample", Constants.XRAY_PROFILE_MIN_SAMPLE);
    }
    /** How far back the shape profile looks. */
    public int xrayProfileWindowSeconds() {
        return cfg.getInt("anticheat.xray_profile_window_seconds", Constants.XRAY_PROFILE_WINDOW_SECONDS);
    }
    public double xrayProfileMaxYStdDev() {
        return cfg.getDouble("anticheat.xray_profile_max_y_stddev", Constants.XRAY_PROFILE_MAX_Y_STDDEV);
    }
    public double xrayProfileMinCorridor() {
        return cfg.getDouble("anticheat.xray_profile_min_corridor", Constants.XRAY_PROFILE_MIN_CORRIDOR);
    }
    public int xrayProfileOreBand() {
        return cfg.getInt("anticheat.xray_profile_ore_band", Constants.XRAY_PROFILE_ORE_BAND);
    }
    /** How far back the spoil is counted when deciding whether a player is searching. */
    public int xrayStoneWindowSeconds() {
        return cfg.getInt("anticheat.xray_stone_window_seconds", Constants.XRAY_STONE_WINDOW_SECONDS);
    }
    /** How much spoil in that window marks the player as searching. */
    public int xrayMinStoneForRatio() {
        return cfg.getInt("anticheat.xray_min_stone_for_ratio", Constants.XRAY_MIN_STONE_FOR_RATIO_CHECK);
    }
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
    /** Deterministic protocol violations beyond rotation (see PacketChecker); needs badpackets_detection. */
    public boolean badPacketsExtendedEnabled() { return cfg.getBoolean("anticheat.badpackets_extended_detection", true); }
    // PingSpoof: off by default — a heuristic around transaction replies, see TransactionManager.
    public boolean pingSpoofDetectionEnabled() { return cfg.getBoolean("anticheat.pingspoof_detection", false); }
    public int pingSpoofViolations() { return cfg.getInt("anticheat.thresholds.pingspoof_violations", Constants.PINGSPOOF_VIOLATIONS); }
    public long pingSpoofDivergenceMs() { return cfg.getLong("anticheat.thresholds.pingspoof_divergence_ms", Constants.PINGSPOOF_DIVERGENCE_MS); }
    // CrystalAura / AnchorAura: off by default — uncalibrated heuristics, see CrystalChecker.
    public boolean crystalAuraDetectionEnabled() { return cfg.getBoolean("anticheat.crystalaura_detection", false); }
    public boolean anchorAuraDetectionEnabled() { return cfg.getBoolean("anticheat.anchoraura_detection", false); }
    public long crystalAuraMinBreakMs() { return cfg.getLong("anticheat.thresholds.crystalaura_min_break_ms", Constants.CRYSTALAURA_MIN_BREAK_MS); }
    public int crystalAuraMaxBreaksPerSecond() { return cfg.getInt("anticheat.thresholds.crystalaura_max_breaks_per_second", Constants.CRYSTALAURA_MAX_BREAKS_PER_SECOND); }
    public double crystalAuraMaxAngle() { return cfg.getDouble("anticheat.thresholds.crystalaura_max_angle", Constants.CRYSTALAURA_MAX_ANGLE); }
    public int crystalAuraViolations() { return cfg.getInt("anticheat.thresholds.crystalaura_violations", Constants.CRYSTALAURA_VIOLATIONS); }
    public long anchorAuraMinIntervalMs() { return cfg.getLong("anticheat.thresholds.anchoraura_min_interval_ms", Constants.ANCHORAURA_MIN_INTERVAL_MS); }
    public int anchorAuraViolations() { return cfg.getInt("anticheat.thresholds.anchoraura_violations", Constants.ANCHORAURA_VIOLATIONS); }
    // NoFall: off by default — a heuristic around the server's own landing detection, see
    // MovementChecker#checkLanding. Calibrate with debug_mode before enabling it.
    public boolean noFallDetectionEnabled() { return cfg.getBoolean("anticheat.nofall_detection", false); }
    public int noFallViolations() { return cfg.getInt("anticheat.thresholds.nofall_violations", Constants.NOFALL_VIOLATIONS); }
    public double noFallMinExtraDistance() { return cfg.getDouble("anticheat.thresholds.nofall_min_extra_distance", Constants.NOFALL_MIN_EXTRA_DISTANCE); }
    // Sprint: sprinting that a 1.21.2+ client ends by itself (food level 6 or less, blindness).
    public boolean sprintDetectionEnabled() { return cfg.getBoolean("anticheat.sprint_detection", true); }
    // Omni-sprint: off by default — momentum on ice and sharp turns point motion away from the view.
    public boolean sprintOmniDetectionEnabled() { return cfg.getBoolean("anticheat.sprint_omni_detection", false); }
    public long sprintMinDurationMs() { return cfg.getLong("anticheat.thresholds.sprint_min_duration_ms", Constants.SPRINT_MIN_DURATION_MS); }
    public double sprintOmniMaxAngle() { return cfg.getDouble("anticheat.thresholds.sprint_omni_max_angle", Constants.SPRINT_OMNI_MAX_ANGLE); }
    public int sprintOmniViolations() { return cfg.getInt("anticheat.thresholds.sprint_omni_violations", Constants.SPRINT_OMNI_VIOLATIONS); }
    // Mace: off by default — compares the fall distance a smash is computed from with the
    // measured descent, see CombatChecker#checkMaceSmash.
    public boolean maceDetectionEnabled() { return cfg.getBoolean("anticheat.mace_detection", false); }
    public int maceViolations() { return cfg.getInt("anticheat.thresholds.mace_violations", Constants.MACE_VIOLATIONS); }
    public double maceMinExcess() { return cfg.getDouble("anticheat.thresholds.mace_min_excess", Constants.MACE_MIN_EXCESS); }
    public boolean timerDetectionEnabled() { return cfg.getBoolean("anticheat.timer_detection", true); }
    public long timerMaxBalanceMs() { return cfg.getLong("anticheat.timer_max_balance_ms", 200L); }
    /** Ceiling on the reach check's latency allowance, in blocks. */
    public double reachMaxLatencyBlocks() {
        return cfg.getDouble("anticheat.thresholds.reach_max_latency_blocks", Constants.REACH_MAX_LATENCY_BLOCKS);
    }
    /** Above this measured round trip the reach check stands down. */
    public int reachMaxPingMs() {
        return cfg.getInt("anticheat.thresholds.reach_max_ping_ms", Constants.REACH_MAX_PING_MS);
    }
    /** How much vertical speed a hover run may lose before it reads as falling. */
    public double flyHoverMaxDrop() {
        return cfg.getDouble("anticheat.thresholds.fly_hover_max_drop", Constants.FLY_HOVER_MAX_DROP);
    }
    /** How much the balance must still gain across the excursion (see Constants). */
    public long timerMinGrowthMs() {
        return cfg.getLong("anticheat.timer_min_growth_ms", Constants.TIMER_MIN_GROWTH_MS);
    }
    /** Ceiling on the round-trip extension of the excursion window. */
    public long timerMaxRttCompensationMs() {
        return cfg.getLong("anticheat.timer_max_rtt_compensation_ms", Constants.TIMER_MAX_RTT_COMPENSATION_MS);
    }
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
    /**
     * Whitelisted LuckPerms groups. Immutable and cached: read on every exemption check. The
     * same instance is returned until the list changes, so callers may cache results
     * against its identity.
     */
    public List<String> anticheatWhitelistGroups() { return whitelistGroupsList; }
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
        mutateList("anticheat.whitelist_players", list -> !list.contains(uuid) && list.add(uuid));
    }
    /**
     * Remove a whitelist entry exactly as it is stored (case-insensitive, so a UUID typed in
     * upper case still matches). Needs no name lookup, so it also works for players the
     * server no longer has in its user cache.
     *
     * @return true when an entry was removed
     */
    public boolean removeWhitelistPlayer(String entry) {
        boolean present = anticheatWhitelistPlayers().stream().anyMatch(e -> e.equalsIgnoreCase(entry));
        if (!present) return false;
        mutateList("anticheat.whitelist_players", list -> list.removeIf(e -> e.equalsIgnoreCase(entry)));
        return true;
    }
    public void addWhitelistGroup(String group) {
        mutateList("anticheat.whitelist_groups", list -> !list.contains(group) && list.add(group));
    }
    public void removeWhitelistGroup(String group) {
        mutateList("anticheat.whitelist_groups", list -> list.remove(group));
    }
    public void addExcludedOre(String ore) {
        String upper = ore.toUpperCase();
        mutateList("anticheat.xray_excluded_ores", list -> !list.contains(upper) && list.add(upper));
    }
    public void removeExcludedOre(String ore) {
        String upper = ore.toUpperCase();
        mutateList("anticheat.xray_excluded_ores", list -> list.remove(upper));
    }

    // Discord
    public boolean discordEnabled() { return cfg.getBoolean("discord.enabled", false); }
    public String discordWebhookUrl() { return cfg.getString("discord.webhook_url", ""); }
    public boolean discordAlertType(String type) { return cfg.getBoolean("discord.alert_types." + type, true); }

    // Silent players
    public List<String> silentPlayers() { return cfg.getStringList("alerts.silent_players"); }
    public void setSilentPlayers(List<String> players) {
        List<String> copy = new ArrayList<>(players);
        mutate(c -> {
            c.set("alerts.silent_players", copy);
            return true;
        });
    }

    // Save

    /** Record that the plugin changed a value, then persist it. */
    private void markAndSave() {
        pluginModified.set(true);
        asyncSaver.saveAsync();
    }

    /**
     * Write the config on shutdown — but only if the plugin has something of its own to
     * write. Otherwise the file on disk is the admin's, and it stays theirs.
     */
    public void saveSyncOnShutdown() {
        if (!pluginModified.get()) return;
        asyncSaver.saveSyncOnShutdown();
    }
}
