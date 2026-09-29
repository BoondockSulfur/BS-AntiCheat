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
    // Spacing between two requests to one webhook URL: Discord allows about 30 per minute.
    public static final long DISCORD_MIN_REQUEST_DELAY_MS = 2100L;
    public static final int DISCORD_MAX_QUEUE_SIZE = 50;
    public static final int DISCORD_CONNECT_TIMEOUT_MS = 5000;
    public static final int DISCORD_READ_TIMEOUT_MS = 10000;

    // ==================== MOVEMENT CHECKER ====================
    // Every PlayerMoveEvent is checked; there is no event sampling.
    public static final double MOVEMENT_MIN_TIME_DELTA = 0.01;
    // Longest gap between two movement packets that is still treated as ordinary play. A
    // vanilla client sends one every tick even while standing still, so silence beyond this
    // is a stalled connection: what arrives next is the backlog, carrying several ticks of
    // travel in one event. Matches the Timer check's PACKET_GAP_MS, which exists for the
    // same reason on the packet side.
    public static final long MOVEMENT_MAX_GAP_MS = 400L;

    // ==================== XRAY DETECTOR ====================
    public static final long XRAY_PLACED_BLOCK_EXPIRY_MS = 3600000L;
    public static final long XRAY_CLEANUP_INTERVAL_TICKS = 6000L;
    public static final int XRAY_MAX_PLACED_BLOCKS_SIZE = 10000;
    // Cave miners legitimately break the ores they SEE plus little stone, so on a small
    // sample the ratio is high without any X-Ray. A meaningful ratio needs a real
    // tunnel-mining sample.
    /** How much stone marks a player as visibly SEARCHING, over {@link #XRAY_STONE_WINDOW_SECONDS}. */
    public static final int XRAY_MIN_STONE_FOR_RATIO_CHECK = 60;
    /**
     * How far back the spoil is counted when deciding whether a player is searching.
     *
     * <p>Kept equal to the ore window. Widening it is tempting — digging and extracting are
     * separated in time, so a miner who tunnels for minutes and then spends one minute pulling
     * ore out shows almost no spoil in the window that judges the ore. But it does not
     * separate anything: an X-Ray user tunnels straight from vein to vein and digs just as
     * much, so a wider window hands THEM the searching exemption too. What distinguishes an
     * honest miner is not how much they dug but that most of their windows find nothing,
     * which spoil volume alone cannot see.
     */
    public static final int XRAY_STONE_WINDOW_SECONDS = 60;
    /**
     * How much a single deposit may contribute to a per-ore threshold.
     * Emptying one thick vein is a single piece of knowledge however many blocks come out of
     * it, and the hidden-ore test counts every block after the first as hidden (each one is
     * exposed by breaking its neighbour). Without a cap two fat veins clear a threshold of
     * ten on their own, which is what {@code xray_min_veins} was meant to prevent and cannot,
     * because the count it guards stays block-based.
     */
    public static final int XRAY_MAX_COUNT_PER_VEIN = 2;
    // ---- Shape vetoes (see MiningProfile for what each value describes) ----
    /** Stone breaks needed before the shape of the digging says anything at all. */
    public static final int XRAY_PROFILE_MIN_SAMPLE = 300;
    /** How far back the shape profile looks. Wider than the ratio window, on purpose. */
    public static final int XRAY_PROFILE_WINDOW_SECONDS = 300;
    /** How tightly strip mining holds one level (standard deviation of Y). */
    public static final double XRAY_PROFILE_MAX_Y_STDDEV = 2.0;
    /** How much of it is corridor rather than open excavation. */
    public static final double XRAY_PROFILE_MIN_CORRIDOR = 0.70;
    /** How far the judged ore may sit from the corridor level, in blocks. */
    public static final int XRAY_PROFILE_ORE_BAND = 4;
    public static final int XRAY_MAX_PLAYER_ENTRIES = 5000;
    // Combined rare-ore count (diamond + emerald + ancient debris) that triggers an
    // alert even when no single rare ore exceeded its individual threshold. Set so that
    // beacon/efficiency deepslate mining does not reach it legitimately.
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
    // Elytra: gliding tops out near 3 b/t, but a rocket-assisted dive legitimately reaches
    // 5–6 b/t (100–120 b/s), so the limit sits above that band; a boost bleeding off is not
    // sustained impossible speed.
    public static final double ELYTRA_MAX_SPEED = 140.0;
    // Riptide: vanilla launches the player at (1.5 + 0.5 × level) blocks/tick, so Riptide III
    // alone is 3 b/t = 60 b/s before any sprint or fall momentum is added, so the limit sits
    // above that.
    public static final double RIPTIDE_MAX_SPEED = 75.0;
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
    // Sustained ascent: how many consecutive climbing samples must fail to decay, and how much
    // vertical speed a genuine ballistic rise sheds per tick. Vanilla gravity is 0.08 b/t; the
    // floor is set below that so drag, a slow client and rounding cannot make a real arc look
    // powered. 8 samples is ~0.4s of climbing that gravity cannot account for.
    public static final int SUSTAINED_ASCENT_VIOLATIONS = 8;
    public static final double SUSTAINED_ASCENT_MIN_DECAY = 0.05;
    // NoSlow: allowed fraction of walk speed while using an item, and consecutive samples.
    // 0.8 leaves room for walking-with-item transitions.
    public static final double NOSLOW_SPEED_MULTIPLIER = 0.8;
    public static final int NOSLOW_VIOLATIONS = 5;
    // Jesus / Spider / Step — high enough that jumping next to a wall or stepping up blocks
    // does not reach them.
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
    /** Sprint speed, used to turn a round trip into the distance a target can have moved. */
    public static final double SPRINT_BLOCKS_PER_SECOND = 5.6;
    /** Ceiling on the latency allowance, so the compensation cannot grow without bound. */
    public static final double REACH_MAX_LATENCY_BLOCKS = 3.0;
    /** Above this measured round trip the reach check stands down instead of guessing. */
    public static final int REACH_MAX_PING_MS = 400;
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

    /**
     * How much vertical speed a hover run may LOSE before it counts as falling instead.
     * A hover holds its speed; a ballistic arc through the +-0.08 still band is still being
     * pulled down by gravity, and the apex of a slow arc sits inside that band for many
     * samples. 0.05 is well under one tick of vanilla gravity (0.08), so anything actually
     * falling is excluded while a held altitude is not.
     */
    public static final double FLY_HOVER_MAX_DROP = 0.05;

    // Timer: the balance must stay over the limit this long before it counts. A bundle of
    // packets delivered together spikes it for a few hundred ms; a timer hack holds it.
    public static final long TIMER_SUSTAINED_MS = 1000L;
    /**
     * How much the balance must still GROW across the excursion before it counts as a hack.
     * A connection catching up after a stall drains its backlog and then plateaus; a timer
     * hack keeps gaining every tick it runs. Duration alone cannot tell those apart on a
     * high-latency link.
     */
    public static final long TIMER_MIN_GROWTH_MS = 150L;
    /** Ceiling on how far a measured round trip may stretch the excursion window. */
    public static final long TIMER_MAX_RTT_COMPENSATION_MS = 3000L;
    // PacketFlood: consecutive one-second windows over the limit before flagging. One
    // window is a connection catching up after a stall; an attack floods every window.
    public static final int PACKETFLOOD_WINDOWS = 2;
    // Grace after a teleport/join/world change: chunk loading stalls the CLIENT, which
    // then flushes its queued packets in one burst.
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
    /** Download pages shown in the update notice (console and, for operators, in chat). */
    public static final String URL_MODRINTH = "https://modrinth.com/plugin/bs-anticheat";
    /** Empty = no CurseForge page; the notice then shows the Modrinth link only. */
    public static final String URL_CURSEFORGE = "https://www.curseforge.com/minecraft/bukkit-plugins/bs-anticheat";

    // ==================== METRICS ====================
    public static final int BSTATS_PLUGIN_ID = 32112;
}
