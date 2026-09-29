package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.integration.GeyserHook;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.lang.LanguageManager;
import dev.boondock.bsanticheat.util.Constants;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Detects X-Ray cheating by analyzing ore mining patterns.
 *
 * Detection methods:
 * - Ore-to-stone ratio (too many ores, too little stone)
 * - Time-based ore mining frequency
 * - Rare ore detection (diamonds, ancient debris)
 */
public class XRayDetector implements Listener {

    private final Plugin plugin;
    private final PluginConfig config;
    private final DatabaseManager database;
    private final LanguageManager lang;
    private LuckPermsHook luckPerms;
    private GeyserHook geyser;
    private XRayAlertManager alertManager;
    private ViolationManager violationManager;

    // Excluded ores from config (cached). Immutable and swapped whole on reload, so region
    // threads reading it never see a half-rebuilt (e.g. momentarily empty) set.
    private volatile Set<Material> excludedOres = Set.of();

    // Normalized ore names for restricted worlds (cached). Same swap-on-reload rule.
    private volatile Set<String> normalizedRestrictedOres = Set.of();

    // Track ore mining per player
    private final Map<UUID, List<OreMineEvent>> playerOreMines = new ConcurrentHashMap<>();
    // Time-windowed stone mining per player. Positions, not just timestamps: the SHAPE of the
    // digging is what tells a strip miner from someone who walked to the ore (MiningProfile).
    // Held for the longer of the two windows — the ratio still counts only what falls inside
    // xray_stone_window_seconds, the profile wants the wider sample to say anything at all.
    private final Map<UUID, ConcurrentLinkedDeque<MiningProfile.StoneBreak>> playerStoneMines =
            new ConcurrentHashMap<>();

    // Track player-placed blocks to prevent false positives in restricted worlds
    // Key: Block location hash, Value: Timestamp when placed
    private final Map<String, Long> playerPlacedBlocks = new ConcurrentHashMap<>();

    // Players already told about (once per session) that OP does not exempt them here.
    private final Set<UUID> opNoticeLogged = ConcurrentHashMap.newKeySet();

    // Recently broken block positions, so an open face can be told from one that was just
    // made. Key: block location hash, Value: when it was broken. Server-wide on purpose (see
    // wasVisible): a break by ANY player counts.
    //
    // Bounded by time, not by a global count: every entry is also queued per player in break
    // order (brokenByPlayer), and expired entries are evicted from the head of that queue in
    // O(1) each. A global cap that skips new entries when full could be filled on purpose,
    // leaving fresh tunnels unrecorded so that hidden ore reads as visible.
    private final Map<String, Long> recentlyBroken = new ConcurrentHashMap<>();
    // Per player, in break order. Only ever touched inside brokenByPlayer.compute /
    // computeIfPresent, so the queue itself needs no further locking.
    private final Map<UUID, ArrayDeque<BrokenBlock>> brokenByPlayer = new ConcurrentHashMap<>();

    /** One recorded break, so its recentlyBroken entry can be evicted in order. */
    private record BrokenBlock(String key, long time) {}

    /**
     * Break rate the per-player queue must hold for a whole window: instamining (Efficiency V
     * with Haste II) is about 20 blocks a second. A player above that only loses their own
     * oldest entries, never anyone else's.
     */
    private static final int MAX_BREAKS_PER_SECOND = 20;

    // Valuable ores to track
    private static final Set<Material> VALUABLE_ORES = Set.of(
        Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
        Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
        Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
        Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
        Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
        Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
        Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
        Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
        Material.ANCIENT_DEBRIS
    );

    private static final Set<Material> RARE_ORES = Set.of(
        Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
        Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
        Material.ANCIENT_DEBRIS
    );

    // Common ores form large natural veins (coal/iron/copper/redstone/lapis), so they
    // are excluded from the ore-to-stone ratio check to avoid false positives when a
    // player legitimately mines through such a vein.
    private static final Set<Material> COMMON_ORES = Set.of(
        Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
        Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
        Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
        Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
        Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE
    );

    // Ore blocks this close on every axis are treated as one deposit (see countVeins).
    private static final int VEIN_LINK_DISTANCE = 2;

    /**
     * What counts as spoil — the rock a player moves while searching. This is the denominator
     * of the ore-to-stone ratio and decides whether a player is treated as searching at all,
     * so anything missing here reads as "found ore without digging for it".
     *
     * <p>The nether matters most: mining there moves netherrack, and while that was absent
     * the ratio check could never run in that dimension at all. Ancient debris has a
     * threshold of 3 and is a rare ore, so hunting it produced alerts with nothing able to
     * account for the rock that was moved to find it.
     *
     * <p>Terracotta is the rock of badlands, where gold generates in quantity at surface
     * height; infested stone replaces ordinary stone in mountains and deep underground. Soft
     * blocks (dirt, gravel, sand, clay, moss, sculk) are left out on purpose: they instamine
     * with the right tool, so counting them would make "visibly searching" cheap to fake.
     */
    private static final Set<Material> STONE_TYPES = Set.of(
        Material.STONE,
        Material.DEEPSLATE,
        Material.ANDESITE,
        Material.GRANITE,
        Material.DIORITE,
        Material.TUFF,
        Material.CALCITE,
        Material.DRIPSTONE_BLOCK,
        Material.INFESTED_STONE,
        Material.INFESTED_DEEPSLATE,
        // Badlands
        Material.TERRACOTTA,
        Material.WHITE_TERRACOTTA,
        Material.ORANGE_TERRACOTTA,
        Material.YELLOW_TERRACOTTA,
        Material.BROWN_TERRACOTTA,
        Material.RED_TERRACOTTA,
        Material.LIGHT_GRAY_TERRACOTTA,
        // Nether
        Material.NETHERRACK,
        Material.BASALT,
        Material.SMOOTH_BASALT,
        Material.BLACKSTONE,
        Material.SOUL_SAND,
        Material.SOUL_SOIL,
        // End
        Material.END_STONE,
        // Desert / mesa digging
        Material.SANDSTONE,
        Material.RED_SANDSTONE
    );

    /**
     * How long stone breaks are kept. The ratio and the shape profile read the same deque
     * over different spans, so it has to survive the longer of the two.
     */
    private int retentionSeconds() {
        return Math.max(config.xrayStoneWindowSeconds(), config.xrayProfileWindowSeconds());
    }

    public XRayDetector(Plugin plugin, PluginConfig config, DatabaseManager database, LanguageManager lang) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.lang = lang;
        reloadExcludedOres();
        reloadRestrictedWorldOres();
        startPeriodicCleanup();
    }

    /**
     * Start periodic cleanup task to prevent memory leaks.
     * Runs every 5 minutes and cleans old data for all players.
     * Also enforces size limits to prevent unbounded memory growth.
     */
    private void startPeriodicCleanup() {
        // Run cleanup every 5 minutes (async, real-time interval)
        dev.boondock.bsanticheat.util.Scheduler.runAsyncTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            int[] totalCleaned = {0};
            int playersWithData = 0;

            // Trim and remove-if-empty happen inside computeIfPresent, i.e. under the same lock
            // the break handler holds while appending (compute), so an entry a region thread
            // is adding to can never be dropped as "empty" underneath it.
            for (UUID id : playerOreMines.keySet()) {
                trimPlayer(id, now, totalCleaned);
                if (playerOreMines.containsKey(id)) playersWithData++;
            }
            for (UUID id : brokenByPlayer.keySet()) {
                expireBreaks(id, now);
            }

            // Clean up old player-placed blocks
            long placedBlockCutoff = now - Constants.XRAY_PLACED_BLOCK_EXPIRY_MS;
            int placedBlocksBeforeCleanup = playerPlacedBlocks.size();
            playerPlacedBlocks.entrySet().removeIf(entry -> entry.getValue() < placedBlockCutoff);
            int placedBlocksCleaned = placedBlocksBeforeCleanup - playerPlacedBlocks.size();

            // Enforce size limits to prevent memory issues on large servers.
            // Evict only the entries with the oldest recent activity — clearing the
            // whole map would throw away the evidence of every active suspect at once.
            boolean hitLimit = false;
            if (playerOreMines.size() > Constants.XRAY_MAX_PLAYER_ENTRIES) {
                int toRemove = playerOreMines.size() - Constants.XRAY_MAX_PLAYER_ENTRIES;
                plugin.getLogger().warning("[XRay] playerOreMines exceeded size limit (" + playerOreMines.size() + " > " + Constants.XRAY_MAX_PLAYER_ENTRIES + "), evicting " + toRemove + " oldest entries");
                playerOreMines.entrySet().stream()
                    .sorted(Comparator.comparingLong(e -> newestOreTimestamp(e.getValue())))
                    .limit(toRemove)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(playerOreMines::remove);
                hitLimit = true;
            }
            if (playerStoneMines.size() > Constants.XRAY_MAX_PLAYER_ENTRIES) {
                int toRemove = playerStoneMines.size() - Constants.XRAY_MAX_PLAYER_ENTRIES;
                plugin.getLogger().warning("[XRay] playerStoneMines exceeded size limit (" + playerStoneMines.size() + " > " + Constants.XRAY_MAX_PLAYER_ENTRIES + "), evicting " + toRemove + " oldest entries");
                playerStoneMines.entrySet().stream()
                    .sorted(Comparator.comparingLong(e -> {
                        MiningProfile.StoneBreak last = e.getValue().peekLast();
                        return last != null ? last.time() : 0L;
                    }))
                    .limit(toRemove)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(playerStoneMines::remove);
                hitLimit = true;
            }

            if (config.debugMode() && (totalCleaned[0] > 0 || placedBlocksCleaned > 0 || hitLimit)) {
                plugin.getLogger().info(String.format(java.util.Locale.ROOT, "[XRay Cleanup] Removed %d old mining events, %d placed blocks. Active players: %d, Maps size: ore=%d, stone=%d, broken=%d",
                    totalCleaned[0], placedBlocksCleaned, playersWithData, playerOreMines.size(), playerStoneMines.size(), recentlyBroken.size()));
            }
        }, Constants.XRAY_CLEANUP_INTERVAL_TICKS / 20L, Constants.XRAY_CLEANUP_INTERVAL_TICKS / 20L,
                java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Drop a player's ore and stone records that fell out of their windows, and the entries
     * themselves once empty. Atomic per entry (computeIfPresent).
     *
     * <p>The stone deque has its OWN, longer cutoff — trimming the spoil to the ore window
     * would throw away exactly the digging history that explains the ore.
     */
    private void trimPlayer(UUID id, long now, int[] cleanedCounter) {
        long oreCutoff = now - (config.xrayTimewindowSeconds() * 1000L);
        long stoneCutoff = now - (retentionSeconds() * 1000L);
        playerOreMines.computeIfPresent(id, (k, mines) -> {
            int before = mines.size();
            mines.removeIf(mine -> mine.timestamp < oreCutoff);
            if (cleanedCounter != null) cleanedCounter[0] += before - mines.size();
            return mines.isEmpty() ? null : mines;
        });
        playerStoneMines.computeIfPresent(id, (k, breaks) -> {
            trimStone(breaks, stoneCutoff);
            return breaks.isEmpty() ? null : breaks;
        });
    }

    private static void trimStone(ConcurrentLinkedDeque<MiningProfile.StoneBreak> breaks, long cutoff) {
        MiningProfile.StoneBreak head;
        while ((head = breaks.peekFirst()) != null && head.time() < cutoff) {
            breaks.pollFirst();
        }
    }

    /** Per-player cap on remembered breaks: a full window at the maximum legitimate rate. */
    private int maxBreaksPerPlayer() {
        return Math.max(1, config.xrayTimewindowSeconds()) * MAX_BREAKS_PER_SECOND;
    }

    /**
     * Remember a broken position for {@link #wasVisible}. Expired entries are evicted from the
     * head of the player's queue first; if the player is still at their cap, their own oldest
     * entry makes room. A new break is never skipped.
     */
    void recordBreak(UUID playerId, String key, long now) {
        long cutoff = now - (config.xrayTimewindowSeconds() * 1000L);
        int cap = maxBreaksPerPlayer();
        brokenByPlayer.compute(playerId, (k, queue) -> {
            if (queue == null) queue = new ArrayDeque<>();
            evictExpired(queue, cutoff);
            while (queue.size() >= cap) evict(queue.pollFirst());
            queue.addLast(new BrokenBlock(key, now));
            recentlyBroken.put(key, now);
            return queue;
        });
    }

    private void expireBreaks(UUID playerId, long now) {
        long cutoff = now - (config.xrayTimewindowSeconds() * 1000L);
        brokenByPlayer.computeIfPresent(playerId, (k, queue) -> {
            evictExpired(queue, cutoff);
            return queue.isEmpty() ? null : queue;
        });
    }

    private void evictExpired(ArrayDeque<BrokenBlock> queue, long cutoff) {
        BrokenBlock head;
        while ((head = queue.peekFirst()) != null && head.time() < cutoff) {
            evict(queue.pollFirst());
        }
    }

    /**
     * Forget one break — unless the position was broken again since (by anyone), in which case
     * the newer record owns the map entry and stays.
     */
    private void evict(BrokenBlock b) {
        if (b != null) recentlyBroken.remove(b.key(), b.time());
    }

    /** Number of remembered broken positions, server-wide. For tests. */
    int recentlyBrokenSize() {
        return recentlyBroken.size();
    }

    public void setLuckPerms(LuckPermsHook luckPerms) {
        this.luckPerms = luckPerms;
    }

    public void setGeyser(GeyserHook geyser) {
        this.geyser = geyser;
    }

    public void setAlertManager(XRayAlertManager alertManager) {
        this.alertManager = alertManager;
    }

    public void setViolationManager(ViolationManager violationManager) {
        this.violationManager = violationManager;
    }

    /**
     * Reload all config-derived caches. Call this after the plugin config is reloaded
     * so changes to excluded ores and restricted-world ores take effect without a restart.
     */
    public void reloadConfigCaches() {
        reloadExcludedOres();
        reloadRestrictedWorldOres();
    }

    /**
     * Reload excluded ores from config.
     */
    public void reloadExcludedOres() {
        Set<Material> built = EnumSet.noneOf(Material.class);
        List<String> excluded = config.xrayExcludedOres();
        for (String oreName : excluded) {
            try {
                Material mat = Material.valueOf(oreName.toUpperCase());
                built.add(mat);
                // Also add deepslate variant if not already specified
                String deepslateName = "DEEPSLATE_" + oreName.toUpperCase();
                try {
                    Material deepslate = Material.valueOf(deepslateName);
                    built.add(deepslate);
                } catch (IllegalArgumentException ignored) {}
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("[XRay] Unbekanntes Erz in Ausnahmeliste: " + oreName);
            }
        }
        excludedOres = Set.copyOf(built);
        if (!built.isEmpty() && config.debugMode()) {
            plugin.getLogger().info("[XRay] Ausgenommene Erze: " + built);
        }
    }

    /**
     * Reload and normalize restricted world ores from config.
     * Normalizes ore names to handle both DIAMOND_ORE and DEEPSLATE_DIAMOND_ORE formats.
     */
    private void reloadRestrictedWorldOres() {
        Set<String> built = new HashSet<>();
        List<String> restricted = config.restrictedWorldOres();

        for (String oreName : restricted) {
            // Normalize: remove DEEPSLATE_ prefix and _ORE suffix
            String normalized = oreName.toUpperCase()
                .replace("DEEPSLATE_", "")
                .replace("_ORE", "");

            built.add(normalized);
        }
        normalizedRestrictedOres = Set.copyOf(built);

        if (!built.isEmpty() && config.debugMode()) {
            plugin.getLogger().info("[XRay] Normalized restricted ores: " + built);
        }
    }

    /**
     * Check if an ore is excluded from tracking.
     */
    private boolean isOreExcluded(Material ore) {
        return excludedOres.contains(ore);
    }

    /**
     * Track player-placed blocks to prevent false positives.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();

        // Only track valuable ores (prevents players from placing and mining their own ores)
        if (VALUABLE_ORES.contains(type)) {
            // Memory leak prevention: Check size limit before adding
            if (playerPlacedBlocks.size() >= Constants.XRAY_MAX_PLACED_BLOCKS_SIZE) {
                // Remove oldest entries (simple cleanup)
                long cutoff = System.currentTimeMillis() - Constants.XRAY_PLACED_BLOCK_EXPIRY_MS;
                playerPlacedBlocks.entrySet().removeIf(entry -> entry.getValue() < cutoff);

                // If still too large, skip tracking this block
                if (playerPlacedBlocks.size() >= Constants.XRAY_MAX_PLACED_BLOCKS_SIZE) {
                    plugin.getLogger().warning("[XRay] Placed blocks map at size limit (" +
                        Constants.XRAY_MAX_PLACED_BLOCKS_SIZE + "), skipping block tracking");
                    return;
                }
            }

            String locationKey = getLocationKey(block.getLocation());
            playerPlacedBlocks.put(locationKey, System.currentTimeMillis());

            if (config.debugMode()) {
                plugin.getLogger().info("[XRay] Player " + event.getPlayer().getName() +
                    " placed ore: " + type.name() + " at " + locationKey);
            }
        }
    }

    /**
     * Generate a unique key for a block location.
     */
    static String getLocationKey(org.bukkit.Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!config.xrayDetectionEnabled()) {
            if (config.debugMode()) {
                plugin.getLogger().info("[XRay] Detection deaktiviert, ignoriere Event");
            }
            return;
        }

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        Block block = event.getBlock();
        Material type = block.getType();

        // Fully exempt worlds (resource/farm worlds where mass mining is normal)
        if (config.isXrayExemptWorld(block.getWorld().getName())) {
            return;
        }

        // Every break is remembered briefly, so the exposure test can tell a face that was
        // already open from one that was just broken open (see wasVisible). Recorded BEFORE
        // the player exemptions below: a tunnel dug by a bypassed, creative or Bedrock account
        // is still a fresh opening, and a second account collecting the ore from it must not
        // be excused by it.
        long breakNow = System.currentTimeMillis();
        recordBreak(playerId, getLocationKey(block.getLocation()), breakNow);

        // Skip whitelisted players
        if (isPlayerWhitelisted(player)) {
            if (config.debugMode()) {
                plugin.getLogger().info("[XRay] Spieler " + player.getName() + " ist whitelisted/bypass, ignoriere");
            }
            return;
        }

        // Creative/Spectator are exempt (consistent with all other checks) — a builder
        // instamining an area with ores would otherwise be flagged guaranteed.
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        // Track stone mining (time-windowed). Appended under the map's lock (compute) so the
        // async cleanup cannot drop the deque as empty while it is being added to.
        if (STONE_TYPES.contains(type)) {
            long stoneCutoff = breakNow - (retentionSeconds() * 1000L);
            MiningProfile.StoneBreak stone = new MiningProfile.StoneBreak(
                    breakNow, block.getX(), block.getY(), block.getZ());
            playerStoneMines.compute(playerId, (k, stoneBreaks) -> {
                if (stoneBreaks == null) stoneBreaks = new ConcurrentLinkedDeque<>();
                stoneBreaks.addLast(stone);
                trimStone(stoneBreaks, stoneCutoff);
                return stoneBreaks;
            });
            return;
        }

        // Track ore mining (skip excluded ores)
        if (VALUABLE_ORES.contains(type) && !isOreExcluded(type)) {
            // Check if this block was player-placed (e.g. silk touch ores placed in base)
            // This check applies to ALL worlds to prevent false positives
            String locationKey = getLocationKey(block.getLocation());
            boolean wasPlayerPlaced = playerPlacedBlocks.containsKey(locationKey);

            if (wasPlayerPlaced) {
                // Remove from tracking and skip ALL detection for this block
                playerPlacedBlocks.remove(locationKey);
                if (config.debugMode()) {
                    plugin.getLogger().info("[XRay] " + player.getName() +
                        " mined self-placed ore " + type.name() + " at " + locationKey + " - skipping detection");
                }
                return; // Not a naturally generated ore, skip entirely
            }

            Location mineLoc = block.getLocation().clone();
            boolean visible = wasVisible(block);

            // Check if player is in a restricted world (instant alert zone)
            String worldName = player.getWorld().getName();
            boolean isInRestrictedWorld = config.isRestrictedWorld(worldName);

            // INSTANT ALERT for restricted worlds — once per deposit, and only for ore that was
            // hidden when required, like the regular checks. Per block, a single copper vein
            // walked a player through every punishment tier on its own.
            if (isInRestrictedWorld && isRestrictedOre(type)
                    && !(config.xrayRequireHidden() && visible)
                    && !continuesRestrictedVein(playerId, type, mineLoc)) {
                String locationInfo = String.format(java.util.Locale.ROOT, "%s @ [%d, %d, %d]",
                    worldName,
                    block.getX(), block.getY(), block.getZ());

                String message = lang.format("alert.restricted_zone", player.getName(), type.name(), locationInfo);

                plugin.getLogger().warning("[XRay RESTRICTED] " + message);

                // Immediate violation log (no threshold checking)
                Map<String, Integer> breakdown = new HashMap<>();
                breakdown.put(type.name(), 1);
                List<String> location = new ArrayList<>();
                location.add(type.name() + " @ " + locationInfo);

                logViolation(player, "RESTRICTED_ZONE", message, 1, breakdown, location);

                if (database != null) {
                    database.logAsync(player.getUniqueId(), "anticheat_restricted_zone", 1.0, message);
                }
            }

            // Append, trim and snapshot under the map's lock (compute), which the async cleanup
            // also takes before removing an emptied list — so this event cannot be lost.
            // The snapshot is what the checks read: the live list keeps changing.
            OreMineEvent mineEvent = new OreMineEvent(type, breakNow, mineLoc, visible);
            long cutoff = breakNow - (config.xrayTimewindowSeconds() * 1000L);
            List<OreMineEvent> mines = new ArrayList<>();
            playerOreMines.compute(playerId, (k, live) -> {
                if (live == null) live = new CopyOnWriteArrayList<>();
                live.add(mineEvent);
                live.removeIf(mine -> mine.timestamp < cutoff);
                mines.addAll(live);
                return live;
            });

            if (config.debugMode()) {
                int currentOreCount = (int) mines.stream().filter(m -> m.oreType == type).count();
                plugin.getLogger().info("[XRay] " + player.getName() + " mined " + type.name() +
                    " | Count: " + currentOreCount + "/" + config.xrayThreshold(type.name()) +
                    " | Total ores in window: " + mines.size() +
                    " | Restricted World: " + isInRestrictedWorld);
            }

            // Check for suspicious patterns (normal XRay detection).
            checkSuspiciousPattern(player, playerId, mines);
        } else if (VALUABLE_ORES.contains(type) && isOreExcluded(type)) {
            if (config.debugMode()) {
                plugin.getLogger().info("[XRay] " + player.getName() + " mined excluded ore: " + type.name());
            }
        }
    }

    /**
     * Whether an ore is watched in restricted worlds. An empty restricted_world_ores list means
     * the valuable ores, not every ore: coal, iron, copper, redstone and lapis come in large
     * veins and would flag anyone passing through.
     */
    private boolean isRestrictedOre(Material type) {
        Set<String> restricted = normalizedRestrictedOres;
        if (restricted.isEmpty()) return !COMMON_ORES.contains(type);
        return restricted.contains(type.name().replace("DEEPSLATE_", "").replace("_ORE", ""));
    }

    /**
     * Whether this block belongs to a deposit the player already took ore of the same kind
     * from inside the window — that deposit has already been reported (or was not hidden).
     */
    private boolean continuesRestrictedVein(UUID playerId, Material type, Location loc) {
        List<OreMineEvent> mines = playerOreMines.get(playerId);
        if (mines == null) return false;
        long cutoff = System.currentTimeMillis() - (config.xrayTimewindowSeconds() * 1000L);
        String kind = type.name().replace("DEEPSLATE_", "");
        for (OreMineEvent m : mines) {
            if (m.timestamp < cutoff) continue;
            if (!m.oreType.name().replace("DEEPSLATE_", "").equals(kind)) continue;
            if (sameVein(m.location, loc)) return true;
        }
        return false;
    }

    /**
     * Check player's mining pattern for XRay indicators.
     * Uses three detection methods:
     * 1. Per-ore threshold check (too many of a specific ore; deepslate variants merged)
     * 2. Ore-to-stone ratio (too many rare ores vs stone; common veins excluded)
     * 3. Combined rare-ore count (diamonds + emeralds + ancient debris together)
     *
     * @param player The player being checked
     * @param playerId Player's UUID
     * @param mines List of recent ore mining events
     */
    private void checkSuspiciousPattern(Player player, UUID playerId, List<OreMineEvent> mines) {
        int timewindow = config.xrayTimewindowSeconds();

        // Spoil moved recently. Computed up front because it decides WHICH checks apply, not
        // just how the ratio comes out.
        //
        // Counted over xray_stone_window_seconds, which ships EQUAL to the ore window. A
        // longer one was built and dropped: it fixes the case where a player tunnels for
        // minutes and then spends under a minute extracting, but it hands an X-Ray user the
        // same exemption, because tunnelling from vein to vein digs just as much. The key
        // exists so the trade-off is adjustable, not because the default differs — see
        // Constants.XRAY_STONE_WINDOW_SECONDS. The deque itself is held longer than either,
        // for the shape profile below.
        //
        // Counted from the newest end and stopped at the window edge, so the cost is the
        // breaks inside the ratio window, not the whole (longer) profile retention. Counting
        // by time also ignores stale entries a player who stopped mining stone still holds.
        ConcurrentLinkedDeque<MiningProfile.StoneBreak> stoneBreaks = playerStoneMines.get(playerId);
        long now = System.currentTimeMillis();
        int stoneMined = 0;
        if (stoneBreaks != null) {
            long ratioCutoff = now - (config.xrayStoneWindowSeconds() * 1000L);
            Iterator<MiningProfile.StoneBreak> newestFirst = stoneBreaks.descendingIterator();
            while (newestFirst.hasNext()) {
                if (newestFirst.next().time() < ratioCutoff) break;
                stoneMined++;
            }
        }

        // Someone moving this much stone is visibly SEARCHING, which is the opposite of what
        // X-Ray is for — its whole point is not having to. Strip mining therefore gets judged
        // by hit RATE (check 2) rather than by raw counts (checks 1 and 3): a tunnel through
        // ore-rich rock can pass a per-minute threshold on luck alone, with nothing in those
        // checks able to see the hundreds of blocks of spoil that explain it.
        //
        // The two then cover disjoint cases instead of overlapping, and nothing falls between
        // them: below the line raw counts decide, above it the ratio does. Deliberate spoil
        // mining as camouflage does not buy much — with 10 hidden diamonds a player needs
        // ~67 stone before the ratio drops under its threshold, i.e. they have to genuinely
        // dig, which is the behaviour being asked for.
        boolean searching = stoneMined > config.xrayMinStoneForRatio();

        // VETOES. Shape evidence that the counting checks cannot see (see MiningProfile).
        // They only ever suppress an alert, so they cannot create a false positive.
        //
        // Applied to the two RAW-COUNT checks only (1 and 3). Check 2 is the ratio, which
        // exists precisely to judge players who are visibly digging — vetoing it with "this
        // player is visibly digging" would switch it off entirely.
        //
        // Evaluated lazily, only once check 1 or 3 would actually flag: the profile is O(n)
        // over up to several minutes of stone breaks and must not run on every ore break.
        // A veto can only remove an alert, so deferring it changes no outcome.
        Boolean[] vetoCache = new Boolean[1];
        java.util.function.BooleanSupplier vetoed = () -> {
            if (vetoCache[0] == null) vetoCache[0] = stripMiningVeto(player, playerId, mines, now);
            return vetoCache[0];
        };

        // Calculate ore breakdown and check per-ore thresholds.
        // The full breakdown is what gets reported; the thresholds are judged against ore
        // that was HIDDEN when it was broken, because that is the only ore whose location
        // X-Ray could have supplied. Ore taken off an open cave wall was simply seen.
        Map<String, Integer> oreBreakdown = calculateOreBreakdown(mines);
        Map<String, Integer> hiddenBreakdown = config.xrayRequireHidden()
                ? calculateOreBreakdown(mines, true) : oreBreakdown;
        Map<String, Integer> exceededOres = new HashMap<>();

        // Check per-ore thresholds.
        //
        // Crossing the count alone is not evidence, because the count says nothing about how
        // the ore was found. One thick vein produces the same number as a dozen scattered
        // ones: copper and redstone veins run past 20 blocks, a lucky pair of overlapping
        // diamond veins reaches ten, and any vein-miner style tool takes a whole vein in a
        // single action. None of that is knowledge about where the ore was — which is the
        // thing X-Ray actually gives someone. Walking to several SEPARATE deposits without
        // searching is. So the ore must also come from at least xray_min_veins distinct veins.
        //
        // A vein gate alone is not enough while the count it guards is block-based: three
        // veins of five blocks are fifteen, which clears a threshold of ten while being three
        // finds. The hidden-ore test does not help either — every block of a vein after the
        // first is exposed by breaking its neighbour, so an entire deposit counts as hidden.
        //
        // So the count itself is per-vein: each deposit contributes at most
        // xray_max_count_per_vein. Emptying one vein is one piece of knowledge however many
        // blocks come out of it, while ten scattered single ores still count ten.
        int minVeins = config.xrayMinVeins();
        int perVeinCap = Math.max(1, config.xrayMaxCountPerVein());
        boolean hiddenOnly = config.xrayRequireHidden();
        if (!searching) {
            for (Map.Entry<String, Integer> entry : hiddenBreakdown.entrySet()) {
                String oreName = entry.getKey();
                int count = entry.getValue();
                int threshold = config.xrayThreshold(oreName);
                // Cheap pre-filter: the capped count can never exceed the raw one, so an ore
                // under its threshold cannot pass and needn't pay for the clustering.
                if (count < threshold) continue;

                // Clustered over the SAME ore the count came from — hidden-only when that is
                // what is being judged, otherwise the vein figure describes a different set
                // of blocks than the number it is gating.
                List<Integer> veins = veinSizes(mines, oreName, hiddenOnly);
                if (veins.size() < minVeins) continue;

                int cappedCount = 0;
                for (int size : veins) cappedCount += Math.min(size, perVeinCap);
                if (config.debugMode()) {
                    plugin.getLogger().info("[XRay] " + player.getName() + " " + oreName
                            + ": hidden=" + count + " veins=" + veins.size()
                            + " capped=" + cappedCount + "/" + threshold);
                }
                if (cappedCount >= threshold) {
                    exceededOres.put(oreName, count);
                }
            }
            if (!exceededOres.isEmpty() && vetoed.getAsBoolean()) exceededOres.clear();
        }

        // If any ore exceeded its threshold, trigger alert
        if (!exceededOres.isEmpty()) {
            // Get location info from recent mines
            String locationInfo = getLocationSummary(mines);

            // Name the exceeded ores in the message — without them a logged alert can't
            // be judged (or the thresholds tuned) afterwards.
            StringBuilder oreInfo = new StringBuilder();
            for (Map.Entry<String, Integer> e : exceededOres.entrySet()) {
                if (oreInfo.length() > 0) oreInfo.append(", ");
                // "x10/38" = ten of the thirty-eight were hidden when broken. The second
                // number is what the player actually mined; the first is what counted.
                oreInfo.append(e.getKey()).append(" x").append(e.getValue())
                        .append("/").append(oreBreakdown.getOrDefault(e.getKey(), e.getValue()))
                        .append(" (max ").append(config.xrayThreshold(e.getKey())).append(")");
            }
            String message = lang.format("alert.xray_threshold", player.getName(), timewindow, locationInfo)
                    + " [" + oreInfo + "]";

            // Log alerts to console only in debug mode
            if (config.debugMode()) {
                plugin.getLogger().warning("[XRay ALERT] " + player.getName() + ": " + exceededOres);
            }

            logViolation(player, "XRAY_THRESHOLD", message, mines.size(), exceededOres, getRecentLocations(mines));

            if (database != null) {
                database.logAsync(player.getUniqueId(), "anticheat_xray", mines.size(), message);
            }

            // Reset evidence after flagging — otherwise every further ore break in the
            // window re-triggers a violation for the same mining events and a single
            // (possibly legitimate) threshold crossing walks through all punishment tiers.
            resetEvidence(playerId);
            return;
        } else if (config.debugMode()) {
            plugin.getLogger().info("[XRay] " + player.getName() + " - Kein Schwellenwert ueberschritten. Breakdown: " + oreBreakdown);
        }

        // Check 2: Ore-to-stone ratio — the check that judges a searching player, by how
        // often the digging pays off rather than by how much came out of it.
        if (searching) {
            // Only count rarer ores — common veins (coal/iron/...) would inflate the ratio.
            // Ore that was visible from open space is excluded for the same reason as in the
            // threshold check: a player clearing a cave took it off a wall they could see,
            // and a high ore-to-stone ratio is exactly what that looks like.
            //
            // Counted per deposit, capped like checks 1 and 3: the ratio asks how often the
            // digging pays off, and one vein is one payoff however many blocks it holds;
            // counted per block, one large vein alone could push the ratio over the limit.
            List<OreMineEvent> relevant = mines.stream()
                    .filter(m -> !COMMON_ORES.contains(m.oreType))
                    .filter(m -> !config.xrayRequireHidden() || !m.visible)
                    .toList();
            long relevantOreCount = cappedVeinCount(relevant, perVeinCap);
            double ratio = (double) relevantOreCount / stoneMined;

            // Get threshold from config (default: 0.10 = 10%)
            double ratioThreshold = config.xrayStoneOreRatio();

            if (ratio > ratioThreshold) {
                String locationInfo = getLocationSummary(mines);
                String message = lang.format("alert.xray_ratio",
                    player.getName(), ratio * 100, (int) relevantOreCount, stoneMined, ratioThreshold * 100, locationInfo);

                logViolation(player, "XRAY_RATIO", message, (int) relevantOreCount, oreBreakdown, getRecentLocations(mines));

                if (database != null) {
                    database.logAsync(player.getUniqueId(), "anticheat_xray_ratio", relevantOreCount, message);
                }

                resetEvidence(playerId);
                return;
            }
        }

        // Check 3: Combined rare-ore count. Catches a player mixing several rare ores
        // where no single type crosses its individual threshold. Only runs when Check 1
        // did NOT already flag an individual rare ore (avoids duplicate alerts), and — like
        // Check 1 — only for a player who is not visibly searching: it is a raw count too,
        // and the ratio above already measures exactly these ores against the spoil.
        boolean rareAlreadyFlagged = exceededOres.containsKey("DIAMOND_ORE")
                || exceededOres.containsKey("EMERALD_ORE")
                || exceededOres.containsKey("ANCIENT_DEBRIS");

        if (!rareAlreadyFlagged && !searching) {
            List<OreMineEvent> rareMines = mines.stream()
                    .filter(m -> RARE_ORES.contains(m.oreType))
                    .filter(m -> !config.xrayRequireHidden() || !m.visible)
                    .toList();
            // Capped per deposit for the same reason as Check 1 — this is a raw count too, so
            // two fat veins would otherwise carry it on their own. Clustered across the rare
            // ores together, which is what the check is about: several deposits, mixed types.
            int rareCapped = 0;
            int cap = Math.max(1, config.xrayMaxCountPerVein());
            for (int size : veinSizes(rareMines.stream().map(m -> m.location).toList())) {
                rareCapped += Math.min(size, cap);
            }
            if (rareCapped >= config.xrayRareCombinedThreshold() && !vetoed.getAsBoolean()) {
                Map<String, Integer> rareBreakdown = new HashMap<>();
                rareMines.forEach(m -> rareBreakdown.merge(m.oreType.name().replace("DEEPSLATE_", ""), 1, Integer::sum));

                String locationInfo = getLocationSummary(rareMines);
                String message = lang.format("alert.xray_rare",
                    player.getName(), rareMines.size(), timewindow, locationInfo);

                logViolation(player, "XRAY_RARE_ORES", message, rareMines.size(),
                    rareBreakdown, getRecentLocations(rareMines));

                if (database != null) {
                    database.logAsync(player.getUniqueId(), "anticheat_xray_rare_ores", rareMines.size(), message);
                }

                resetEvidence(playerId);
            }
        }
    }

    /**
     * Clear a player's collected mining evidence after a violation was flagged, so
     * detection restarts fresh instead of re-flagging the same events on every
     * subsequent block break within the time window.
     */
    private void resetEvidence(UUID playerId) {
        playerOreMines.remove(playerId);
        playerStoneMines.remove(playerId);
    }

    /**
     * The strip-mining veto (see MiningProfile) for the ore currently being judged. Reads the
     * stone profile inside its retention window.
     */
    private boolean stripMiningVeto(Player player, UUID playerId, List<OreMineEvent> mines, long now) {
        ConcurrentLinkedDeque<MiningProfile.StoneBreak> stoneBreaks = playerStoneMines.get(playerId);
        if (stoneBreaks == null) return false;
        long retention = now - (retentionSeconds() * 1000L);
        List<MiningProfile.StoneBreak> profile = new ArrayList<>();
        for (MiningProfile.StoneBreak s : stoneBreaks) {
            if (s.time() >= retention) profile.add(s);
        }
        List<Integer> judgedOreYs = new ArrayList<>();
        for (OreMineEvent mine : mines) {
            if (config.xrayRequireHidden() && mine.visible) continue;
            judgedOreYs.add(mine.location.getBlockY());
        }
        boolean stripMining = MiningProfile.looksLikeStripMining(profile, judgedOreYs,
                config.xrayProfileMinSample(), config.xrayProfileMaxYStdDev(),
                config.xrayProfileMinCorridor(), config.xrayProfileOreBand());
        if (stripMining && config.debugMode()) {
            plugin.getLogger().info("[XRay] " + player.getName()
                    + " Veto: stripMining"
                    + " (profil=" + profile.size() + " Bloecke, yStdAbw="
                    + String.format(java.util.Locale.ROOT, "%.2f", MiningProfile.yStdDev(profile))
                    + ", korridor=" + String.format(java.util.Locale.ROOT, "%.2f",
                            MiningProfile.corridorFraction(profile)) + ")");
        }
        return stripMining;
    }

    /**
     * Ore count with each deposit contributing at most {@code perVeinCap}, clustered per ore
     * kind (deepslate variants merged). Package-private for tests.
     */
    static int cappedVeinCount(List<OreMineEvent> mines, int perVeinCap) {
        Map<String, List<Location>> byKind = new HashMap<>();
        for (OreMineEvent m : mines) {
            byKind.computeIfAbsent(m.oreType.name().replace("DEEPSLATE_", ""), k -> new ArrayList<>())
                    .add(m.location);
        }
        int total = 0;
        for (List<Location> pts : byKind.values()) {
            for (int size : veinSizes(pts)) total += Math.min(size, perVeinCap);
        }
        return total;
    }

    /**
     * Get a summary of mining locations (world + area).
     */
    private String getLocationSummary(List<OreMineEvent> mines) {
        if (mines == null || mines.isEmpty()) return lang.get("alert.location_unknown");

        // Get most recent location (with bounds check)
        OreMineEvent recent = mines.get(mines.size() - 1);
        if (recent == null || recent.location == null) return lang.get("alert.location_unknown");

        String worldName = recent.location.getWorld() != null ? recent.location.getWorld().getName() : "unknown";

        // Calculate bounding box
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        for (OreMineEvent mine : mines) {
            minX = Math.min(minX, mine.location.getBlockX());
            maxX = Math.max(maxX, mine.location.getBlockX());
            minY = Math.min(minY, mine.location.getBlockY());
            maxY = Math.max(maxY, mine.location.getBlockY());
            minZ = Math.min(minZ, mine.location.getBlockZ());
            maxZ = Math.max(maxZ, mine.location.getBlockZ());
        }

        return lang.format("alert.location_summary",
            worldName, minX, maxX, minY, maxY, minZ, maxZ);
    }

    /**
     * Get list of recent mine locations for detailed view.
     */
    private List<String> getRecentLocations(List<OreMineEvent> mines) {
        List<String> locations = new ArrayList<>();
        // Get last 5 locations
        int start = Math.max(0, mines.size() - 5);
        for (int i = start; i < mines.size(); i++) {
            OreMineEvent mine = mines.get(i);
            String worldName = mine.location.getWorld() != null ? mine.location.getWorld().getName() : "unknown";
            locations.add(String.format(java.util.Locale.ROOT, "%s @ %s [%d, %d, %d]",
                mine.oreType.name(), worldName,
                mine.location.getBlockX(), mine.location.getBlockY(), mine.location.getBlockZ()));
        }
        return locations;
    }

    /**
     * Whether this ore was already exposed to open space when it was broken — i.e. whether
     * the player could simply see it.
     *
     * <p>This is the distinction the block count cannot make. X-Ray tells someone where ore
     * is that they <em>cannot</em> see, and acting on that means digging to it: the ore comes
     * out of solid rock. Emptying a cave means taking ore off walls that were open all along,
     * which produces the same "lots of ore, little stone" signature — in fact even less
     * stone, because nothing has to be dug at all.
     *
     * <p>Faces broken within the time window do not count as exposure, otherwise the tunnel
     * dug straight to a hidden ore would qualify it as visible. The record is not per-player
     * on purpose: two accounts mining together — one opening the rock, one taking the ore —
     * would otherwise excuse each other.
     */
    private boolean wasVisible(Block block) {
        long cutoff = System.currentTimeMillis() - (config.xrayTimewindowSeconds() * 1000L);
        return wasVisible(block, recentlyBroken, cutoff);
    }

    /**
     * Testable form: everything this decision depends on is passed in, so the rule can be
     * exercised against a built world without a plugin instance behind it.
     */
    static boolean wasVisible(Block block, Map<String, Long> recentlyBroken, long cutoff) {
        int[][] faces = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int[] f : faces) {
            Block side = block.getRelative(f[0], f[1], f[2]);
            // Open means "does not hide the face": any non-occluding block. Besides air and
            // liquids that is everything that sits in open cave space without filling it —
            // glow lichen, sculk veins, dripstone, vines, moss carpet, amethyst buds, snow
            // layers, torches, plants — and glass, which is see-through as well.
            if (side.getType().isOccluding()) continue;
            Long brokenAt = recentlyBroken.get(getLocationKey(side.getLocation()));
            if (brokenAt == null || brokenAt < cutoff) return true; // open, and not by them
        }
        return false;
    }

    /**
     * Block count of each separate deposit the given ore was taken from. Two blocks belong to
     * the same vein when they sit within {@link #VEIN_LINK_DISTANCE} of each other on every
     * axis, joined transitively — so a winding vein counts once however it is shaped, and two
     * veins a couple of blocks apart (visible together from one spot) also count once. The
     * sizes, not just how many there are, because a threshold has to be able to cap what one
     * deposit contributes to it.
     *
     * <p>O(n²) over the ore blocks of one type in a 60s window, and only reached once a
     * threshold has already been crossed.
     *
     * @param hiddenOnly restrict to ore that was still hidden in rock when it was broken, so
     *                   the clustering describes the same blocks as the count being gated
     */
    private List<Integer> veinSizes(List<OreMineEvent> mines, String oreName, boolean hiddenOnly) {
        List<Location> pts = new ArrayList<>();
        for (OreMineEvent mine : mines) {
            if (hiddenOnly && mine.visible) continue;
            if (mine.oreType.name().replace("DEEPSLATE_", "").equals(oreName)) {
                pts.add(mine.location);
            }
        }
        return veinSizes(pts);
    }

    /**
     * Deposit count for a set of ore positions, by transitive linkage. Package-private and
     * dependent on nothing but its argument, so the clustering can be tested against real
     * mining coordinates.
     */
    static int countVeins(List<Location> pts) {
        return veinSizes(pts).size();
    }

    /** The same clustering, reporting how many blocks fell into each deposit. */
    static List<Integer> veinSizes(List<Location> pts) {
        int n = pts.size();
        if (n == 0) return List.of();
        if (n == 1) return List.of(1);

        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (sameVein(pts.get(i), pts.get(j))) {
                    int a = findRoot(parent, i);
                    int b = findRoot(parent, j);
                    if (a != b) parent[a] = b;
                }
            }
        }
        Map<Integer, Integer> sizes = new HashMap<>();
        for (int i = 0; i < n; i++) sizes.merge(findRoot(parent, i), 1, Integer::sum);
        return new ArrayList<>(sizes.values());
    }

    static boolean sameVein(Location a, Location b) {
        if (a == null || b == null) return false;
        if (a.getWorld() != null && b.getWorld() != null && !a.getWorld().equals(b.getWorld())) return false;
        return Math.abs(a.getBlockX() - b.getBlockX()) <= VEIN_LINK_DISTANCE
                && Math.abs(a.getBlockY() - b.getBlockY()) <= VEIN_LINK_DISTANCE
                && Math.abs(a.getBlockZ() - b.getBlockZ()) <= VEIN_LINK_DISTANCE;
    }

    private static int findRoot(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private Map<String, Integer> calculateOreBreakdown(List<OreMineEvent> mines) {
        return calculateOreBreakdown(mines, false);
    }

    /**
     * Ore counts per type. With {@code hiddenOnly}, ore that was visible from open space is
     * left out — see {@link #wasVisible}. Deepslate variants merge into their base ore
     * (DEEPSLATE_DIAMOND_ORE -> DIAMOND_ORE) so both count against the same threshold.
     */
    private Map<String, Integer> calculateOreBreakdown(List<OreMineEvent> mines, boolean hiddenOnly) {
        Map<String, Integer> breakdown = new HashMap<>();
        for (OreMineEvent mine : mines) {
            if (hiddenOnly && mine.visible) continue;
            String oreName = mine.oreType.name().replace("DEEPSLATE_", "");
            breakdown.merge(oreName, 1, Integer::sum);
        }
        return breakdown;
    }

    private boolean isPlayerWhitelisted(Player player) {
        String name = player.getName();
        boolean debug = config.debugMode();

        // Bedrock (Geyser/Floodgate) players use different mining timing/instamine
        if (Exemptions.isBedrockExempt(player, config, geyser)) {
            if (debug) plugin.getLogger().info("[XRay] " + name + " ist Bedrock (Geyser) -> übersprungen");
            return true;
        }
        if (Exemptions.isLegacyExempt(player, config)) {
            if (debug) plugin.getLogger().info("[XRay] " + name + " ist Legacy-Client (ViaVersion) -> übersprungen");
            return true;
        }

        // Check UUID whitelist
        if (config.isWhitelistedPlayer(player.getUniqueId())) {
            if (debug) plugin.getLogger().info("[XRay] " + name + " ist whitelisted (UUID in Liste)");
            return true;
        }

        // Check if OPs should bypass (configurable!)
        if (player.isOp()) {
            if (config.opsBypass()) {
                if (debug) plugin.getLogger().info("[XRay] " + name + " ist OP und ops_bypass=true -> übersprungen");
                return true;
            } else {
                // Once per player per session, not once per block broken: this fired on every
                // single break and put 2843 identical lines into one debug session's log,
                // burying what the mode was turned on for.
                if (debug && opNoticeLogged.add(player.getUniqueId())) {
                    plugin.getLogger().info("[XRay] " + name + " ist OP aber ops_bypass=false -> wird geprüft!");
                }
            }
        }

        // Check explicit bypass permission (defaults to false, so OPs never get it implicitly)
        if (player.hasPermission("bsanticheat.bypass")) {
            if (debug) plugin.getLogger().info("[XRay] " + name + " hat bypass Permission -> übersprungen");
            return true;
        }

        // Check LuckPerms group whitelist
        if (luckPerms != null) {
            List<String> whitelistGroups = config.anticheatWhitelistGroups();
            if (luckPerms.isPlayerInWhitelistedGroup(player, whitelistGroups)) {
                if (debug) plugin.getLogger().info("[XRay] " + name + " ist in LuckPerms whitelist Gruppe -> übersprungen");
                return true;
            }
        }

        return false;
    }

    private void logViolation(Player player, String type, String message, int oreCount, Map<String, Integer> oreBreakdown, List<String> locations) {
        // Only log to console in debug mode
        if (config.debugMode()) {
            plugin.getLogger().warning(message);
        }

        // Use alert manager if available (bundled alerts)
        if (alertManager != null) {
            alertManager.addAlert(player, type, message, oreCount, oreBreakdown, locations);
        } else {
            // Fallback: direct notification
            plugin.getServer().getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("bsanticheat.admin"))
                .forEach(admin -> admin.sendMessage("\u00a7e" + message));
        }

        // Raise violation level / run punishments
        if (violationManager != null) {
            violationManager.flag(player, type);
        }
    }

    /**
     * Called on quit. Mining evidence is kept, only trimmed to its windows: dropping it would
     * let a relog wipe a window that is about to cross a threshold. What remains expires by
     * time through the periodic cleanup. Recorded breaks stay too — they describe the world,
     * not the player.
     */
    public void cleanup(UUID playerId) {
        trimPlayer(playerId, System.currentTimeMillis(), null);
        opNoticeLogged.remove(playerId);
    }

    /** Timestamp of a player's most recent ore mine, 0 if none (snapshot-safe for COW lists). */
    private static long newestOreTimestamp(List<OreMineEvent> mines) {
        OreMineEvent[] arr = mines.toArray(new OreMineEvent[0]);
        return arr.length == 0 ? 0L : arr[arr.length - 1].timestamp;
    }

    /**
     * Simple data class to track ore mine events.
     */
    private static class OreMineEvent {
        final Material oreType;
        final long timestamp;
        final org.bukkit.Location location;
        /** Whether the ore was already visible from open space when it was broken. */
        final boolean visible;

        OreMineEvent(Material oreType, long timestamp, org.bukkit.Location location, boolean visible) {
            this.oreType = oreType;
            this.timestamp = timestamp;
            this.location = location;
            this.visible = visible;
        }
    }
}
