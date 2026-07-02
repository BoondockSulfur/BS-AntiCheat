package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
    private XRayAlertManager alertManager;
    private ViolationManager violationManager;

    // Excluded ores from config (cached)
    private Set<Material> excludedOres = new HashSet<>();

    // Normalized ore names for restricted worlds (cached)
    private Set<String> normalizedRestrictedOres = new HashSet<>();

    // Track ore mining per player
    private final Map<UUID, List<OreMineEvent>> playerOreMines = new ConcurrentHashMap<>();
    // Time-windowed stone mining timestamps per player (mirrors ore tracking so the
    // ore/stone ratio compares like-for-like within the same time window)
    private final Map<UUID, ConcurrentLinkedDeque<Long>> playerStoneMines = new ConcurrentHashMap<>();

    // Track player-placed blocks to prevent false positives in restricted worlds
    // Key: Block location hash, Value: Timestamp when placed
    private final Map<String, Long> playerPlacedBlocks = new ConcurrentHashMap<>();

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

    private static final Set<Material> STONE_TYPES = Set.of(
        Material.STONE,
        Material.DEEPSLATE,
        Material.ANDESITE,
        Material.GRANITE,
        Material.DIORITE,
        Material.TUFF,
        Material.CALCITE
    );

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
        // Run cleanup every 5 minutes
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            long cutoff = System.currentTimeMillis() - (config.xrayTimewindowSeconds() * 1000L);
            int totalCleaned = 0;
            int playersWithData = 0;

            // Clean old ore mining events
            for (Map.Entry<UUID, List<OreMineEvent>> entry : playerOreMines.entrySet()) {
                List<OreMineEvent> mines = entry.getValue();
                int before = mines.size();
                mines.removeIf(mine -> mine.timestamp < cutoff);
                int cleaned = before - mines.size();
                totalCleaned += cleaned;

                if (!mines.isEmpty()) {
                    playersWithData++;
                }
            }

            // Clean old stone mining timestamps (time-windowed, same cutoff as ores)
            for (ConcurrentLinkedDeque<Long> stoneTimestamps : playerStoneMines.values()) {
                Long head;
                while ((head = stoneTimestamps.peekFirst()) != null && head < cutoff) {
                    stoneTimestamps.pollFirst();
                }
            }

            // Remove empty entries to free memory
            playerOreMines.entrySet().removeIf(e -> e.getValue().isEmpty());
            playerStoneMines.entrySet().removeIf(e -> e.getValue().isEmpty());

            // Clean up old player-placed blocks
            long placedBlockCutoff = System.currentTimeMillis() - Constants.XRAY_PLACED_BLOCK_EXPIRY_MS;
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
                        Long last = e.getValue().peekLast();
                        return last != null ? last : 0L;
                    }))
                    .limit(toRemove)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(playerStoneMines::remove);
                hitLimit = true;
            }

            if (config.debugMode() && (totalCleaned > 0 || placedBlocksCleaned > 0 || hitLimit)) {
                plugin.getLogger().info(String.format("[XRay Cleanup] Removed %d old mining events, %d placed blocks. Active players: %d, Maps size: ore=%d, stone=%d",
                    totalCleaned, placedBlocksCleaned, playersWithData, playerOreMines.size(), playerStoneMines.size()));
            }
        }, Constants.XRAY_CLEANUP_INTERVAL_TICKS, Constants.XRAY_CLEANUP_INTERVAL_TICKS);
    }

    public void setLuckPerms(LuckPermsHook luckPerms) {
        this.luckPerms = luckPerms;
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
        excludedOres.clear();
        List<String> excluded = config.xrayExcludedOres();
        for (String oreName : excluded) {
            try {
                Material mat = Material.valueOf(oreName.toUpperCase());
                excludedOres.add(mat);
                // Also add deepslate variant if not already specified
                String deepslateName = "DEEPSLATE_" + oreName.toUpperCase();
                try {
                    Material deepslate = Material.valueOf(deepslateName);
                    excludedOres.add(deepslate);
                } catch (IllegalArgumentException ignored) {}
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("[XRay] Unbekanntes Erz in Ausnahmeliste: " + oreName);
            }
        }
        if (!excludedOres.isEmpty() && config.debugMode()) {
            plugin.getLogger().info("[XRay] Ausgenommene Erze: " + excludedOres);
        }
    }

    /**
     * Reload and normalize restricted world ores from config.
     * Normalizes ore names to handle both DIAMOND_ORE and DEEPSLATE_DIAMOND_ORE formats.
     */
    private void reloadRestrictedWorldOres() {
        normalizedRestrictedOres.clear();
        List<String> restricted = config.restrictedWorldOres();

        for (String oreName : restricted) {
            // Normalize: remove DEEPSLATE_ prefix and _ORE suffix
            String normalized = oreName.toUpperCase()
                .replace("DEEPSLATE_", "")
                .replace("_ORE", "");

            normalizedRestrictedOres.add(normalized);
        }

        if (!normalizedRestrictedOres.isEmpty() && config.debugMode()) {
            plugin.getLogger().info("[XRay] Normalized restricted ores: " + normalizedRestrictedOres);
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
    private String getLocationKey(org.bukkit.Location loc) {
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

        // Track stone mining (time-windowed)
        if (STONE_TYPES.contains(type)) {
            long now = System.currentTimeMillis();
            long stoneCutoff = now - (config.xrayTimewindowSeconds() * 1000L);
            ConcurrentLinkedDeque<Long> stoneTimestamps =
                    playerStoneMines.computeIfAbsent(playerId, k -> new ConcurrentLinkedDeque<>());
            stoneTimestamps.addLast(now);
            // Trim timestamps that fell out of the window
            Long head;
            while ((head = stoneTimestamps.peekFirst()) != null && head < stoneCutoff) {
                stoneTimestamps.pollFirst();
            }
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

            // Check if player is in a restricted world (instant alert zone)
            String worldName = player.getWorld().getName();
            boolean isInRestrictedWorld = config.isRestrictedWorld(worldName);
            boolean shouldMonitorOreInRestrictedWorld = false;

            if (isInRestrictedWorld) {
                // If normalizedRestrictedOres is empty, monitor ALL ores in restricted worlds
                if (normalizedRestrictedOres.isEmpty()) {
                    shouldMonitorOreInRestrictedWorld = true;
                } else {
                    // Normalize current ore type and check against cached set
                    String normalizedOre = type.name().toUpperCase()
                        .replace("DEEPSLATE_", "")
                        .replace("_ORE", "");

                    shouldMonitorOreInRestrictedWorld = normalizedRestrictedOres.contains(normalizedOre);
                }
            }

            // INSTANT ALERT for restricted worlds
            if (shouldMonitorOreInRestrictedWorld) {
                String locationInfo = String.format("%s @ [%d, %d, %d]",
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
                    database.logAsync("anticheat_restricted_zone", 1.0, message);
                }
            }

            List<OreMineEvent> mines = playerOreMines.computeIfAbsent(playerId, k -> new CopyOnWriteArrayList<>());

            // Clone location to avoid holding chunk references (memory optimization)
            Location mineLoc = block.getLocation().clone();
            mines.add(new OreMineEvent(type, System.currentTimeMillis(), mineLoc));

            // Cleanup old events (older than timewindow) - CRITICAL for memory management
            // Removes both old timestamps AND their location references
            long cutoff = System.currentTimeMillis() - (config.xrayTimewindowSeconds() * 1000L);
            int beforeCleanup = mines.size();
            mines.removeIf(mine -> mine.timestamp < cutoff);

            // Calculate current count for this ore type
            int currentOreCount = (int) mines.stream().filter(m -> m.oreType == type).count();
            int threshold = config.xrayThreshold(type.name());

            // Always log ore mining (important for debugging)
            if (config.debugMode()) {
                plugin.getLogger().info("[XRay] " + player.getName() + " mined " + type.name() +
                    " | Count: " + currentOreCount + "/" + threshold +
                    " | Total ores in window: " + mines.size() +
                    " | Cleaned: " + (beforeCleanup - mines.size() + 1) +
                    " | Restricted World: " + isInRestrictedWorld);
            }

            // Check for suspicious patterns (normal XRay detection).
            // Pass an immutable snapshot: the async cleanup task shrinks the live
            // COW list concurrently, which would break index-based reads.
            checkSuspiciousPattern(player, playerId, List.copyOf(mines));
        } else if (VALUABLE_ORES.contains(type) && isOreExcluded(type)) {
            if (config.debugMode()) {
                plugin.getLogger().info("[XRay] " + player.getName() + " mined excluded ore: " + type.name());
            }
        }
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

        // Calculate ore breakdown and check per-ore thresholds
        Map<String, Integer> oreBreakdown = calculateOreBreakdown(mines);
        Map<String, Integer> exceededOres = new HashMap<>();

        // Check per-ore thresholds
        for (Map.Entry<String, Integer> entry : oreBreakdown.entrySet()) {
            String oreName = entry.getKey();
            int count = entry.getValue();
            int threshold = config.xrayThreshold(oreName);

            if (count >= threshold) {
                exceededOres.put(oreName, count);
            }
        }

        // If any ore exceeded its threshold, trigger alert
        if (!exceededOres.isEmpty()) {
            // Get location info from recent mines
            String locationInfo = getLocationSummary(mines);

            String message = lang.format("alert.xray_threshold", player.getName(), timewindow, locationInfo);

            // Log alerts to console only in debug mode
            if (config.debugMode()) {
                plugin.getLogger().warning("[XRay ALERT] " + player.getName() + ": " + exceededOres);
            }

            logViolation(player, "XRAY_THRESHOLD", message, mines.size(), exceededOres, getRecentLocations(mines));

            if (database != null) {
                database.logAsync("anticheat_xray", mines.size(), message);
            }

            // Reset evidence after flagging — otherwise every further ore break in the
            // window re-triggers a violation for the same mining events and a single
            // (possibly legitimate) threshold crossing walks through all punishment tiers.
            resetEvidence(playerId);
            return;
        } else if (config.debugMode()) {
            plugin.getLogger().info("[XRay] " + player.getName() + " - Kein Schwellenwert ueberschritten. Breakdown: " + oreBreakdown);
        }

        // Check 2: Ore-to-stone ratio (only check if player mined enough stone)
        ConcurrentLinkedDeque<Long> stoneTimestamps = playerStoneMines.get(playerId);
        int stoneMined = stoneTimestamps != null ? stoneTimestamps.size() : 0;
        if (stoneMined > Constants.XRAY_MIN_STONE_FOR_RATIO_CHECK) {
            // Only count rarer ores — common veins (coal/iron/...) would inflate the ratio
            long relevantOreCount = mines.stream().filter(m -> !COMMON_ORES.contains(m.oreType)).count();
            double ratio = (double) relevantOreCount / stoneMined;

            // Get threshold from config (default: 0.10 = 10%)
            double ratioThreshold = config.xrayStoneOreRatio();

            if (ratio > ratioThreshold) {
                String locationInfo = getLocationSummary(mines);
                String message = lang.format("alert.xray_ratio",
                    player.getName(), ratio * 100, (int) relevantOreCount, stoneMined, ratioThreshold * 100, locationInfo);

                logViolation(player, "XRAY_RATIO", message, (int) relevantOreCount, oreBreakdown, getRecentLocations(mines));

                if (database != null) {
                    database.logAsync("anticheat_xray_ratio", relevantOreCount, message);
                }

                resetEvidence(playerId);
                return;
            }
        }

        // Check 3: Combined rare-ore count. Catches a player mixing several rare ores
        // where no single type crosses its individual threshold. Only runs when Check 1
        // did NOT already flag an individual rare ore (avoids duplicate alerts).
        boolean rareAlreadyFlagged = exceededOres.containsKey("DIAMOND_ORE")
                || exceededOres.containsKey("EMERALD_ORE")
                || exceededOres.containsKey("ANCIENT_DEBRIS");

        if (!rareAlreadyFlagged) {
            List<OreMineEvent> rareMines = mines.stream().filter(m -> RARE_ORES.contains(m.oreType)).toList();
            if (rareMines.size() >= Constants.XRAY_RARE_COMBINED_THRESHOLD) {
                Map<String, Integer> rareBreakdown = new HashMap<>();
                rareMines.forEach(m -> rareBreakdown.merge(m.oreType.name().replace("DEEPSLATE_", ""), 1, Integer::sum));

                String locationInfo = getLocationSummary(rareMines);
                String message = lang.format("alert.xray_rare",
                    player.getName(), rareMines.size(), timewindow, locationInfo);

                logViolation(player, "XRAY_RARE_ORES", message, rareMines.size(),
                    rareBreakdown, getRecentLocations(rareMines));

                if (database != null) {
                    database.logAsync("anticheat_xray_rare_ores", rareMines.size(), message);
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
        List<OreMineEvent> mines = playerOreMines.get(playerId);
        if (mines != null) mines.clear();
        ConcurrentLinkedDeque<Long> stone = playerStoneMines.get(playerId);
        if (stone != null) stone.clear();
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
            locations.add(String.format("%s @ %s [%d, %d, %d]",
                mine.oreType.name(), worldName,
                mine.location.getBlockX(), mine.location.getBlockY(), mine.location.getBlockZ()));
        }
        return locations;
    }

    /**
     * Calculate breakdown of ores mined.
     * Normalizes ore names (e.g., DEEPSLATE_DIAMOND_ORE -> DIAMOND_ORE for consistent counting).
     */
    private Map<String, Integer> calculateOreBreakdown(List<OreMineEvent> mines) {
        Map<String, Integer> breakdown = new HashMap<>();
        for (OreMineEvent mine : mines) {
            // Merge the deepslate variant into its base ore (DEEPSLATE_DIAMOND_ORE ->
            // DIAMOND_ORE) so both variants count against the same threshold.
            String oreName = mine.oreType.name().replace("DEEPSLATE_", "");
            breakdown.merge(oreName, 1, Integer::sum);
        }
        return breakdown;
    }

    private boolean isPlayerWhitelisted(Player player) {
        String name = player.getName();
        boolean debug = config.debugMode();

        // Check UUID whitelist
        List<String> whitelistPlayers = config.anticheatWhitelistPlayers();
        if (whitelistPlayers.contains(player.getUniqueId().toString())) {
            if (debug) plugin.getLogger().info("[XRay] " + name + " ist whitelisted (UUID in Liste)");
            return true;
        }

        // Check if OPs should bypass (configurable!)
        if (player.isOp()) {
            if (config.opsBypass()) {
                if (debug) plugin.getLogger().info("[XRay] " + name + " ist OP und ops_bypass=true -> übersprungen");
                return true;
            } else {
                if (debug) plugin.getLogger().info("[XRay] " + name + " ist OP aber ops_bypass=false -> wird geprüft!");
            }
        }

        // Check explicit bypass permission (only for non-OPs)
        if (!player.isOp() && player.hasPermission("bsanticheat.bypass")) {
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

    private void logViolation(Player player, String type, String message, int oreCount, Map<String, Integer> oreBreakdown) {
        logViolation(player, type, message, oreCount, oreBreakdown, null);
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

    public void cleanup(UUID playerId) {
        playerOreMines.remove(playerId);
        playerStoneMines.remove(playerId);
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

        OreMineEvent(Material oreType, long timestamp, org.bukkit.Location location) {
            this.oreType = oreType;
            this.timestamp = timestamp;
            this.location = location;
        }
    }
}
