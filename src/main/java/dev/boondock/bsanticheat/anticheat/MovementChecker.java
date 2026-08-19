package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.integration.GeyserHook;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.lang.LanguageManager;
import dev.boondock.bsanticheat.util.CheckMath;
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
 * skipped moves. Ground state is server-authoritative ({@link #supportDepth}) rather than
 * the client-sent flag, and the consecutive-violation counters are per-tick.
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
    private PistonTracker pistons;

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
    // Start of the current elytra/riptide speed sample. Speed is measured across a fixed
    // time window instead of per move event: a move event is not reliably one tick, and
    // treating it as one turns every packet gap into phantom speed (see checkElytraSpeed).
    private final Map<UUID, Location> elytraSampleFrom = new ConcurrentHashMap<>();
    private final Map<UUID, Long> elytraSampleAt = new ConcurrentHashMap<>();
    private static final long ELYTRA_SAMPLE_WINDOW_MS = 250;

    // Knockback immunity tracking
    private final Map<UUID, Long> recentKnockback = new ConcurrentHashMap<>();
    private static final long KNOCKBACK_IMMUNITY_MS = 2000; // 2 seconds

    // Teleport immunity tracking — prevents false positives after legitimate teleports
    private final Map<UUID, Long> recentTeleport = new ConcurrentHashMap<>();
    private static final long TELEPORT_IMMUNITY_MS = 1000; // 1 second grace period after teleport

    // Join/respawn/world-change grace — spawn teleports and chunk loading cause big deltas
    private final Map<UUID, Long> recentJoin = new ConcurrentHashMap<>();
    private static final long JOIN_GRACE_MS = 3000; // 3 second grace after join/respawn/world change

    // Elytra/Riptide landing grace — after gliding stops the player keeps high horizontal
    // momentum for a moment, which the ground speed check would misread as a Speed violation.
    // Stores the instant the grace EXPIRES, because riptide needs a longer one than elytra:
    // a trident launch is a single impulse that then bleeds off against drag, and vanilla
    // clears isRiptiding() after the ~0.5s animation while the player is still travelling at
    // several blocks per tick. Live data caught exactly that tail — three SPEED alerts of
    // 1.52 / 1.03 / 0.72 b/t against a 0.4 walk cap, decaying, at the same coordinates and
    // second as RIPTIDE alerts.
    // Also used for dismounts: leaving a horse or boat at speed hands the player its
    // momentum, with no key input of their own behind it.
    private final Map<UUID, Long> momentumGraceUntil = new ConcurrentHashMap<>();
    private static final long GLIDE_GRACE_MS = 1500;   // elytra: gliding stops, momentum drops fast
    private static final long RIPTIDE_GRACE_MS = 3000; // riptide: impulse decays over ~2-3s
    private static final long DISMOUNT_GRACE_MS = 1500;

    // Pillar-up grace — a player towering upwards places a block directly beneath their own
    // feet and lands on it immediately. Two things follow: the fresh block catches them
    // before gravity shows, so the fall the hover check waits for never happens, and at the
    // apex of each jump the previous solid block has already dropped out of the support scan.
    // The result reads as "airborne and not falling", which is precisely the hover signature.
    // Live data: 31 hover alerts climbing Y 91→302 while CoreProtect recorded 752 clay blocks
    // placed and removed over the same Y range, in the same minutes.
    private final Map<UUID, Long> recentPillar = new ConcurrentHashMap<>();
    private static final long PILLAR_GRACE_MS = 1500;
    // How far below the feet a placed block still counts as the player's own new footing.
    private static final double PILLAR_MAX_DROP = 3.0;
    // …and how far it may sit horizontally — beyond one block sideways it is scaffolding out,
    // not towering up.
    private static final double PILLAR_MAX_REACH = 1.5;

    // Slime-bounce grace — a high bounce rises for far longer than the 2-block scan below
    // the player can see, so the launch moment is remembered instead.
    private final Map<UUID, Long> recentSlime = new ConcurrentHashMap<>();
    private static final long SLIME_GRACE_MS = 3000;

    // Ice-momentum memory: [0]=timestamp(ms), [1]=ice speed multiplier. Sprint-jumping on
    // ice roads has no ice directly below mid-jump while the momentum persists.
    private final Map<UUID, double[]> recentIce = new ConcurrentHashMap<>();
    private static final long ICE_MOMENTUM_GRACE_MS = 2500;

    // What counts as "hanging in the air". One tick of vanilla gravity is 0.08 blocks, so a
    // player whose vertical movement stays inside this band is neither falling nor climbing —
    // which is what hovering means. The check used to treat everything that was not FALLING as
    // hovering, so every ascent counted: live data showed four hover alerts fired while the
    // player was moving UP at 0.12-0.20 b/t, which no amount of grace tuning would have fixed
    // because a rise is not the thing the check is looking for.
    private static final double HOVER_STILL_BAND = 0.08;

    // Sustained-ascent state (opt-in check): last vertical speed and how many samples in a row
    // rose without the decay gravity imposes.
    private final Map<UUID, Double> lastAscentDy = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> consecutiveAscent = new ConcurrentHashMap<>();

    // Ground-scan depths: nothing supportive within 2 blocks = clearly airborne (hover),
    // within 3 = high above ground (GroundSpoof). One scan to the deeper limit serves both.
    private static final int AIRBORNE_SCAN_DEPTH = 2;
    private static final int GROUNDSPOOF_SCAN_DEPTH = 3;

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

    public void setPistonTracker(PistonTracker pistons) {
        this.pistons = pistons;
    }

    /** True when a piston recently moved next to this spot — it displaced the player. */
    private boolean pistonShoved(Location loc) {
        return pistons != null && pistons.wasPushedRecently(loc);
    }

    /** Round-trip latency (ms) for lag compensation (see {@link dev.boondock.bsanticheat.util.CheckMath}). */
    private int effectivePing(Player player) {
        return CheckMath.effectivePing(transactionManager, player);
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

        // PRIORITY 4: Check climbing. isClimbing() uses the actual game logic (hitbox
        // overlap, trapdoor-above-ladder…); the material check is a fallback for the
        // moment a player exits the top of a ladder while still rising.
        if (player.isClimbing() || isClimbableMaterial(player.getLocation().getBlock().getType())) {
            return MovementType.CLIMBING;
        }

        // PRIORITY 5: Check ground movement states
        if (player.isSprinting()) {
            return MovementType.SPRINTING;
        }
        // Sneaking only slows a player who is actually standing on something. isSneaking()
        // is a pose/input flag that stays set in mid-air, where vanilla applies no sneak
        // slowdown at all — a player who jumps or walks off a ledge while crouched keeps
        // full walking momentum and would be judged against the 0.3x sneak cap.
        if (player.isSneaking() && player.isOnGround()) {
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

        // How fast this player may legitimately move. Read from the real movement_speed
        // attribute instead of hand-rolling a Speed potion multiplier: the attribute already
        // contains the potion (Speed II still yields 1.4x, unchanged) but ALSO every other
        // legitimate source — custom gear and item plugins that add attribute modifiers,
        // datapacks, and EssentialsX /speed, which bypasses attributes entirely.
        double speedMultiplier = CheckMath.speedAttributeRatio(player);

        // Apply soul speed enchantment multiplier on soul sand/soil
        Material below = player.getLocation().getBlock().getRelative(0, -1, 0).getType();
        if (below == Material.SOUL_SAND || below == Material.SOUL_SOIL) {
            speedMultiplier += Constants.SOUL_SPEED_MULTIPLIER;
        }

        // Ice is slippery — players legitimately reach much higher speeds on it. The
        // multiplier must survive sprint-jumping along an ice road: mid-jump the block
        // directly below is air, but the ice momentum persists. So scan a few blocks
        // down AND remember the last ice contact for a short grace period.
        UUID pid = player.getUniqueId();
        long nowMs = System.currentTimeMillis();
        double iceMultiplier = iceMultiplierBelow(player.getLocation());
        if (iceMultiplier > 1.0) {
            recentIce.put(pid, new double[]{nowMs, iceMultiplier});
        } else {
            double[] lastIce = recentIce.get(pid);
            if (lastIce != null && nowMs - (long) lastIce[0] < ICE_MOMENTUM_GRACE_MS) {
                iceMultiplier = lastIce[1];
            }
        }
        speedMultiplier *= iceMultiplier;

        // LAG COMPENSATION: sqrt ping scaling shared with all other checks
        speedMultiplier *= CheckMath.pingSlack(effectivePing(player));

        return switch (type) {
            case WALKING -> baseWalkSpeed * speedMultiplier;
            case SPRINTING -> baseSprintSpeed * speedMultiplier;
            case SNEAKING -> {
                // The sneaking_speed attribute is what Swift Sneak actually modifies, so it
                // covers the enchantment and any item/plugin that grants faster crouching.
                // The enchantment is still read as a floor for servers where the attribute
                // is unavailable.
                double sneakMult = CheckMath.sneakingSpeedFactor(player);
                var leggings = player.getInventory().getLeggings();
                if (leggings != null) {
                    int lvl = leggings.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SWIFT_SNEAK);
                    if (lvl > 0) sneakMult = Math.max(sneakMult, Math.min(1.0,
                            Constants.SNEAKING_SPEED_MULTIPLIER + Constants.SWIFT_SNEAK_MULTIPLIER_PER_LEVEL * lvl));
                }
                yield baseWalkSpeed * sneakMult * speedMultiplier;
            }
            case SWIMMING -> {
                double swim = baseWalkSpeed * Constants.SWIMMING_SPEED_MULTIPLIER * speedMultiplier;
                // Depth Strider removes most of the water drag — without it a player with
                // Depth Strider III swims at roughly walking speed and trips the cap.
                // water_movement_efficiency is the attribute vanilla maps the enchantment
                // onto, so it also covers items/plugins granting it without the enchant;
                // the enchantment lookup stays as a floor.
                double waterMult = CheckMath.waterEfficiencyMultiplier(player);
                var boots = player.getInventory().getBoots();
                if (boots != null) {
                    int ds = boots.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.DEPTH_STRIDER);
                    if (ds > 0) waterMult = Math.max(waterMult,
                            1.0 + Constants.DEPTH_STRIDER_MULTIPLIER_PER_LEVEL * ds);
                }
                swim *= waterMult;
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
            // Not reached from onPlayerMove (gliding/riptiding is routed to
            // checkElytraSpeed), but kept in sync with it so the ceiling has one source.
            case ELYTRA -> config.elytraMaxSpeed();
            case RIPTIDE -> config.riptideMaxSpeed();
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
     * Any server-applied velocity (projectile knockback, wind charges, explosions,
     * fishing-rod pulls, jump pads from other plugins…) reaches the client as a velocity
     * packet and legitimately breaks the movement model for a moment. The damage-event
     * handler above misses every cause that deals no damage, so the velocity itself
     * grants the same immunity.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerVelocity(org.bukkit.event.player.PlayerVelocityEvent event) {
        recentKnockback.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
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

    /**
     * Remember when a player placed a block they could be standing on, so the vertical
     * checks can tell towering up ("pillaring") apart from hovering.
     *
     * <p>Only blocks placed under the player's own feet count — up to
     * {@link #PILLAR_MAX_DROP} below and {@link #PILLAR_MAX_REACH} sideways. That keeps the
     * exemption from becoming a free pass for flight: vanilla only allows placing against an
     * existing block, so a player who keeps producing footing beneath themselves is by
     * definition standing on a column they built, not floating. Bridging out sideways stays
     * outside the window and remains the SCAFFOLD check's business.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        Player player = event.getPlayer();
        org.bukkit.block.Block placed = event.getBlockPlaced();
        Location loc = player.getLocation();
        if (!placed.getWorld().equals(loc.getWorld())) return;

        // Block top surface relative to the feet: at or below them, within reach.
        double drop = loc.getY() - (placed.getY() + 1);
        if (drop < -0.5 || drop > PILLAR_MAX_DROP) return;
        if (Math.abs(placed.getX() + 0.5 - loc.getX()) > PILLAR_MAX_REACH) return;
        if (Math.abs(placed.getZ() + 0.5 - loc.getZ()) > PILLAR_MAX_REACH) return;

        recentPillar.put(player.getUniqueId(), System.currentTimeMillis());
    }

    /**
     * Leaving a vehicle at speed hands its momentum to the player, who then covers ground
     * for a moment without pressing anything — a horse at full gallop or a boat off blue ice
     * carries far more than the walking cap allows.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDismount(org.bukkit.event.entity.EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        momentumGraceUntil.put(player.getUniqueId(), System.currentTimeMillis() + DISMOUNT_GRACE_MS);
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

        // Skip checks for exempt players.
        //
        // Every one of these returns still records the position. The bookkeeping is not
        // optional just because no check ran: the last known position is what a setback
        // teleports to, and leaving it untouched while an exempt player moves means a
        // creative-mode flight (or a bypass period) freezes the baseline where it started.
        // The first violation after they return to survival then teleports them back there,
        // which can be thousands of blocks and minutes ago.
        if (isPlayerWhitelisted(player)) {
            if (config.debugMode()) {
                plugin.getLogger().fine("[AC] " + player.getName() + " is whitelisted, skipping");
            }
            rebaseline(playerId, to);
            return;
        }

        // Check OPs bypass
        if (player.isOp() && config.opsBypass()) {
            rebaseline(playerId, to);
            return;
        }

        // Defaults to false, so OPs never get it implicitly — an explicit grant is meant.
        if (player.hasPermission("bsanticheat.bypass")) {
            rebaseline(playerId, to);
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            rebaseline(playerId, to);
            return;
        }

        // Determine movement type
        MovementType moveType = getMovementType(player);

        // Elytra/Riptide get their own speed-ceiling check — vanilla physics allow far
        // higher speeds than ground movement, so the ground checks below don't apply.
        if (moveType == MovementType.ELYTRA || moveType == MovementType.RIPTIDE) {
            if (config.elytraDetectionEnabled() && !ServerLoad.isLagging(config)) {
                checkElytraSpeed(player, playerId, moveType, from, to);
            }
            // Landing grace, measured from now — riptide's impulse outlives its animation.
            long graceMs = moveType == MovementType.RIPTIDE ? RIPTIDE_GRACE_MS : GLIDE_GRACE_MS;
            momentumGraceUntil.put(playerId, System.currentTimeMillis() + graceMs);
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

        // A move is only judged when there is a usable baseline, the samples aren't
        // microscopically close together, no packet gap sits in between, and no grace window
        // is active. Note this is a condition, not an early return: the position bookkeeping
        // at the end of the method must run either way, otherwise the last known position
        // goes stale and a later setback would teleport the player seconds back in time.
        //
        // The gap condition is the movement-side counterpart of the Timer check's: silence
        // this long is a stalled connection, and what follows is the client flushing its
        // backlog. Judging that catch-up means judging several ticks of travel as if they
        // were one — which is how a routine flight over loading chunks produced a 110 b/s
        // "over-speed" curve before 1.0.4. The per-tick scaling below handles the ordinary
        // case; this rules out the pathological one, where the delta is large enough for the
        // TELEPORT threshold that no scaling protects.
        boolean judge = lastLoc != null && lastTime != null && from.getWorld().equals(to.getWorld())
                && (now - lastTime) / 1000.0 >= Constants.MOVEMENT_MIN_TIME_DELTA
                && (now - lastTime) <= Constants.MOVEMENT_MAX_GAP_MS
                && !hasMovementImmunity(player, playerId, now);

        if (judge) {
            // Calculate movement. The raw distance answers the teleport check — that one asks
            // how far the player jumped, not how fast they were going.
            Vector movement = to.toVector().subtract(from.toVector());
            double totalDist = from.distance(to);

            // Everything else below is a per-TICK rate compared against a per-tick threshold
            // (0.4 blocks walking, 3.5 vertical, …), so the delta has to be divided by the
            // ticks it actually spans. A move event is not reliably one tick: the client may
            // send several position packets within a tick, and a slow connection delivers one
            // event carrying several ticks of travel. Treating the latter as a single tick is
            // the mistake the elytra check was built on until 1.0.4; the ground and vertical
            // checks carried it unchanged.
            //
            // Clamped at one tick, so sub-tick events are never scaled UP: that direction
            // would invent speed rather than remove it, and the whole point is to be
            // conservative about deltas whose duration is uncertain.
            double moveTicks = Math.max(1.0, (now - lastTime) / 50.0);
            if (moveTicks > 1.0) movement.multiply(1.0 / moveTicks);

            double horizontalDist = Math.sqrt(movement.getX() * movement.getX() + movement.getZ() * movement.getZ());
            double verticalDist = Math.abs(movement.getY());

            // Get max allowed speed for this movement type
            double maxSpeed = getMaxSpeed(moveType, player);
            // Jump strength is an attribute too — a plugin or item that grants a higher
            // jump legitimately produces a bigger single-step vertical delta.
            double maxVerticalSpeed = config.flyThreshold() * CheckMath.jumpStrengthRatio(player);

            // Block lookups reused by several checks below — this runs on every movement
            // packet of every player, so each scan is done once and shared.
            boolean nearSlime = isNearSlimeBlock(to);
            boolean nearBubble = isNearBubbleColumn(to);

            // Slime block / bubble column allow faster vertical movement
            if (nearSlime || nearBubble) {
                maxVerticalSpeed *= 2.0; // Double vertical speed allowance
            }

            // Slime launchers throw a player sideways as readily as upwards, and the bounce
            // itself needs no key input. The grace was previously computed inside the
            // vertical block below, so the horizontal checks never saw it.
            if (nearSlime) recentSlime.put(playerId, now);
            Long slimeTs = recentSlime.get(playerId);
            boolean slimeGrace = slimeTs != null && (now - slimeTs) < SLIME_GRACE_MS;

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
                    // Checked here rather than at the top: both are displacement the player
                    // never asked for, and the piston lookup should only be paid for on the
                    // would-flag path.
                    if (!slimeGrace && !pistonShoved(to)) {
                        setBack |= handleViolation(player, "SPEED",
                            lang.format("alert.speed", typeName(moveType), horizontalDist, maxSpeed),
                            horizontalDist, to);
                    }
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

            // Every check below reads blocks, and several of them read the player's
            // NEIGHBOURS: the ground scan samples all four footprint corners, the
            // fall-slowing and liquid tests look sideways, and Spider/Jesus look at the
            // walls. Any of those can cross a chunk border, so the guard has to cover the
            // neighbourhood rather than only the block the player stands in.
            //
            // Two things go wrong without it. An unloaded chunk has no blocks to find, so
            // the scan reports "nothing below the player" for someone standing on perfectly
            // solid ground the server has not got in memory yet. And reading it forces a
            // synchronous chunk load from inside a movement handler — which is exactly what
            // Folia forbids, and it was only ever avoided for the centre column.
            boolean chunkKnown = neighbourhoodLoaded(to);

            // Vertical/fly checks only apply to on-foot movement. Swimming and
            // climbing have their own vertical physics and would false-positive.
            if (groundType && chunkKnown) {
                // (slimeGrace is computed above, where the horizontal checks can see it too —
                // a bounce launches far higher and further than the 2-block scan can follow.)

                // Towering up: the player is building the ground they stand on, one block per
                // jump. Applied to the hover check only — the vertical-burst and GroundSpoof
                // checks stay armed, since pillaring produces neither a 3.5-block jump nor a
                // player who is genuinely high above the ground.
                Long pillarTs = recentPillar.get(playerId);
                boolean pillarGrace = pillarTs != null && (now - pillarTs) < PILLAR_GRACE_MS;

                boolean flyExempt = player.hasPotionEffect(PotionEffectType.LEVITATION)
                        || player.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                        // Jump Boost raises both the height AND the time spent rising, which
                        // feeds the hover counter. The Step check already excluded it.
                        || player.hasPotionEffect(PotionEffectType.JUMP_BOOST)
                        // Reduced gravity means hanging in the air without descending is
                        // legitimate — which is precisely what the hover check looks for.
                        || CheckMath.hasReducedGravity(player)
                        || isInFallSlowingBlock(player)
                        || isNearLiquid(player) || slimeGrace || nearBubble;

                // One ground scan answers both vertical checks: "clearly airborne" (hover)
                // and "high above ground" (GroundSpoof) differ only in the depth they
                // accept. Scanning twice cost 35 block lookups per movement packet.
                // (The chunk neighbourhood is already known to be loaded — see above.)
                boolean wantsFlyScan = config.flyDetectionEnabled() && !flyExempt;
                boolean wantsSpoofScan = config.groundSpoofDetectionEnabled() && !flyExempt
                        && player.isOnGround();
                int support = (wantsFlyScan || wantsSpoofScan)
                        ? supportDepth(player, GROUNDSPOOF_SCAN_DEPTH) : 0;
                // A block scan cannot see that the player is standing on a BOAT, a minecart,
                // a horse or another player's head — all legitimate ground. The entity query
                // is only run when the block scan found nothing, i.e. in the would-flag path,
                // so it costs nothing during normal play.
                if (support < 0 && hasEntitySupport(player)) support = AIRBORNE_SCAN_DEPTH;
                boolean clearlyAirborne = support < 0 || support > AIRBORNE_SCAN_DEPTH;
                boolean highAboveGround = support < 0;

                if (config.flyDetectionEnabled()) {
                    // (a) Vertical burst: too much upward movement in a single step
                    if (!flyExempt && verticalDist > maxVerticalSpeed && movement.getY() > 0) {
                        int consecutive = consecutiveFlyViolations.merge(playerId, 1, Integer::sum);
                        if (consecutive >= config.flyViolationsThreshold()) {
                            // A piston lifts a player a full block per push with no velocity
                            // packet to excuse it — elevators and flying machines do this all
                            // day. Tested only here, on the would-flag path.
                            if (!pistonShoved(to)) {
                                setBack |= handleViolation(player, "FLY",
                                    lang.format("alert.fly", verticalDist, maxVerticalSpeed),
                                    verticalDist, to);
                            }
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
                    if (!flyExempt && !pillarGrace && clearlyAirborne) {
                        if (Math.abs(movement.getY()) > HOVER_STILL_BAND) {
                            // Falling under gravity, or rising — neither is hovering. The rise
                            // matters as much as the fall: a wind charge, a Wind Burst mace or
                            // a Breeze throws a player upwards for longer than any knockback
                            // grace lasts, and the tail of that arc is exactly a slow climb
                            // with nothing underneath. Sustained climbing is judged separately,
                            // by whether it decays the way gravity requires.
                            consecutiveHoverTicks.remove(playerId);
                        } else {
                            int hover = consecutiveHoverTicks.merge(playerId, 1, Integer::sum);
                            if (hover >= config.flyViolationsThreshold()) {
                                if (!pistonShoved(to)) {
                                    setBack |= handleViolation(player, "FLY",
                                        lang.format("alert.hover", hover),
                                        verticalDist, to);
                                }
                                consecutiveHoverTicks.put(playerId, 0);
                            }
                        }
                    } else {
                        consecutiveHoverTicks.remove(playerId);
                    }

                    // (b2) Sustained ascent (opt-in, off by default). Narrowing the hover band
                    // above means a climb is no longer counted as hovering — correct, but it
                    // leaves a slow steady rise unwatched. Gravity is what separates the two:
                    // a player thrown upwards loses ~0.08 b/t of vertical speed every tick and
                    // is back down within a second or two, while a flight cheat holds the climb.
                    if (!flyExempt && !pillarGrace && clearlyAirborne) {
                        setBack |= checkSustainedAscent(player, playerId, movement.getY(), moveTicks, to);
                    } else {
                        lastAscentDy.remove(playerId);
                        consecutiveAscent.remove(playerId);
                    }
                }

                // (c) GroundSpoof: the client claims it is on the ground while it is
                // clearly several blocks up in the air. Fly/NoFall spoof the on-ground
                // flag (which player.isOnGround() reflects) to dodge the hover check.
                if (config.groundSpoofDetectionEnabled() && !flyExempt
                        && player.isOnGround() && highAboveGround) {
                    int gs = consecutiveGroundSpoof.merge(playerId, 1, Integer::sum);
                    if (gs >= config.groundSpoofViolations()) {
                        if (!pistonShoved(to)) {
                            setBack |= handleViolation(player, "GROUNDSPOOF",
                                lang.get("alert.groundspoof"), 0, to);
                        }
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
                // Require meaningful sustained upward motion (> 0.1/tick) so the tail of a
                // normal jump next to a wall doesn't count as climbing.
                if (config.spiderDetectionEnabled() && movement.getY() > 0.1 && !player.isOnGround()
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
                        && movement.getY() > Math.max(config.stepMaxHeight(), CheckMath.stepHeight(player))
                        && horizontalDist > 0.05
                        && !nearSlime && !player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
                    int c = consecutiveStep.merge(playerId, 1, Integer::sum);
                    if (c >= config.stepViolations()) {
                        setBack |= handleViolation(player, "STEP", lang.format("alert.step", movement.getY()), movement.getY(), to);
                        consecutiveStep.put(playerId, 0);
                    }
                } else {
                    consecutiveStep.remove(playerId);
                }
            } else if (groundType) {
                // On-foot, but the surrounding chunks are not all in memory — typically a
                // player walking into fresh terrain. Nothing here can be judged without
                // reading blocks, so the streaks are dropped rather than carried across the
                // blind spot: resuming a half-built hover count on the far side of it would
                // flag on evidence that was never actually gathered.
                consecutiveFlyViolations.remove(playerId);
                consecutiveHoverTicks.remove(playerId);
                consecutiveGroundSpoof.remove(playerId);
                consecutiveJesus.remove(playerId);
                consecutiveSpider.remove(playerId);
                consecutiveStep.remove(playerId);
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
     * Climbing that gravity cannot explain.
     *
     * <p>Ballistic motion has a signature: whatever threw the player upwards, the ascent loses
     * about one tick of gravity (0.08 blocks) of vertical speed per tick and ends. A cheat
     * holding a player in a climb does not decay. Only the missing decay is judged here, never
     * the climb itself — being thrown upwards is ordinary, and a Trial Chamber full of Breezes
     * does it all day.
     *
     * <p>Off by default. It is a heuristic on a check whose false positives have been the
     * expensive part of this plugin's history, and it wants {@code debug_mode} data from the
     * server it will run on before it is trusted.
     *
     * @return true when the player was set back
     */
    private boolean checkSustainedAscent(Player player, UUID playerId, double dy, double moveTicks, Location to) {
        if (!config.sustainedAscentDetectionEnabled()) return false;

        Double previous = lastAscentDy.put(playerId, dy);

        // Not climbing → nothing to judge, and the streak is over.
        if (dy <= HOVER_STILL_BAND) {
            consecutiveAscent.remove(playerId);
            return false;
        }
        // A sample spanning several ticks should have decayed by several ticks' worth of
        // gravity, and the arithmetic for that is guesswork. Skip it rather than guess.
        if (moveTicks > 1.5 || previous == null) return false;

        double decay = previous - dy;
        if (decay >= config.sustainedAscentMinDecay()) {
            consecutiveAscent.remove(playerId); // slowing down like a thrown object should
            return false;
        }

        int c = consecutiveAscent.merge(playerId, 1, Integer::sum);
        if (config.debugMode()) {
            plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                    "[ASCENT-DEBUG] %s dy=%.3f decay=%.3f (%d/%d)",
                    player.getName(), dy, decay, c, config.sustainedAscentViolations()));
        }
        if (c < config.sustainedAscentViolations()) return false;

        consecutiveAscent.remove(playerId);
        if (pistonShoved(to)) return false; // a column of pistons lifts at a constant rate
        return handleViolation(player, "FLY",
                lang.format("alert.sustained_ascent", dy), dy, to);
    }

    /** Record a position as the new baseline without judging the move that led to it. */
    private void rebaseline(UUID playerId, Location to) {
        lastLocations.put(playerId, to.clone());
        lastMoveTime.put(playerId, System.currentTimeMillis());
    }

    /**
     * True while any grace window is active: a legitimate teleport, a join/respawn/world
     * change, the momentum right after gliding/riptiding, or server-applied knockback.
     * Each of these legitimately breaks the movement model for a moment.
     */
    private boolean hasMovementImmunity(Player player, UUID playerId, long now) {
        Long lastTeleportTime = recentTeleport.get(playerId);
        if (lastTeleportTime != null && (now - lastTeleportTime) < TELEPORT_IMMUNITY_MS) {
            if (config.debugMode()) {
                plugin.getLogger().fine("[AC] " + player.getName() + " has teleport immunity, skipping check");
            }
            return true;
        }

        Long lastJoin = recentJoin.get(playerId);
        if (lastJoin != null && (now - lastJoin) < JOIN_GRACE_MS) return true;

        // After gliding/riptiding the residual momentum would read as a Speed/Fly violation.
        Long graceUntil = momentumGraceUntil.get(playerId);
        if (graceUntil != null && now < graceUntil) return true;

        Long lastKnockback = recentKnockback.get(playerId);
        if (lastKnockback != null && (now - lastKnockback) < KNOCKBACK_IMMUNITY_MS) {
            if (config.debugMode()) {
                plugin.getLogger().fine("[AC] " + player.getName() + " has knockback immunity, skipping check");
            }
            return true;
        }
        return false;
    }

    /**
     * Elytra/Riptide speed-ceiling check.
     *
     * <p>Speed is averaged over a real elapsed window ({@link #ELYTRA_SAMPLE_WINDOW_MS})
     * rather than derived from a single move event. A move event is not reliably one tick:
     * the client may send several position packets per tick, and — the damaging direction —
     * a packet gap while flying over loading chunks delivers one event carrying several ticks
     * of travel. Multiplying such an event by 20 invents speed that was never flown, which is
     * how a routine rocket flight produced a tidy 110→100 b/s "over-speed" curve. Dividing by
     * the time that actually passed makes a gap harmless: distance and elapsed time grow
     * together. The window also means the {@code elytra_violations} threshold now spans
     * ~0.75s of sustained over-speed, which noise cannot fake.
     */
    private void checkElytraSpeed(Player player, UUID playerId, MovementType moveType, Location from, Location to) {
        long now = System.currentTimeMillis();
        Long lastTeleportTime = recentTeleport.get(playerId);
        if (lastTeleportTime != null && (now - lastTeleportTime) < TELEPORT_IMMUNITY_MS) return;
        Long lastJoin = recentJoin.get(playerId);
        if (lastJoin != null && (now - lastJoin) < JOIN_GRACE_MS) return;
        if (!from.getWorld().equals(to.getWorld())) return;

        Location sampleFrom = elytraSampleFrom.get(playerId);
        Long sampleAt = elytraSampleAt.get(playerId);
        // Open a new window: no baseline yet, a world change, or the previous one is stale
        // (the player stopped flying in between and momentum no longer connects the points).
        if (sampleFrom == null || sampleAt == null || !sampleFrom.getWorld().equals(to.getWorld())
                || now - sampleAt > ELYTRA_SAMPLE_WINDOW_MS * 4) {
            elytraSampleFrom.put(playerId, to.clone());
            elytraSampleAt.put(playerId, now);
            return;
        }
        long elapsed = now - sampleAt;
        if (elapsed < ELYTRA_SAMPLE_WINDOW_MS) return; // keep accumulating

        double bps = sampleFrom.distance(to) / (elapsed / 1000.0);
        elytraSampleFrom.put(playerId, to.clone());
        elytraSampleAt.put(playerId, now);

        double max = moveType == MovementType.ELYTRA ? config.elytraMaxSpeed() : config.riptideMaxSpeed();
        max *= CheckMath.pingSlack(effectivePing(player));

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
            database.logAsync("anticheat_" + type.toLowerCase(), value,
                    player.getName() + ": " + details + " @ " + CheckMath.formatLocation(location));
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

        // Optional setback: teleport the player back to their last valid position.
        // teleportAsync (not teleport): a synchronous teleport is unsupported on Folia's
        // region threads, and this runs inside a PlayerMoveEvent handler.
        if (config.punishmentsSetback()) {
            Location back = lastLocations.get(player.getUniqueId());
            if (back != null && back.getWorld() != null) {
                player.teleportAsync(back);
                return true;
            }
        }
        return false;
    }

    /**
     * Distance in blocks from the player's feet down to the nearest thing they could
     * legitimately stand on or hang in, or -1 when nothing supportive lies within
     * {@code maxDepth}. Zero means supported at foot level.
     *
     * <p>Checks all four footprint corners, not just the centre — a sneaking player
     * overhangs an edge by up to half the hitbox width while still being supported. Also
     * checks the block at foot level itself: standing on trapdoors, slabs, carpets or snow
     * layers puts the supporting collision INSIDE that block, not below it.
     *
     * <p>Returning the depth rather than a boolean lets both vertical checks (hover =
     * nothing within 2, GroundSpoof = nothing within 3) share a single scan. Note: a
     * client that spoofs its on-ground flag can still evade the hover check — full
     * prevention needs packet-level checks. Main-thread only.
     */
    /**
     * True when every chunk a block-reading check might touch is already in memory — the
     * player's own chunk and those of the eight blocks around them.
     *
     * <p>One block of margin is enough for all of them: the ground scan offsets the footprint
     * corners by 0.3 (more for a scaled player, but still well under a block), and the
     * sideways tests look exactly one block out. Checking only the centre column left the
     * corner of a scan free to reach into an unloaded chunk, which both forces a synchronous
     * load — forbidden on a Folia region thread — and answers "no block here" for ground that
     * simply has not been read yet.
     */
    private static boolean neighbourhoodLoaded(Location loc) {
        org.bukkit.World world = loc.getWorld();
        if (world == null) return false;
        int minChunkX = (loc.getBlockX() - 1) >> 4;
        int maxChunkX = (loc.getBlockX() + 1) >> 4;
        int minChunkZ = (loc.getBlockZ() - 1) >> 4;
        int maxChunkZ = (loc.getBlockZ() + 1) >> 4;
        // Away from a chunk border all four bounds collapse to the same chunk, so this is a
        // single isChunkLoaded call in the overwhelmingly common case.
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!world.isChunkLoaded(cx, cz)) return false;
            }
        }
        return true;
    }

    private int supportDepth(Player player, int maxDepth) {
        if (player.isClimbing()) return 0;
        Location loc = player.getLocation();
        // Hitbox half-width, scaled: the scale attribute resizes the player, and a wider
        // footprint stands on blocks the vanilla-width scan would miss.
        final double half = 0.3 * CheckMath.scale(player);
        final double[] dx = { 0, half, -half, half, -half };
        final double[] dz = { 0, half, half, -half, -half };
        // The feet block covers fractional-Y standing (slabs, trapdoors, carpets); at
        // integer Y the feet sit exactly on the boundary and support is the block below.
        final int feetY = (int) Math.floor(loc.getY());
        for (int k = 0; k <= maxDepth; k++) {
            for (int i = 0; i < dx.length; i++) {
                int bx = (int) Math.floor(loc.getX() + dx[i]);
                int bz = (int) Math.floor(loc.getZ() + dz[i]);
                if (isSupportive(loc.getWorld().getBlockAt(bx, feetY - k, bz))) return k;
            }
        }
        return -1;
    }

    /**
     * A block counts as support when it has real collision ({@code !isPassable()} covers
     * full blocks, slabs, stairs, trapdoors, carpets, fences, snow layers…) or is one of
     * the collision-free blocks players legitimately stand on or hang in: powder snow
     * (walkable with leather boots), scaffolding tops, cobwebs, climbables and liquids.
     */
    private boolean isSupportive(org.bukkit.block.Block block) {
        Material m = block.getType();
        // Air first: it is by far the most common result of a ground scan, and answering it
        // without the isPassable() call saves a block-data lookup on every sample.
        if (m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR) return false;
        if (m == Material.POWDER_SNOW || m == Material.SCAFFOLDING || m == Material.COBWEB) return true;
        if (m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN) return true;
        if (isClimbableMaterial(m)) return true;
        // Full blocks are settled by the material alone. isPassable() is still needed for
        // everything whose collision depends on its state — trapdoors, gates, doors — but
        // those are a small minority of what a ground scan walks over.
        if (m.isSolid()) return true;
        return !block.isPassable();
    }

    /**
     * Climbable block materials via the game's own tag — a hand-rolled list misses the
     * *_PLANT growth variants (CAVE_VINES_PLANT etc.) and any future climbable.
     */
    private static boolean isClimbableMaterial(Material m) {
        return m.isBlock() && org.bukkit.Tag.CLIMBABLE.isTagged(m);
    }

    /**
      * True when standing on the surface of water with nothing holding the player up.
      * The feet block must be AIR specifically, not merely "not water": a lily pad,
      * a waterlogged slab or any other thin block at foot level is legitimate footing
      * and would otherwise read as walking on water. Entities (a boat floating on the
      * surface) are checked separately for the same reason.
      */
    private boolean isOnWaterSurface(Player player) {
        Location loc = player.getLocation();
        Material at = loc.getBlock().getType();
        Material below = loc.getBlock().getRelative(0, -1, 0).getType();
        return at == Material.AIR && below == Material.WATER && !hasEntitySupport(player);
    }

    /**
     * True when an entity the player could be standing on sits just below their feet.
     * Boats, minecarts, rideable animals and other players are all solid footing that no
     * block lookup can see. Only called from the would-flag paths — it is a nearby-entity
     * query and too expensive for every movement packet.
     */
    private boolean hasEntitySupport(Player player) {
        double feetY = player.getLocation().getY();
        for (Entity e : player.getNearbyEntities(0.8, 2.0, 0.8)) {
            if (e instanceof org.bukkit.entity.Item || e instanceof org.bukkit.entity.Projectile) continue;
            // Its top surface must be at or just below the player's feet.
            double top = e.getBoundingBox().getMaxY();
            if (top <= feetY + 0.2 && top >= feetY - 2.0) return true;
        }
        return false;
    }

    /** True when the player occupies a climbable block (ladder/vine/scaffolding). */
    private boolean isOnClimbable(Player player) {
        return player.isClimbing() || isClimbableMaterial(player.getLocation().getBlock().getType());
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

    /**
     * True when the player is inside a block that slows their descent below the rate the
     * hover check accepts as falling ({@code movement.getY() < -0.08}).
     *
     * <p>Cobwebs and powder snow do physically what Slow Falling does, and Slow Falling is
     * already exempt. {@link #isSupportive} does count both as footing, but
     * {@link #supportDepth} only ever scans DOWNWARD from the feet — a web holding the player
     * at body height above a cave therefore reads as "airborne", while the player sinks too
     * slowly to ever reset the counter. Both halves of the hover signature at once, from
     * standing in a mineshaft.
     */
    static boolean isInFallSlowingBlock(Player player) {
        return isInFallSlowingBlock(player.getLocation());
    }

    /** Location-only form, so the block reasoning can be tested without a player. */
    static boolean isInFallSlowingBlock(Location loc) {
        Material at = loc.getBlock().getType();
        Material above = loc.getBlock().getRelative(0, 1, 0).getType();
        if (at == Material.COBWEB || above == Material.COBWEB
                || at == Material.POWDER_SNOW || above == Material.POWDER_SNOW) {
            return true;
        }
        // Sliding down a honey-block wall is the same situation seen sideways: the block
        // doing the slowing is BESIDE the player, where a downward scan never looks, and the
        // slide is far slower than the rate counted as falling.
        int[][] sides = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] s : sides) {
            if (loc.getBlock().getRelative(s[0], s[1], s[2]).getType() == Material.HONEY_BLOCK) return true;
            if (loc.getBlock().getRelative(s[0], s[1] + 1, s[2]).getType() == Material.HONEY_BLOCK) return true;
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

    /** Ice speed multiplier within 3 blocks below (shared scan, on-foot constants). */
    private double iceMultiplierBelow(Location loc) {
        return CheckMath.iceMultiplierBelow(
                loc, Constants.ICE_SPEED_MULTIPLIER, Constants.BLUE_ICE_SPEED_MULTIPLIER);
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
        elytraSampleFrom.remove(playerId);
        elytraSampleAt.remove(playerId);
        lastAscentDy.remove(playerId);
        consecutiveAscent.remove(playerId);
        recentKnockback.remove(playerId);
        recentTeleport.remove(playerId);
        recentJoin.remove(playerId);
        momentumGraceUntil.remove(playerId);
        recentSlime.remove(playerId);
        recentPillar.remove(playerId);
        recentIce.remove(playerId);
    }

    /**
     * Check if player is whitelisted (UUID or LuckPerms group).
     */
    public boolean isPlayerWhitelisted(Player player) {
        // Bedrock (Geyser/Floodgate) and legacy (ViaVersion) clients use different physics
        if (Exemptions.isBedrockExempt(player, config, geyser)) return true;
        if (Exemptions.isLegacyExempt(player, config)) return true;

        // Check UUID whitelist
        if (config.isWhitelistedPlayer(player.getUniqueId())) {
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
        WALKING,
        SPRINTING,
        SNEAKING,
        SWIMMING,
        CLIMBING,
        RIDING_HORSE,
        RIDING_DONKEY,
        RIDING_LLAMA,
        RIDING_CAMEL,
        RIDING_PIG,
        RIDING_STRIDER,
        BOAT,
        MINECART,
        ELYTRA,
        RIPTIDE,
        CREATIVE_FLY,
        OTHER_VEHICLE
    }
}
