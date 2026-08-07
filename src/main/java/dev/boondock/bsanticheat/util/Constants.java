package dev.boondock.bsanticheat.util;

/**
 * Central constants for BSAntiCheat plugin.
 */
public final class Constants {

    private Constants() {}

    // ==================== DATABASE ====================
    /** Shared prefix of every check's DB log type ({@code anticheat_<check>}). */
    public static final String LOG_TYPE_PREFIX = "anticheat_";
    /**
     * Log-type prefixes owned by the XRay alert family. Everything else under
     * {@link #LOG_TYPE_PREFIX} belongs to the movement alert family, so the two clear
     * commands stay complete without a hand-maintained per-check list.
     */
    public static final java.util.List<String> XRAY_LOG_TYPE_PREFIXES =
            java.util.List.of("anticheat_xray", "anticheat_restricted_zone");
    public static final int DB_MAX_BATCH_SIZE = 1000;
    public static final int DB_MAX_QUEUE_SIZE = 10000;
    public static final int DB_DEFAULT_POOL_SIZE = 5;
    public static final int DB_DEFAULT_MIN_IDLE = 1;
    public static final long DB_DEFAULT_CONNECTION_TIMEOUT_MS = 10000L;
    public static final int DB_FLUSH_INTERVAL_SECONDS = 30;

    // ==================== DISCORD WEBHOOK ====================
    public static final long DISCORD_MIN_REQUEST_DELAY_MS = 2000L;
    public static final int DISCORD_MAX_QUEUE_SIZE = 50;
    public static final int DISCORD_CONNECT_TIMEOUT_MS = 5000;
    public static final int DISCORD_READ_TIMEOUT_MS = 10000;

    // ==================== MOVEMENT CHECKER ====================
    // Phase 1: event sampling removed — every PlayerMoveEvent is checked.
    public static final double MOVEMENT_MIN_TIME_DELTA = 0.01;

    // ==================== XRAY DETECTOR ====================
    public static final long XRAY_PLACED_BLOCK_EXPIRY_MS = 3600000L;
    public static final long XRAY_CLEANUP_INTERVAL_TICKS = 6000L;
    public static final int XRAY_MAX_PLACED_BLOCKS_SIZE = 10000;
    // Raised from 20: cave miners legitimately break the ores they SEE plus little stone,
    // so a small sample flags instantly (21 stone + 3 diamonds = 14%). A meaningful ratio
    // needs a real tunnel-mining sample.
    public static final int XRAY_MIN_STONE_FOR_RATIO_CHECK = 60;
    public static final int XRAY_MAX_PLAYER_ENTRIES = 5000;
    // Combined rare-ore count (diamond + emerald + ancient debris) that triggers an
    // alert even when no single rare ore exceeded its individual threshold. Raised from
    // 8 after live data: beacon/efficiency deepslate mining legitimately clears that.
    public static final int XRAY_RARE_COMBINED_THRESHOLD = 12;

    // ==================== TRANSACTION LATENCY ====================
    // Ticks between transaction pings per player. Every 2 ticks (10/s) still resolves
    // latency far finer than the 15s keep-alive getPing() is derived from, at half the
    // packet overhead of the previous every-tick default.
    public static final long TRANSACTION_INTERVAL_TICKS = 2L;

    // ==================== ALERT MANAGER ====================
    public static final long ALERT_COOLDOWN_MS = 300000L;
    public static final long ALERT_CLEANUP_MS = 1800000L;

    // ==================== MOVEMENT SPEEDS ====================
    public static final double SPEED_POTION_MULTIPLIER_PER_LEVEL = 0.2;
    public static final double SOUL_SPEED_MULTIPLIER = 0.3;
    public static final double SNEAKING_SPEED_MULTIPLIER = 0.3;
    // Swift Sneak raises the sneak multiplier by 0.15 per level (level 3 = 0.75x walking)
    public static final double SWIFT_SNEAK_MULTIPLIER_PER_LEVEL = 0.15;
    public static final double SWIMMING_SPEED_MULTIPLIER = 0.8;
    public static final double CLIMBING_SPEED_MULTIPLIER = 0.5;
    public static final double CREATIVE_FLY_MULTIPLIER = 2.0;
    public static final double ICE_SPEED_MULTIPLIER = 1.7;
    public static final double BLUE_ICE_SPEED_MULTIPLIER = 2.6;
    public static final double DOLPHINS_GRACE_MULTIPLIER = 4.0;
    // Depth Strider I/II/III remove ~1/3 of the water drag per level
    public static final double DEPTH_STRIDER_MULTIPLIER_PER_LEVEL = 0.33;

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

    // Elytra/Riptide: consecutive over-speed samples before flagging (speeds above are b/s)
    public static final int ELYTRA_VIOLATIONS = 3;
    // Vehicle checks: consecutive samples before flagging Boat-Fly / vehicle speed
    public static final int BOATFLY_VIOLATIONS = 10;
    public static final int VEHICLE_SPEED_VIOLATIONS = 5;
    // Ice boating is legitimately very fast (blue ice: 40+ b/s cruising, ~70 at launch)
    public static final double VEHICLE_ICE_SPEED_MULTIPLIER = 5.0;
    public static final double VEHICLE_BLUE_ICE_SPEED_MULTIPLIER = 8.0;

    // Consecutive impossible (on-ground while airborne) samples before flagging GroundSpoof
    public static final int GROUNDSPOOF_VIOLATIONS = 4;
    // NoSlow: allowed fraction of walk speed while using an item, and consecutive samples.
    // Cap raised to 0.8 so normal walking-with-item (and transitions) don't false-flag.
    public static final double NOSLOW_SPEED_MULTIPLIER = 0.8;
    public static final int NOSLOW_VIOLATIONS = 5;
    // Jesus / Spider / Step — thresholds raised after live testing showed jumping next to a
    // wall / stepping up blocks false-flagged Spider/Step at the old low counts.
    public static final int JESUS_VIOLATIONS = 8;
    public static final int SPIDER_VIOLATIONS = 8;
    public static final double STEP_MAX_HEIGHT = 0.75; // vanilla auto-step is 0.6
    public static final int STEP_VIOLATIONS = 5;
    // KillAura multi-aura: window for counting distinct targets hit
    public static final long KILLAURA_MULTI_WINDOW_MS = 250L;
    // Reach / KillAura angle: consecutive suspicious hits before flagging. Single hits are
    // noisy (latency moves both hitboxes; flick hits are judged against stale rotation).
    public static final int REACH_VIOLATIONS = 3;
    // Slack on top of a player's actual entity_interaction_range attribute (vanilla 3.0)
    public static final double REACH_ATTRIBUTE_SLACK = 1.0;
    public static final int KILLAURA_ANGLE_VIOLATIONS = 3;
    // Scaffold: consecutive "not looking at block" places before flagging
    public static final int SCAFFOLD_VIOLATIONS = 3;
    // Criticals (crit while on ground) / AutoBlock (attack while shielding)
    public static final int CRITICALS_VIOLATIONS = 3;
    public static final int AUTOBLOCK_VIOLATIONS = 2;
    // Velocity / AntiKnockback: ignore velocities below this (b/tick, not a real knockback);
    // evaluate displacement after this many ticks; flag when applied < expected * ratio.
    public static final double VELOCITY_MIN_KB = 0.1;
    public static final long VELOCITY_EVAL_DELAY_TICKS = 3L;
    public static final int VELOCITY_VIOLATIONS = 3;
    public static final double VELOCITY_MIN_APPLY_RATIO = 0.33;
    // FastBreak: per-block break time vs. expected. Only digs expected to take at least
    // MIN_EXPECTED are judged; flag when the actual time is below expected * TOLERANCE.
    public static final long FASTBREAK_MIN_EXPECTED_MS = 300L;
    public static final double FASTBREAK_TOLERANCE = 0.7;
    public static final int FASTBREAK_VIOLATIONS = 3;

    // ==================== INVENTORY CHECKER ====================
    // InventoryMove: sustained walking speed (blocks/move) with an open container GUI
    public static final double INVENTORYMOVE_MIN_SPEED = 0.15;
    public static final int INVENTORYMOVE_VIOLATIONS = 8;
    // ChestStealer: this many consecutive container clicks each under the interval
    public static final long CHESTSTEALER_MAX_INTERVAL_MS = 40L;
    // Intervals below this are physically impossible for separate human clicks AND for
    // any real autoclicker (that would be >100 CPS) — they only occur when the network
    // delivered several clicks in one bundle. Such pairs are ignored, not counted.
    public static final long CHESTSTEALER_MIN_INTERVAL_MS = 10L;
    public static final int CHESTSTEALER_MIN_CLICKS = 6;
    // FastUse: fastest legit consumable (dried kelp) takes ~800ms
    public static final long FASTUSE_MIN_INTERVAL_MS = 600L;
    public static final int FASTUSE_VIOLATIONS = 2;
    // BowSpam: a full bow draw takes 1000ms; only near-full-charge shots are counted
    public static final long BOWSPAM_MIN_INTERVAL_MS = 700L;
    public static final float BOWSPAM_MIN_FORCE = 0.9f;
    public static final int BOWSPAM_VIOLATIONS = 3;
    // AutoTotem: inventory-click totem refill faster than any human reaction after a pop
    public static final long AUTOTOTEM_MAX_REACTION_MS = 150L;

    // Timer: the balance must stay over the limit this long before it counts. A bundle of
    // packets delivered together spikes it for a few hundred ms; a timer hack holds it.
    public static final long TIMER_SUSTAINED_MS = 1000L;
    // PacketFlood: consecutive one-second windows over the limit before flagging. One
    // window is a connection catching up after a stall; an attack floods every window.
    public static final int PACKETFLOOD_WINDOWS = 2;
    // Grace after a teleport/join/world change: chunk loading stalls the CLIENT, which
    // then flushes its queued packets in one burst. The movement checks have always had
    // these windows; the packet checks had none.
    public static final long PACKET_GRACE_MS = 5000L;

    // ==================== CRASH PROTECTION ====================
    // Generous multiples of the vanilla limits (books: 100 pages / ~1024 chars per page)
    public static final int CRASHER_MAX_BOOK_PAGES = 150;
    public static final int CRASHER_MAX_BOOK_PAGE_CHARS = 2048;
    public static final long CRASHER_MAX_BOOK_TOTAL_CHARS = 100000L;
    public static final int CRASHER_MAX_SIGN_LINE_CHARS = 512;

    // ==================== CONFIG ====================
    public static final int CONFIG_VERSION = 1;
    public static final String DEFAULT_LANGUAGE = "en";
    public static final String DEFAULT_SQLITE_PATH = "plugins/BSAntiCheat/anticheat.db";

    // ==================== UPDATE CHECKER ====================
    public static final long UPDATE_CHECKER_DELAY_TICKS = 60L;

    // ==================== METRICS ====================
    public static final int BSTATS_PLUGIN_ID = 32112;
}
