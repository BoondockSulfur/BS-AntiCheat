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
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects suspicious movement patterns that may indicate cheating.
 * Checks for various movement types: walking, sprinting, swimming, riding, etc.
 * WARNING: This is basic detection and may produce false positives.
 *
 * PHASE 1: every PlayerMoveEvent is checked (no sampling), so a cheat cannot hide in
 * skipped moves. Ground state is server-authoritative (isOnGroundServer) rather than the
 * client-sent flag, and the consecutive-violation counters are per-tick.
 */
public class MovementChecker implements Listener {

    private final Plugin plugin;
    private final PluginConfig config;
    private final DatabaseManager database;
    private final LanguageManager lang;
    private LuckPermsHook luckPerms;
    private GeyserHook geyser;
    private MovementAlertManager alertManager;
    private ViolationManager violationManager;
    private TransactionManager transactionManager;

    private final Map<UUID, Location> lastLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMoveTime = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> consecutiveSpeedViolations = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> consecutiveFlyViolations = new ConcurrentHashMap<>();
    // Sustained-hover detection: consecutive airborne samples without falling
    private final Map<UUID, Integer> consecutiveHoverTicks = new ConcurrentHashMap<>();
    // GroundSpoof: consecutive samples claiming on-ground while high in the air
    private final Map<UUID, Integer> consecutiveGroundSpoof = new ConcurrentHashMap<>();
    // NoSlow: consecutive samples moving too fast while using an item
    private final Map<UUID, Integer> consecutiveNoSlow = new ConcurrentHashMap<>();
    // Jesus / Spider / Step
    private final Map<UUID, Integer> consecutiveJesus = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> consecutiveSpider = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> consecutiveStep = new ConcurrentHashMap<>();
    // Elytra/Riptide: consecutive over-speed samples
    private final Map<UUID, Integer> consecutiveElytra = new ConcurrentHashMap<>();

    // Knockback immunity tracking
    private final Map<UUID, Long> recentKnockback = new ConcurrentHashMap<>();
    private static final long KNOCKBACK_IMMUNITY_MS = 2000; // 2 seconds

    // Teleport immunity tracking — prevents false positives after legitimate teleports
    private final Map<UUID, Long> recentTeleport = new ConcurrentHashMap<>();
    private static final long TELEPORT_IMMUNITY_MS = 1000; // 1 second grace period after teleport

    // Join/respawn/world-change grace — spawn teleports and chunk loading cause big deltas
    private final Map<UUID, Long> recentJoin = new ConcurrentHashMap<>();
    private static final long JOIN_GRACE_MS = 3000; // 3 second grace after join/respawn/world change

    public MovementChecker(Plugin plugin, PluginConfig config, DatabaseManager database, LanguageManager lang) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.lang = lang;
    }

    /** Localized display name for a movement type (used in speed alerts). */
    private String typeName(MovementType type) {
        return lang.get("movetype." + type.name().toLowerCase());
    }

    public void setLuckPerms(LuckPermsHook luckPerms) {
        this.luckPerms = luckPerms;
    }

    public void setGeyser(GeyserHook geyser) {
        this.geyser = geyser;
    }

    public void setAlertManager(MovementAlertManager alertManager) {
        this.alertManager = alertManager;
    }

    public void setViolationManager(ViolationManager violationManager) {
        this.violationManager = violationManager;
    }

    public void setTransactionManager(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    /**
     * Round-trip latency (ms) for lag compensation: the precise transaction RTT when
     * available, otherwise the coarse {@link Player#getPing()}.
     */
    private int effectivePing(Player player) {
        if (transactionManager != null) {
            double rtt = transactionManager.roundTripMs(player.getUniqueId());
            if (rtt >= 0) return (int) Math.round(rtt);
        }
        return player.getPing();
    }

    /**
     * Determines the current movement type of a player.
     * Priority order matters - checks most specific states first.
     */
    private MovementType getMovementType(Player player) {
        // PRIORITY 1: Check if in vehicle (highest priority)
        Entity vehicle = player.getVehicle();
        if (vehicle != null) {
            // Rideable animals
            if (vehicle instanceof Horse) {
                return MovementType.RIDING_HORSE;
            }
            if (vehicle instanceof Donkey || vehicle instanceof Mule) {
                return MovementType.RIDING_DONKEY;
            }
            if (vehicle instanceof Llama) {
                return MovementType.RIDING_LLAMA;
            }
            if (vehicle instanceof Camel) {
                return MovementType.RIDING_CAMEL;
            }
            if (vehicle instanceof Pig) {
                return MovementType.RIDING_PIG;
            }
            if (vehicle instanceof Strider) {
                return MovementType.RIDING_STRIDER;
            }
            // Vehicles
            if (vehicle instanceof Boat) {
                return MovementType.BOAT;
            }
            if (vehicle instanceof Minecart) {
                return MovementType.MINECART;
            }
            // Fallback for any other vehicle
            return MovementType.OTHER_VEHICLE;
        }

        // PRIORITY 2: Check special flying states
        if (player.isGliding()) {
            return MovementType.ELYTRA;
        }
        if (player.isRiptiding()) {
            return MovementType.RIPTIDE;
        }
        if (player.isFlying()) {
            return MovementType.CREATIVE_FLY;
        }

        // PRIORITY 3: Check water movement
        if (player.isSwimming()) {
            return MovementType.SWIMMING;
        }

        // PRIORITY 4: Check climbing
        Material blockAt = player.getLocation().getBlock().getType();
        if (blockAt == Material.LADDER || blockAt == Material.VINE ||
            blockAt == Material.TWISTING_VINES || blockAt == Material.WEEPING_VINES ||
            blockAt == Material.CAVE_VINES || blockAt == Material.SCAFFOLDING) {
            return MovementType.CLIMBING;
        }

        // PRIORITY 5: Check ground movement states
        if (player.isSprinting()) {
            return MovementType.SPRINTING;
        }
        if (player.isSneaking()) {
            return MovementType.SNEAKING;
        }

        // DEFAULT: Normal walking
        return MovementType.WALKING;
    }

    /**
     * Gets the maximum allowed speed for a movement type.
     * Includes lag compensation based on player ping.
     */
    private double getMaxSpeed(MovementType type, Player player) {
        // Base speeds from config
        double baseWalkSpeed = config.speedThresholdWalk();
        double baseSprintSpeed = config.speedThresholdSprint();
        double baseFlySpeed = config.speedThresholdFly();

        // Apply speed potion multiplier
        double speedMultiplier = 1.0;
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            int amplifier = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1;
            speedMultiplier += Constants.SPEED_POTION_MULTIPLIER_PER_LEVEL * amplifier;
        }

        // Apply soul speed enchantment multiplier on soul sand/soil
        Material below = player.getLocation().getBlock().getRelative(0, -1, 0).getType();
        if (below == Material.SOUL_SAND || below == Material.SOUL_SOIL) {
            speedMultiplier += Constants.SOUL_SPEED_MULTIPLIER;
        }

        // Ice is slippery — players legitimately reach much higher speeds on it
        if (below == Material.BLUE_ICE) {
            speedMultiplier *= Constants.BLUE_ICE_SPEED_MULTIPLIER;
        } else if (below == Material.ICE || below == Material.PACKED_ICE || below == Material.FROSTED_ICE) {
            speedMultiplier *= Constants.ICE_SPEED_MULTIPLIER;
        }

        // LAG COMPENSATION: Non-linear increase based on player ping
        // Uses square root scaling so high-ping players get progressively more tolerance
        // without allowing extreme speeds at very high ping
        int ping = effectivePing(player);
        if (ping > 100) {
            // sqrt scaling: 200ms → +10%, 500ms → +20%, 1000ms → +30%
            double lagMultiplier = 1.0 + (Math.sqrt(ping - 100) / 100.0);
            speedMultiplier *= lagMultiplier;
        }

        return switch (type) {
            case WALKING -> baseWalkSpeed * speedMultiplier;
            case SPRINTING -> baseSprintSpeed * speedMultiplier;
            case SNEAKING -> baseWalkSpeed * Constants.SNEAKING_SPEED_MULTIPLIER * speedMultiplier;
            case SWIMMING -> {
                double swim = baseWalkSpeed * Constants.SWIMMING_SPEED_MULTIPLIER * speedMultiplier;
                // Dolphin's Grace drastically increases swim speed — avoid false positives
                if (player.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE)) {
                    swim *= Constants.DOLPHINS_GRACE_MULTIPLIER;
                }
                yield swim;
            }
            case CLIMBING -> baseWalkSpeed * Constants.CLIMBING_SPEED_MULTIPLIER;
            case RIDING_HORSE -> Constants.HORSE_MAX_SPEED;
            case RIDING_DONKEY -> Constants.DONKEY_MAX_SPEED;
            case RIDING_LLAMA -> Constants.LLAMA_MAX_SPEED;
            case RIDING_CAMEL -> Constants.CAMEL_MAX_SPEED;
            case RIDING_PIG -> Constants.PIG_MAX_SPEED;
            case RIDING_STRIDER -> Constants.STRIDER_MAX_SPEED;
            case BOAT -> Constants.BOAT_MAX_SPEED;
            case MINECART -> Constants.MINECART_MAX_SPEED;
            case ELYTRA -> Constants.ELYTRA_MAX_SPEED;
            case RIPTIDE -> Constants.RIPTIDE_MAX_SPEED;
            case CREATIVE_FLY -> baseFlySpeed * Constants.CREATIVE_FLY_MULTIPLIER;
            case OTHER_VEHICLE -> Constants.OTHER_VEHICLE_MAX_SPEED;
        };
    }

    /**
     * Track knockback/damage for immunity detection.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        UUID playerId = player.getUniqueId();

        // Track explosion knockback
        if (event.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_EXPLOSION ||
            event.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
            recentKnockback.put(playerId, System.currentTimeMillis());
        }

        // Track entity attacks (knockback)
        if (event.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK ||
            event.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            recentKnockback.put(playerId, System.currentTimeMillis());
        }
    }

    /**
     * Track legitimate teleports to prevent false-positive movement violations.
     * PlayerTeleportEvent extends PlayerMoveEvent, so without this handler
     * teleports would be detected as "teleport-like movement" violations.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // Mark this player as recently teleported
        recentTeleport.put(playerId, System.currentTimeMillis());

        // Reset location tracking to the teleport destination so the next
        // movement check uses the correct baseline position
        Location to = event.getTo();
        if (to != null) {
            lastLocations.put(playerId, to.clone());
            lastMoveTime.put(playerId, System.currentTimeMillis());
        }

        // Reset violation counters — teleport is legitimate, not a violation streak
        consecutiveSpeedViolations.remove(playerId);
        consecutiveFlyViolations.remove(playerId);

        if (config.debugMode()) {
            plugin.getLogger().fine("[AC] " + player.getName() + " teleported (" +
                    event.getCause().name() + "), granting immunity");
        }
    }

    @EventHandler
    public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        recentJoin.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onPlayerRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        recentJoin.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent event) {
        recentJoin.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Skip teleport events — handled by onPlayerTeleport
        if (event instanceof PlayerTeleportEvent) {
            return;
        }

        // Skip if movement checks are disabled
        if (!config.movementChecksEnabled()) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // Only check actual position changes (not just head rotation)
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null) return;
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;

        // PHASE 1: no sampling — every position change is checked. PlayerMoveEvent already
        // fires per movement packet the server accepts, so this gives per-tick coverage; a
        // cheat can no longer hide in the moves an every-Nth sampler would have skipped.
        // The per-move work is bounded (arithmetic + a few block lookups), so full coverage
        // is cheap even with many players.

        // Skip checks for exempt players
        if (isPlayerWhitelisted(player)) {
            if (config.debugMode()) {
                plugin.getLogger().fine("[AC] " + player.getName() + " is whitelisted, skipping");
            }
            return;
        }

        // Check OPs bypass
        if (player.isOp() && config.opsBypass()) {
            return;
        }

        if (!player.isOp() && player.hasPermission("bsanticheat.bypass")) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;

        // Determine movement type
        MovementType moveType = getMovementType(player);

        // Elytra/Riptide get their own speed-ceiling check — vanilla physics allow far
        // higher speeds than ground movement, so the ground checks below don't apply.
        if (moveType == MovementType.ELYTRA || moveType == MovementType.RIPTIDE) {
            if (config.elytraDetectionEnabled() && !ServerLoad.isLagging(config)) {
                checkElytraSpeed(player, playerId, moveType, from, to);
            }
            lastLocations.put(playerId, to.clone());
            lastMoveTime.put(playerId, System.currentTimeMillis());
            return;
        }

        // Vehicles are handled by VehicleChecker (VehicleMoveEvent — PlayerMoveEvent
        // does not fire while riding). Creative fly is server-granted (allowFlight),
        // so it is trusted. On-foot states (walking/sprinting/sneaking/swimming/
        // climbing) ARE checked, each with its own speed multiplier in getMaxSpeed().
        if (moveType == MovementType.CREATIVE_FLY || moveType == MovementType.MINECART ||
            moveType == MovementType.BOAT || moveType.name().startsWith("RIDING_") ||
            moveType == MovementType.OTHER_VEHICLE) {
            // Reset tracking for these modes
            lastLocations.put(playerId, to.clone());
            lastMoveTime.put(playerId, System.currentTimeMillis());
            return;
        }

        Location lastLoc = lastLocations.get(playerId);
        long now = System.currentTimeMillis();
        Long lastTime = lastMoveTime.get(playerId);
        boolean setBack = false;

        // Skip during server lag — reset the baseline so the catch-up move afterwards
        // isn't misread as a speed/teleport violation.
        if (ServerLoad.isLagging(config)) {
            lastLocations.put(playerId, to.clone());
            lastMoveTime.put(playerId, now);
            return;
        }

        if (lastLoc != null && lastTime != null && from.getWorld().equals(to.getWorld())) {
            double timeDelta = (now - lastTime) / 1000.0;
            // Skip if time delta is too small to avoid division issues
            // and prevent false positives from rapid tick updates
            if (timeDelta < Constants.MOVEMENT_MIN_TIME_DELTA) return;

            // IMMUNITY CHECK: Skip if player recently teleported
            Long lastTeleportTime = recentTeleport.get(playerId);
            if (lastTeleportTime != null && (now - lastTeleportTime) < TELEPORT_IMMUNITY_MS) {
                if (config.debugMode()) {
                    plugin.getLogger().fine("[AC] " + player.getName() + " has teleport immunity, skipping check");
                }
                return; // Player just teleported legitimately
            }

            // IMMUNITY CHECK: Skip during join/respawn/world-change grace
            Long lastJoin = recentJoin.get(playerId);
            if (lastJoin != null && (now - lastJoin) < JOIN_GRACE_MS) {
                return;
            }

            // IMMUNITY CHECK: Skip if player was recently knocked back or damaged
            Long lastKnockback = recentKnockback.get(playerId);
            if (lastKnockback != null && (now - lastKnockback) < KNOCKBACK_IMMUNITY_MS) {
                if (config.debugMode()) {
                    plugin.getLogger().fine("[AC] " + player.getName() + " has knockback immunity, skipping check");
                }
                return; // Player has knockback immunity
            }

            // Calculate movement
            Vector movement = to.toVector().subtract(from.toVector());
            double horizontalDist = Math.sqrt(movement.getX() * movement.getX() + movement.getZ() * movement.getZ());
            double verticalDist = Math.abs(movement.getY());
            double totalDist = from.distance(to);

            // Get max allowed speed for this movement type
            double maxSpeed = getMaxSpeed(moveType, player);
            double maxVerticalSpeed = config.flyThreshold();

            // Check for slime block/bubble column (allows faster vertical movement)
            if (isNearSlimeBlock(to) || isNearBubbleColumn(to)) {
                maxVerticalSpeed *= 2.0; // Double vertical speed allowance
            }

            // On-foot states (vertical and item-use checks use their own physics)
            boolean groundType = moveType == MovementType.WALKING
                    || moveType == MovementType.SPRINTING
                    || moveType == MovementType.SNEAKING;

            // Check for impossible teleportation-like movement (if enabled)
            if (config.teleportDetectionEnabled() && totalDist > config.teleportThreshold()) {
                setBack |= handleViolation(player, "TELEPORT",
                    lang.format("alert.teleport", totalDist),
                    totalDist, to);
            }

            // Check horizontal speed (if enabled)
            // This ensures we catch both walking and sprinting violations
            if (config.speedDetectionEnabled() && horizontalDist > maxSpeed) {
                int consecutive = consecutiveSpeedViolations.merge(playerId, 1, Integer::sum);

                // Only alert after multiple consecutive violations
                if (consecutive >= config.speedViolationsThreshold()) {
                    setBack |= handleViolation(player, "SPEED",
                        lang.format("alert.speed", typeName(moveType), horizontalDist, maxSpeed),
                        horizontalDist, to);
                    consecutiveSpeedViolations.put(playerId, 0);
                }
            } else if (horizontalDist < maxSpeed * 0.7) {
                // Only reset if speed is significantly below threshold (70%)
                // This prevents a single valid move from washing out violations too quickly
                consecutiveSpeedViolations.compute(playerId, (k, v) -> {
                    if (v == null || v <= 0) return 0;
                    return Math.max(0, v - 1);
                });
            }

            // NoSlow: moving too fast while using an item (eating, drawing a bow,
            // blocking with a shield, charging a trident…) which vanilla slows down.
            // isHandRaised() reflects the (client-influenced) item-use state.
            if (config.noSlowDetectionEnabled() && groundType && player.isHandRaised()) {
                double noSlowCap = getMaxSpeed(MovementType.WALKING, player) * config.noSlowSpeedMultiplier();
                if (horizontalDist > noSlowCap) {
                    int c = consecutiveNoSlow.merge(playerId, 1, Integer::sum);
                    if (c >= config.noSlowViolations()) {
                        setBack |= handleViolation(player, "NOSLOW",
                            lang.format("alert.noslow", horizontalDist, noSlowCap), horizontalDist, to);
                        consecutiveNoSlow.put(playerId, 0);
                    }
                } else {
                    consecutiveNoSlow.remove(playerId);
                }
            } else {
                consecutiveNoSlow.remove(playerId);
            }

            // Vertical/fly checks only apply to on-foot movement. Swimming and
            // climbing have their own vertical physics and would false-positive.
            if (groundType) {
                boolean flyExempt = player.hasPotionEffect(PotionEffectType.LEVITATION)
                        || player.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                        || isNearLiquid(player) || isNearSlimeBlock(to) || isNearBubbleColumn(to);

                if (config.flyDetectionEnabled()) {
                    // (a) Vertical burst: too much upward movement in a single step
                    if (!flyExempt && verticalDist > maxVerticalSpeed && movement.getY() > 0) {
                        int consecutive = consecutiveFlyViolations.merge(playerId, 1, Integer::sum);
                        if (consecutive >= config.flyViolationsThreshold()) {
                            setBack |= handleViolation(player, "FLY",
                                lang.format("alert.fly", verticalDist, maxVerticalSpeed),
                                verticalDist, to);
                            consecutiveFlyViolations.put(playerId, 0);
                        }
                    } else if (verticalDist < maxVerticalSpeed * 0.5) {
                        consecutiveFlyViolations.compute(playerId, (k, v) -> {
                            if (v == null || v <= 0) return 0;
                            return Math.max(0, v - 1);
                        });
                    }

                    // (b) Sustained hover: airborne across many samples without ever
                    // falling. Speed potions / sprinting are irrelevant here — only the
                    // vertical state matters, so legitimate fast running is unaffected.
                    if (!flyExempt && isClearlyAirborne(player)) {
                        if (movement.getY() < -0.08) {
                            // Falling under gravity → legitimate, reset
                            consecutiveHoverTicks.remove(playerId);
                        } else {
                            int hover = consecutiveHoverTicks.merge(playerId, 1, Integer::sum);
                            if (hover >= config.flyViolationsThreshold()) {
                                setBack |= handleViolation(player, "FLY",
                                    lang.format("alert.hover", hover),
                                    verticalDist, to);
                                consecutiveHoverTicks.put(playerId, 0);
                            }
                        }
                    } else {
                        consecutiveHoverTicks.remove(playerId);
                    }
                }

                // (c) GroundSpoof: the client claims it is on the ground while it is
                // clearly several blocks up in the air. Fly/NoFall spoof the on-ground
                // flag (which player.isOnGround() reflects) to dodge the hover check.
                if (config.groundSpoofDetectionEnabled() && !flyExempt
                        && player.isOnGround() && isHighAboveGround(player)) {
                    int gs = consecutiveGroundSpoof.merge(playerId, 1, Integer::sum);
                    if (gs >= config.groundSpoofViolations()) {
                        setBack |= handleViolation(player, "GROUNDSPOOF",
                            lang.get("alert.groundspoof"), 0, to);
                        consecutiveGroundSpoof.put(playerId, 0);
                    }
                } else {
                    consecutiveGroundSpoof.remove(playerId);
                }

                // Jesus: moving across the top of water without sinking
                if (config.jesusDetectionEnabled() && !player.isInWater() && !player.isGliding()
                        && horizontalDist > 0.08 && Math.abs(movement.getY()) < 0.05 && isOnWaterSurface(player)) {
                    int c = consecutiveJesus.merge(playerId, 1, Integer::sum);
                    if (c >= config.jesusViolations()) {
                        setBack |= handleViolation(player, "JESUS", lang.get("alert.jesus"), horizontalDist, to);
                        consecutiveJesus.put(playerId, 0);
                    }
                } else {
                    consecutiveJesus.remove(playerId);
                }

                // Spider: climbing a wall (moving up while pressed against a solid block, not a ladder)
                if (config.spiderDetectionEnabled() && movement.getY() > 0.0 && !player.isOnGround()
                        && !isNearLiquid(player) && !isOnClimbable(player) && isAgainstWall(player)) {
                    int c = consecutiveSpider.merge(playerId, 1, Integer::sum);
                    if (c >= config.spiderViolations()) {
                        setBack |= handleViolation(player, "SPIDER", lang.get("alert.spider"), movement.getY(), to);
                        consecutiveSpider.put(playerId, 0);
                    }
                } else {
                    consecutiveSpider.remove(playerId);
                }

                // Step: stepping up more than the vanilla ~0.6 in one move while staying grounded
                if (config.stepDetectionEnabled() && player.isOnGround()
                        && movement.getY() > config.stepMaxHeight() && horizontalDist > 0.05
                        && !isNearSlimeBlock(to) && !player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
                    int c = consecutiveStep.merge(playerId, 1, Integer::sum);
                    if (c >= config.stepViolations()) {
                        setBack |= handleViolation(player, "STEP", lang.format("alert.step", movement.getY()), movement.getY(), to);
                        consecutiveStep.put(playerId, 0);
                    }
                } else {
                    consecutiveStep.remove(playerId);
                }
            }
        }

        // After a setback the teleport handler already restored the baseline position,
        // so don't overwrite it with the illegal location.
        if (!setBack) {
            lastLocations.put(playerId, to.clone());
        }
        lastMoveTime.put(playerId, now);
    }

    /**
     * Elytra/Riptide speed-ceiling check. One move event covers ~one tick of movement,
     * so per-event distance × 20 approximates blocks per second, compared against the
     * per-mode ceiling (Constants) with the same ping tolerance as getMaxSpeed().
     */
    private void checkElytraSpeed(Player player, UUID playerId, MovementType moveType, Location from, Location to) {
        long now = System.currentTimeMillis();
        Long lastTeleportTime = recentTeleport.get(playerId);
        if (lastTeleportTime != null && (now - lastTeleportTime) < TELEPORT_IMMUNITY_MS) return;
        Long lastJoin = recentJoin.get(playerId);
        if (lastJoin != null && (now - lastJoin) < JOIN_GRACE_MS) return;
        if (!from.getWorld().equals(to.getWorld())) return;

        double bps = from.distance(to) * 20.0;
        double max = moveType == MovementType.ELYTRA ? Constants.ELYTRA_MAX_SPEED : Constants.RIPTIDE_MAX_SPEED;
        int ping = effectivePing(player);
        if (ping > 100) {
            max *= 1.0 + (Math.sqrt(ping - 100) / 100.0);
        }

        if (bps > max) {
            int c = consecutiveElytra.merge(playerId, 1, Integer::sum);
            if (c >= config.elytraViolations()) {
                boolean elytra = moveType == MovementType.ELYTRA;
                handleViolation(player, elytra ? "ELYTRA" : "RIPTIDE",
                        lang.format(elytra ? "alert.elytra" : "alert.riptide", bps, max), bps, to);
                consecutiveElytra.put(playerId, 0);
            }
        } else {
            consecutiveElytra.remove(playerId);
        }
    }

    /**
     * Handle a confirmed movement violation: log, alert, raise VL and optionally set back.
     *
     * @return true if the player was set back (teleported), so the caller skips updating
     *         the last-known position with the illegal location
     */
    private boolean handleViolation(Player player, String type, String details, double value, Location location) {
        // Log to database
        if (database != null) {
            database.logAsync("anticheat_" + type.toLowerCase(), value, player.getName() + ": " + details);
        }

        // Use alert manager if available (bundled alerts)
        if (alertManager != null) {
            alertManager.addAlert(player, type, details, value, location);
        } else {
            // Fallback: direct notification (only in debug mode to console)
            if (config.debugMode()) {
                String message = String.format("[AntiCheat] %s: %s - %s", player.getName(), type, details);
                plugin.getLogger().warning(message);
            }
        }

        // Raise violation level / run punishments
        if (violationManager != null) {
            violationManager.flag(player, type);
        }

        // Optional setback: teleport the player back to their last valid position
        if (config.punishmentsSetback()) {
            Location back = lastLocations.get(player.getUniqueId());
            if (back != null && back.getWorld() != null) {
                player.teleport(back);
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true only when the player is clearly free-floating in the air:
     * not reported on ground, not in/on a climbable, web or liquid, and with at
     * least two blocks of non-solid space below (so slabs, carpets, stairs and
     * fences don't trigger false positives). Note: a client that spoofs its
     * on-ground flag can still evade this — full prevention needs packet-level checks.
     */
    private boolean isClearlyAirborne(Player player) {
        if (isOnGroundServer(player)) return false;
        Location loc = player.getLocation();
        Material at = loc.getBlock().getType();
        if (at == Material.COBWEB || at == Material.LADDER || at == Material.VINE
                || at == Material.SCAFFOLDING || at == Material.TWISTING_VINES
                || at == Material.WEEPING_VINES || at == Material.CAVE_VINES) {
            return false;
        }
        if (loc.getBlock().getRelative(0, -1, 0).getType().isSolid()) return false;
        if (loc.getBlock().getRelative(0, -2, 0).getType().isSolid()) return false;
        return true;
    }

    /**
     * True when the player is clearly several blocks above any solid ground (used together
     * with a client on-ground claim to detect GroundSpoof). Requires 3 blocks of non-solid
     * space below so slabs, carpets, snow layers and stairs never trigger it. Climbables and
     * webs are excluded (legitimate "hanging" states).
     */
    private boolean isHighAboveGround(Player player) {
        Location loc = player.getLocation();
        Material at = loc.getBlock().getType();
        if (at == Material.COBWEB || at == Material.LADDER || at == Material.VINE
                || at == Material.SCAFFOLDING || at == Material.TWISTING_VINES
                || at == Material.WEEPING_VINES || at == Material.CAVE_VINES) {
            return false;
        }
        for (int i = 1; i <= 3; i++) {
            if (loc.getBlock().getRelative(0, -i, 0).getType().isSolid()) return false;
        }
        return true;
    }

    /**
     * Server-authoritative on-ground test: true when a solid block sits just beneath any
     * corner of the player's footprint. Reads the world directly instead of trusting the
     * client-sent on-ground flag, which Fly/NoFall spoof. Main-thread only.
     */
    private boolean isOnGroundServer(Player player) {
        Location loc = player.getLocation();
        final double half = 0.3;      // player hitbox half-width
        final int by = (int) Math.floor(loc.getY() - 0.001); // just below the feet
        final double[] dx = { 0, half, -half, half, -half };
        final double[] dz = { 0, half, half, -half, -half };
        for (int i = 0; i < dx.length; i++) {
            if (loc.getWorld().getBlockAt(
                    (int) Math.floor(loc.getX() + dx[i]), by,
                    (int) Math.floor(loc.getZ() + dz[i])).getType().isSolid()) {
                return true;
            }
        }
        return false;
    }

    /** True when standing on top of a water block without being submerged (Jesus). */
    private boolean isOnWaterSurface(Player player) {
        Location loc = player.getLocation();
        Material at = loc.getBlock().getType();
        Material below = loc.getBlock().getRelative(0, -1, 0).getType();
        return at != Material.WATER && below == Material.WATER;
    }

    /** True when the player occupies a climbable block (ladder/vine/scaffolding). */
    private boolean isOnClimbable(Player player) {
        Material at = player.getLocation().getBlock().getType();
        return at == Material.LADDER || at == Material.VINE || at == Material.SCAFFOLDING
                || at == Material.TWISTING_VINES || at == Material.WEEPING_VINES || at == Material.CAVE_VINES;
    }

    /** True when a solid block is directly next to the player (feet or head level). */
    private boolean isAgainstWall(Player player) {
        Location loc = player.getLocation();
        int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] o : offsets) {
            if (loc.getBlock().getRelative(o[0], o[1], o[2]).getType().isSolid()) return true;
            if (loc.getBlock().getRelative(o[0], o[1] + 1, o[2]).getType().isSolid()) return true;
        }
        return false;
    }

    private boolean isNearLiquid(Player player) {
        Location loc = player.getLocation();
        // Check current block and 6 adjacent faces (7 checks instead of 27)
        Material current = loc.getBlock().getType();
        if (current == Material.WATER || current == Material.LAVA || current == Material.BUBBLE_COLUMN) return true;

        int[][] offsets = {{0,-1,0}, {0,1,0}, {1,0,0}, {-1,0,0}, {0,0,1}, {0,0,-1}};
        for (int[] o : offsets) {
            Material type = loc.getBlock().getRelative(o[0], o[1], o[2]).getType();
            if (type == Material.WATER || type == Material.LAVA || type == Material.BUBBLE_COLUMN) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if player is near a slime block (allows high bounces).
     */
    private boolean isNearSlimeBlock(Location loc) {
        // Check 2 blocks below for slime blocks
        Material below1 = loc.getBlock().getRelative(0, -1, 0).getType();
        Material below2 = loc.getBlock().getRelative(0, -2, 0).getType();
        return below1 == Material.SLIME_BLOCK || below2 == Material.SLIME_BLOCK;
    }

    /**
     * Check if player is in or near a bubble column.
     */
    private boolean isNearBubbleColumn(Location loc) {
        Material at = loc.getBlock().getType();
        Material below = loc.getBlock().getRelative(0, -1, 0).getType();
        Material above = loc.getBlock().getRelative(0, 1, 0).getType();
        return at == Material.BUBBLE_COLUMN || below == Material.BUBBLE_COLUMN || above == Material.BUBBLE_COLUMN;
    }

    /**
     * Cleanup player data on disconnect to prevent memory leaks.
     * Removes all tracking data associated with the player.
     *
     * @param playerId UUID of the player to cleanup
     */
    public void cleanup(UUID playerId) {
        lastLocations.remove(playerId);
        lastMoveTime.remove(playerId);
        consecutiveSpeedViolations.remove(playerId);
        consecutiveFlyViolations.remove(playerId);
        consecutiveHoverTicks.remove(playerId);
        consecutiveGroundSpoof.remove(playerId);
        consecutiveNoSlow.remove(playerId);
        consecutiveJesus.remove(playerId);
        consecutiveSpider.remove(playerId);
        consecutiveStep.remove(playerId);
        consecutiveElytra.remove(playerId);
        recentKnockback.remove(playerId);
        recentTeleport.remove(playerId);
        recentJoin.remove(playerId);
    }

    /**
     * Check if player is whitelisted (UUID or LuckPerms group).
     */
    public boolean isPlayerWhitelisted(Player player) {
        // Bedrock (Geyser/Floodgate) and legacy (ViaVersion) clients use different physics
        if (Exemptions.isBedrockExempt(player, config, geyser)) return true;
        if (Exemptions.isLegacyExempt(player, config)) return true;

        // Check UUID whitelist
        List<String> whitelistPlayers = config.anticheatWhitelistPlayers();
        if (whitelistPlayers.contains(player.getUniqueId().toString())) {
            return true;
        }

        // Check LuckPerms group whitelist
        if (luckPerms != null) {
            List<String> whitelistGroups = config.anticheatWhitelistGroups();
            if (luckPerms.isPlayerInWhitelistedGroup(player, whitelistGroups)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Movement types for detection.
     * Each type has its own speed threshold.
     */
    public enum MovementType {
        WALKING("Laufen"),
        SPRINTING("Sprinten"),
        SNEAKING("Schleichen"),
        SWIMMING("Schwimmen"),
        CLIMBING("Klettern"),
        RIDING_HORSE("Reiten (Pferd)"),
        RIDING_DONKEY("Reiten (Esel/Maultier)"),
        RIDING_LLAMA("Reiten (Lama)"),
        RIDING_CAMEL("Reiten (Kamel)"),
        RIDING_PIG("Reiten (Schwein)"),
        RIDING_STRIDER("Reiten (Schreiter)"),
        BOAT("Boot"),
        MINECART("Lore"),
        ELYTRA("Elytra"),
        RIPTIDE("Dreizack"),
        CREATIVE_FLY("Kreativ-Flug"),
        OTHER_VEHICLE("Anderes Fahrzeug");

        private final String displayName;

        MovementType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}
