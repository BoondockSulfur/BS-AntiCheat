package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.integration.GeyserHook;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.lang.LanguageManager;
import dev.boondock.bsanticheat.util.CheckMath;
import dev.boondock.bsanticheat.util.Constants;
import dev.boondock.bsanticheat.util.Scheduler;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Velocity / AntiKnockback check (Masterplan phase 2.3).
 *
 * <p>When the server applies knockback it fires {@link PlayerVelocityEvent} with the velocity
 * the client is expected to apply. A few ticks later we compare the player's actual horizontal
 * displacement along the knockback direction against that expectation: a client that ignores or
 * reduces knockback (AntiKB/Velocity cheat) barely moves, while a vanilla client is pushed the
 * full amount in the first tick alone.
 *
 * <p>The delayed evaluation runs on the player's region thread (Folia-safe). Exemptions cover
 * the legitimate ways knockback is reduced/blocked (water, vehicle, gliding, riptide, a
 * climbable, or a wall in the knockback direction). Note: items granting near-total knockback
 * resistance are a known calibration caveat — netherite (~0.4) still moves the player enough to
 * pass. Off-by-default-friendly: gated by {@code velocity_detection}.
 */
public class VelocityChecker implements Listener {

    private final Plugin plugin;
    private final PluginConfig config;
    private final DatabaseManager database;
    private final LanguageManager lang;
    private LuckPermsHook luckPerms;
    private GeyserHook geyser;
    private MovementAlertManager alertManager;
    private ViolationManager violationManager;
    private TransactionManager transactionManager;

    private final Map<UUID, Integer> consecutive = new ConcurrentHashMap<>();
    // Last teleport / respawn / world change per player (ms). A relocation inside the
    // evaluation window makes the measured displacement meaningless.
    private final Map<UUID, Long> relocated = new ConcurrentHashMap<>();
    // Knockback events per player: [0] = last one of any cause, [1] = last explosion (ms).
    // A second push inside the evaluation window — a wind charge, an explosion, another
    // hit — replaces the displacement the expectation was built for.
    private final Map<UUID, long[]> knockbacks = new ConcurrentHashMap<>();
    // The hit's own knockback event fires in the same tick as its velocity event.
    private static final long SAME_TICK_MS = 25L;
    // Explosion knockback reaches the client with the explosion, not as a velocity event.
    private static final long EXPLOSION_OVERLAP_MS = 150L;

    public VelocityChecker(Plugin plugin, PluginConfig config, DatabaseManager database, LanguageManager lang) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.lang = lang;
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerVelocity(PlayerVelocityEvent event) {
        if (!config.velocityDetectionEnabled()) return;
        Player player = event.getPlayer();
        if (ServerLoad.isLagging(config, player)) return;
        if (Exemptions.isExempt(player, config, luckPerms, geyser)) return;

        Vector v = event.getVelocity();
        double expectedH = Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ());
        if (expectedH < Constants.VELOCITY_MIN_KB) return;   // not a real knockback

        // Legit reasons a knockback is reduced/blocked
        if (absorbsKnockback(player)) return;

        final Location start = player.getLocation().clone();
        final double dirX = v.getX() / expectedH;
        final double dirZ = v.getZ() / expectedH;

        // A wall directly in the knockback direction legitimately stops the push.
        if (isWall(start, dirX, dirZ)) return;

        // Evaluate a few ticks later, on the player's own region thread (Folia-safe). The
        // window is extended by the transaction latency so a laggy client still has time to
        // report the movement it applied.
        long delay = Constants.VELOCITY_EVAL_DELAY_TICKS;
        if (transactionManager != null) {
            double rttMs = transactionManager.roundTripMs(player.getUniqueId());
            if (rttMs > 0) delay += Math.min(10L, Math.round(rttMs / 50.0));
        }
        UUID id = player.getUniqueId();
        final long startMs = System.currentTimeMillis();
        Scheduler.runForEntityLater(plugin, player,
                () -> evaluate(id, start, startMs, expectedH, dirX, dirZ), delay);
    }

    void evaluate(UUID id, Location start, long startMs, double expectedH, double dirX, double dirZ) {
        Player player = org.bukkit.Bukkit.getPlayer(id);
        if (player == null || player.isDead()) return;
        if (Exemptions.isExempt(player, config, luckPerms, geyser)) return;
        if (!player.getWorld().equals(start.getWorld())) return;
        // A teleport, respawn or world change inside the window replaces the knockback's
        // displacement with an arbitrary one — in either direction.
        if (relocatedSince(id, startMs)) return;
        if (pushedAgain(id, startMs)) return;
        if (ServerLoad.isLagging(config, player)) return;

        // The state that legitimately eats a knockback was only sampled when the velocity was
        // applied — but the verdict is passed several ticks later, and being thrown into water,
        // onto a vine, or into a boat during exactly those ticks is what a knockback does. The
        // same test therefore has to hold at BOTH ends of the window.
        if (absorbsKnockback(player)) return;
        // Likewise for a wall: the probe at the start only reaches 0.6 blocks ahead, and the
        // push travels roughly a block in this window, so the obstacle that stopped the player
        // is frequently one they had not reached yet when it was applied.
        if (isWall(player.getLocation(), dirX, dirZ)) return;

        Location now = player.getLocation();
        double dx = now.getX() - start.getX();
        double dz = now.getZ() - start.getZ();
        // Displacement projected onto the knockback direction (ignores incidental sideways move)
        double along = dx * dirX + dz * dirZ;

        if (along < expectedH * config.velocityMinApplyRatio()) {
            int c = consecutive.merge(id, 1, Integer::sum);
            if (config.debugMode()) {
                plugin.getLogger().info(String.format(java.util.Locale.ROOT, "[VELOCITY-DEBUG] %s applied=%.3f expected>=%.3f (%d/%d)",
                        player.getName(), along, expectedH * config.velocityMinApplyRatio(), c, config.velocityViolations()));
            }
            if (c >= config.velocityViolations()) {
                handleViolation(player, "VELOCITY", lang.format("alert.velocity", along, expectedH), along, now);
                consecutive.put(id, 0);
            }
        } else {
            consecutive.remove(id);
        }
    }

    /**
     * True when the player is in a state that legitimately swallows a knockback: water or
     * lava drag, a vehicle taking the push instead, gliding or riptiding physics, flight —
     * including survival flight granted by another plugin (EssentialsX {@code /fly}), which
     * barely displaces a player at all — a climbable, or a block that slows movement.
     */
    private boolean absorbsKnockback(Player player) {
        return player.isInWater() || player.isInsideVehicle()
                || player.isGliding() || player.isRiptiding() || player.isFlying()
                || player.getAllowFlight() || isOnClimbable(player)
                || isInSlowingBlock(player.getLocation());
    }

    /**
     * True when a block around the player scales their motion down every tick. Cobwebs,
     * powder snow, sweet berry bushes and lava do so while the player is inside them (feet
     * or head block); honey blocks and soul sand while the player stands on them; a honey-block wall
     * beside the player slides them down and slows them too. Each of these eats most of a
     * knockback within the evaluation window.
     */
    static boolean isInSlowingBlock(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        org.bukkit.block.Block feet = loc.getBlock();
        if (slowsInside(feet.getType()) || slowsInside(feet.getRelative(0, 1, 0).getType())) return true;
        Material below = feet.getRelative(0, -1, 0).getType();
        if (slowsOnTop(feet.getType()) || slowsOnTop(below)) return true;
        int[][] sides = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] s : sides) {
            if (feet.getRelative(s[0], s[1], s[2]).getType() == Material.HONEY_BLOCK) return true;
            if (feet.getRelative(s[0], s[1] + 1, s[2]).getType() == Material.HONEY_BLOCK) return true;
        }
        return false;
    }

    private static boolean slowsInside(Material m) {
        return m == Material.COBWEB || m == Material.POWDER_SNOW || m == Material.SWEET_BERRY_BUSH
                || m == Material.LAVA;
    }

    private static boolean slowsOnTop(Material m) {
        return m == Material.HONEY_BLOCK || m == Material.SOUL_SAND;
    }

    /**
     * Record teleports, respawns and world changes, which move the player by an amount that
     * has nothing to do with the knockback being evaluated.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        relocated.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        relocated.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent event) {
        relocated.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    /**
     * Every knockback, including explosions that deal no damage — a wind charge launching its
     * own thrower is one, and it may arrive with no velocity event at all.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKnockback(io.papermc.paper.event.entity.EntityKnockbackEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        noteKnockback(player.getUniqueId(),
                event.getCause() == io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.EXPLOSION,
                System.currentTimeMillis());
    }

    void noteKnockback(UUID id, boolean explosion, long now) {
        knockbacks.compute(id, (k, v) -> {
            long[] r = v == null ? new long[]{0L, 0L} : v;
            r[0] = now;
            if (explosion) r[1] = now;
            return r;
        });
    }

    /**
     * True when another push overlaps the window that started at {@code startMs}: any
     * knockback after the tick of the evaluated one, or an explosion around it.
     */
    boolean pushedAgain(UUID id, long startMs) {
        long[] k = knockbacks.get(id);
        if (k == null) return false;
        if (k[0] > startMs + SAME_TICK_MS) return true;
        return k[1] != 0L && Math.abs(k[1] - startMs) <= EXPLOSION_OVERLAP_MS;
    }

    /** True when the player was relocated at or after {@code sinceMs}. */
    private boolean relocatedSince(UUID id, long sinceMs) {
        Long at = relocated.get(id);
        return at != null && at >= sinceMs;
    }

    /** True when a solid block sits directly in the knockback direction at body height. */
    private boolean isWall(Location start, double dirX, double dirZ) {
        Location probe = start.clone().add(dirX * 0.6, 0.5, dirZ * 0.6);
        return probe.getBlock().getType().isSolid();
    }

    /**
     * Climbable via the game's own tag, not a hand-rolled list: the latter misses the
     * *_PLANT growth variants (CAVE_VINES_PLANT etc.) and every future climbable, which
     * would flag a player who legitimately absorbed the knockback on a vine.
     */
    private boolean isOnClimbable(Player player) {
        if (player.isClimbing()) return true;
        Material at = player.getLocation().getBlock().getType();
        return at.isBlock() && org.bukkit.Tag.CLIMBABLE.isTagged(at);
    }

    private void handleViolation(Player player, String type, String details, double value, Location location) {
        if (database != null) {
            database.logAsync(player.getUniqueId(), "anticheat_" + type.toLowerCase(), value,
                    player.getName() + ": " + details + " @ " + CheckMath.formatLocation(location));
        }
        if (alertManager != null) {
            alertManager.addAlert(player, type, details, value, location);
        } else if (config.debugMode()) {
            plugin.getLogger().warning("[AntiCheat] " + player.getName() + " " + type + " - " + details);
        }
        if (violationManager != null) {
            violationManager.flag(player, type);
        }
    }

    public void cleanup(UUID playerId) {
        consecutive.remove(playerId);
        relocated.remove(playerId);
        knockbacks.remove(playerId);
    }
}
