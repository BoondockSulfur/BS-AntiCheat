package dev.boondock.bsanticheat.util;

/**
 * Central constants for BSAntiCheat plugin.
 */
public final class Constants {

    private Constants() {}

    // ==================== DATABASE ====================
    public static final int DB_MAX_BATCH_SIZE = 1000;
    public static final int DB_MAX_QUEUE_SIZE = 10000;
    public static final int DB_DEFAULT_POOL_SIZE = 5;
    public static final int DB_DEFAULT_MIN_IDLE = 1;
    public static final long DB_DEFAULT_CONNECTION_TIMEOUT_MS = 10000L;
    public static final int DB_FLUSH_INTERVAL_SECONDS = 30;

    // ==================== DISCORD WEBHOOK ====================
    public static final long DISCORD_MIN_REQUEST_DELAY_MS = 2000L;
    public static final int DISCORD_MAX_QUEUE_SIZE = 50;

    // ==================== MOVEMENT CHECKER ====================
    public static final int MOVEMENT_SAMPLE_RATE = 10;
    public static final double MOVEMENT_MIN_DISTANCE_THRESHOLD = 2.0;
    public static final int MOVEMENT_COUNTER_RESET_THRESHOLD = 1000;
    public static final double MOVEMENT_MIN_TIME_DELTA = 0.01;

    // ==================== XRAY DETECTOR ====================
    public static final long XRAY_PLACED_BLOCK_EXPIRY_MS = 3600000L;
    public static final long XRAY_CLEANUP_INTERVAL_TICKS = 6000L;
    public static final int XRAY_MAX_PLACED_BLOCKS_SIZE = 10000;
    public static final int XRAY_MIN_STONE_FOR_RATIO_CHECK = 20;
    public static final int XRAY_MAX_PLAYER_ENTRIES = 5000;
    // Combined rare-ore count (diamond + emerald + ancient debris) that triggers an
    // alert even when no single rare ore exceeded its individual threshold.
    public static final int XRAY_RARE_COMBINED_THRESHOLD = 8;

    // ==================== ALERT MANAGER ====================
    public static final long ALERT_COOLDOWN_MS = 300000L;
    public static final long ALERT_CLEANUP_MS = 1800000L;

    // ==================== MOVEMENT SPEEDS ====================
    public static final double SPEED_POTION_MULTIPLIER_PER_LEVEL = 0.2;
    public static final double SOUL_SPEED_MULTIPLIER = 0.3;
    public static final double SNEAKING_SPEED_MULTIPLIER = 0.3;
    public static final double SWIMMING_SPEED_MULTIPLIER = 0.8;
    public static final double CLIMBING_SPEED_MULTIPLIER = 0.5;
    public static final double CREATIVE_FLY_MULTIPLIER = 2.0;
    public static final double ICE_SPEED_MULTIPLIER = 1.7;
    public static final double BLUE_ICE_SPEED_MULTIPLIER = 2.6;
    public static final double DOLPHINS_GRACE_MULTIPLIER = 4.0;

    // ==================== VEHICLE SPEEDS ====================
    public static final double HORSE_MAX_SPEED = 15.0;
    public static final double DONKEY_MAX_SPEED = 8.0;
    public static final double LLAMA_MAX_SPEED = 6.0;
    public static final double CAMEL_MAX_SPEED = 10.0;
    public static final double PIG_MAX_SPEED = 5.0;
    public static final double STRIDER_MAX_SPEED = 8.0;
    public static final double BOAT_MAX_SPEED = 10.0;
    public static final double MINECART_MAX_SPEED = 20.0;
    public static final double ELYTRA_MAX_SPEED = 100.0;
    public static final double RIPTIDE_MAX_SPEED = 50.0;
    public static final double OTHER_VEHICLE_MAX_SPEED = 20.0;

    // Consecutive impossible (on-ground while airborne) samples before flagging GroundSpoof
    public static final int GROUNDSPOOF_VIOLATIONS = 4;
    // NoSlow: allowed fraction of walk speed while using an item, and consecutive samples
    public static final double NOSLOW_SPEED_MULTIPLIER = 0.5;
    public static final int NOSLOW_VIOLATIONS = 3;
    // Jesus / Spider / Step
    public static final int JESUS_VIOLATIONS = 5;
    public static final int SPIDER_VIOLATIONS = 3;
    public static final double STEP_MAX_HEIGHT = 0.75; // vanilla auto-step is 0.6
    public static final int STEP_VIOLATIONS = 2;
    // KillAura multi-aura: window for counting distinct targets hit
    public static final long KILLAURA_MULTI_WINDOW_MS = 250L;
    // Scaffold: consecutive "not looking at block" places before flagging
    public static final int SCAFFOLD_VIOLATIONS = 3;

    // ==================== CONFIG ====================
    public static final int CONFIG_VERSION = 1;
    public static final String DEFAULT_LANGUAGE = "en";
    public static final String DEFAULT_SQLITE_PATH = "plugins/BSAntiCheat/anticheat.db";

    // ==================== UPDATE CHECKER ====================
    public static final long UPDATE_CHECKER_DELAY_TICKS = 60L;

    // ==================== METRICS ====================
    public static final int BSTATS_PLUGIN_ID = 32112;
}
