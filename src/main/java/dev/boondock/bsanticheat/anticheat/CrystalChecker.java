package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.integration.GeyserHook;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.lang.LanguageManager;
import dev.boondock.bsanticheat.util.CheckMath;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CrystalAura and AnchorAura (event-based, both off by default).
 *
 * <p><b>CrystalAura</b> judges end crystals broken by a player's melee hit:
 * <ul>
 *   <li>broken too soon after spawning: within {@code crystalaura_min_break_ms}, or before
 *       the spawn could have reached the client and the attack come back (under half the
 *       measured round trip). An aura that predicts the next entity id attacks before it has
 *       seen the crystal;</li>
 *   <li>broken far off the aim ({@code crystalaura_max_angle});</li>
 *   <li>more breaks per second than {@code crystalaura_max_breaks_per_second}, over two
 *       consecutive one-second windows.</li>
 * </ul>
 * The first two count towards a streak ({@code crystalaura_violations} within 10s).
 *
 * <p><b>AnchorAura</b> times a respawn anchor from the glowstone charge to the detonating
 * click by the same player. A human has to charge, switch away from glowstone and click again.
 *
 * <p>Uncalibrated heuristics: client mods that speed up crystal PvP shorten these intervals
 * legitimately, which is why both ship off and log their measurements under debug_mode.
 *
 * <p>Events run on region threads (on Folia a crystal's spawn and its break may run on
 * different ones), so all state is in concurrent maps.
 */
public class CrystalChecker implements Listener {

    private final Plugin plugin;
    private final PluginConfig config;
    private final DatabaseManager database;
    private final LanguageManager lang;
    private LuckPermsHook luckPerms;
    private GeyserHook geyser;
    private MovementAlertManager alertManager;
    private ViolationManager violationManager;
    private TransactionManager transactionManager;

    // Crystal entity -> spawn time (ms). Removed on break; swept when old.
    private final Map<UUID, Long> crystalSpawns = new ConcurrentHashMap<>();
    // Per player: [0]=window start, [1]=breaks in window, [2]=consecutive windows over the limit.
    private final Map<UUID, long[]> breakRate = new ConcurrentHashMap<>();
    private final Map<UUID, long[]> crystalStreak = new ConcurrentHashMap<>();
    private final Map<UUID, long[]> anchorStreak = new ConcurrentHashMap<>();
    // Per player: the anchor they last charged and when.
    private final Map<UUID, AnchorCharge> lastCharge = new ConcurrentHashMap<>();

    private record AnchorCharge(UUID world, int x, int y, int z, long time) {}

    private static final long WINDOW_MS = 1000L;
    private static final int RATE_WINDOWS = 2;
    // A crystal nobody broke within this long no longer needs its spawn time.
    private static final long SPAWN_MEMORY_MS = 60_000L;
    private static final long SWEEP_INTERVAL_MS = 10_000L;
    // Below this round trip, half of it is within timing noise and says nothing.
    private static final double MIN_RTT_FOR_PREDICTION_MS = 80.0;
    // Closer than this the aim angle is not meaningful (overlapping hitboxes).
    private static final double MIN_ANGLE_DISTANCE = 1.0;
    // A charge older than this does not pair with a detonation.
    private static final long ANCHOR_PAIR_MS = 2000L;
    private volatile long lastSweep;

    public CrystalChecker(Plugin plugin, PluginConfig config, DatabaseManager database, LanguageManager lang) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.lang = lang;
    }

    public void setLuckPerms(LuckPermsHook luckPerms) { this.luckPerms = luckPerms; }
    public void setGeyser(GeyserHook geyser) { this.geyser = geyser; }
    public void setAlertManager(MovementAlertManager alertManager) { this.alertManager = alertManager; }
    public void setViolationManager(ViolationManager violationManager) { this.violationManager = violationManager; }
    public void setTransactionManager(TransactionManager transactionManager) { this.transactionManager = transactionManager; }

    // ---- CrystalAura ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        if (event.getEntityType() != EntityType.END_CRYSTAL) return;
        if (!config.crystalAuraDetectionEnabled()) return;
        noteCrystalSpawn(event.getEntity().getUniqueId(), System.currentTimeMillis());
    }

    void noteCrystalSpawn(UUID crystal, long now) {
        crystalSpawns.put(crystal, now);
        sweep(now);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal crystal)) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        onCrystalBreak(player, crystal, System.currentTimeMillis());
    }

    void onCrystalBreak(Player player, Entity crystal, long now) {
        Long spawned = crystalSpawns.remove(crystal.getUniqueId());
        if (!config.crystalAuraDetectionEnabled()) return;
        if (ServerLoad.isLagging(config, player)) return;
        if (Exemptions.isExempt(player, config, luckPerms, geyser)) return;
        if (player.getWorld() != crystal.getWorld()) return;
        UUID id = player.getUniqueId();

        int rate = noteBreakRate(id, now, config.crystalAuraMaxBreaksPerSecond());
        if (rate > 0) {
            flag(player, "CRYSTALAURA",
                    lang.format("alert.crystalaura_rate", rate, config.crystalAuraMaxBreaksPerSecond()), rate);
        }

        double rtt = transactionManager != null ? transactionManager.roundTripMs(id) : -1;
        long interval = spawned != null ? now - spawned : -1L;
        boolean fast = spawned != null && isImpossibleBreak(interval, config.crystalAuraMinBreakMs(), rtt);

        org.bukkit.Location eye = player.getEyeLocation();
        org.bukkit.util.BoundingBox box = crystal.getBoundingBox();
        double angle = distanceToBox(eye, box) >= MIN_ANGLE_DISTANCE
                ? CombatChecker.angleToBox(eye.toVector(), eye.getDirection(), box) : 0.0;
        boolean offAim = angle > config.crystalAuraMaxAngle();

        if (config.debugMode()) {
            plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                    "[CRYSTAL-DEBUG] %s interval=%s rtt=%.0fms angle=%.0f -> fast=%b offAim=%b",
                    player.getName(), interval < 0 ? "n/a" : interval + "ms", Math.max(rtt, 0), angle, fast, offAim));
        }
        if (!fast && !offAim) return;
        int c = CombatChecker.bumpStreak(crystalStreak, id, now, 0L);
        if (c < config.crystalAuraViolations()) return;
        crystalStreak.remove(id);
        if (fast) {
            flag(player, "CRYSTALAURA", lang.format("alert.crystalaura_fast", interval), interval);
        } else {
            flag(player, "CRYSTALAURA", lang.format("alert.crystalaura_angle", angle), angle);
        }
    }

    /**
     * Whether a crystal broken {@code intervalMs} after spawning is too fast: below the
     * configured floor, or below half the round trip — the spawn has to reach the client and
     * the attack has to come back before a client that waits for it can hit.
     */
    static boolean isImpossibleBreak(long intervalMs, long minBreakMs, double rttMs) {
        if (intervalMs < 0) return false;
        if (intervalMs < minBreakMs) return true;
        return rttMs >= MIN_RTT_FOR_PREDICTION_MS && intervalMs < rttMs / 2.0;
    }

    /**
     * Count one break; returns the rate of the window that completed a run of
     * {@link #RATE_WINDOWS} consecutive windows over {@code max}, else 0.
     */
    int noteBreakRate(UUID id, long now, int max) {
        long[] st = breakRate.computeIfAbsent(id, k -> new long[]{now, 0L, 0L});
        synchronized (st) {
            if (now - st[0] >= WINDOW_MS) {
                // The finished window decides the streak; a quiet gap longer than one window
                // breaks it.
                boolean adjacent = now - st[0] < 2 * WINDOW_MS;
                st[2] = st[1] > max && adjacent ? st[2] : 0L;
                st[0] = now;
                st[1] = 0L;
            }
            st[1]++;
            if (st[1] == max + 1) {
                st[2]++;
                if (st[2] >= RATE_WINDOWS) {
                    st[2] = 0L;
                    return (int) st[1];
                }
            }
            return 0;
        }
    }

    // ---- AnchorAura ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.RESPAWN_ANCHOR) return;
        if (!config.anchorAuraDetectionEnabled()) return;
        if (!(block.getBlockData() instanceof RespawnAnchor anchor)) return;
        onAnchorClick(event.getPlayer(), block, anchor.getCharges(), anchor.getMaximumCharges(),
                event.getItem(), System.currentTimeMillis());
    }

    /**
     * One right-click on an anchor, with its charge state BEFORE the click. Glowstone on a
     * not-full anchor charges it; any other click on a charged anchor outside the Nether
     * detonates it (sneaking with an item in hand places the item instead).
     */
    void onAnchorClick(Player player, Block block, int charges, int maxCharges, ItemStack item, long now) {
        UUID id = player.getUniqueId();
        World world = block.getWorld();
        boolean glowstone = item != null && item.getType() == Material.GLOWSTONE;
        if (glowstone && charges < maxCharges) {
            lastCharge.put(id, new AnchorCharge(world.getUID(), block.getX(), block.getY(), block.getZ(), now));
            return;
        }
        if (charges <= 0 || world.getEnvironment() == World.Environment.NETHER) return;
        if (player.isSneaking() && item != null && !item.getType().isAir()) return;
        AnchorCharge ch = lastCharge.remove(id);
        if (ch == null || !ch.world().equals(world.getUID())
                || ch.x() != block.getX() || ch.y() != block.getY() || ch.z() != block.getZ()) return;
        long interval = now - ch.time();
        if (interval > ANCHOR_PAIR_MS) return;
        if (ServerLoad.isLagging(config, player)) return;
        if (Exemptions.isExempt(player, config, luckPerms, geyser)) return;
        if (config.debugMode()) {
            plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                    "[ANCHOR-DEBUG] %s charge->detonate %dms (min %dms)",
                    player.getName(), interval, config.anchorAuraMinIntervalMs()));
        }
        if (interval >= config.anchorAuraMinIntervalMs()) return;
        int c = CombatChecker.bumpStreak(anchorStreak, id, now, 0L);
        if (c >= config.anchorAuraViolations()) {
            anchorStreak.remove(id);
            flag(player, "ANCHORAURA", lang.format("alert.anchoraura", interval), interval);
        }
    }

    // ---- shared ----

    private static double distanceToBox(org.bukkit.Location eye, org.bukkit.util.BoundingBox box) {
        double dx = eye.getX() - Math.max(box.getMinX(), Math.min(eye.getX(), box.getMaxX()));
        double dy = eye.getY() - Math.max(box.getMinY(), Math.min(eye.getY(), box.getMaxY()));
        double dz = eye.getZ() - Math.max(box.getMinZ(), Math.min(eye.getZ(), box.getMaxZ()));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void sweep(long now) {
        if (now - lastSweep < SWEEP_INTERVAL_MS) return;
        lastSweep = now;
        crystalSpawns.values().removeIf(t -> t < now - SPAWN_MEMORY_MS);
    }

    private void flag(Player player, String type, String details, double value) {
        if (database != null) {
            database.logAsync(player.getUniqueId(), "anticheat_" + type.toLowerCase(), value,
                    player.getName() + ": " + details + " @ " + CheckMath.formatLocation(player.getLocation()));
        }
        if (alertManager != null) {
            alertManager.addAlert(player, type, details, value, player.getLocation());
        } else if (config.debugMode()) {
            plugin.getLogger().warning("[AntiCheat] " + player.getName() + " " + type + " - " + details);
        }
        if (violationManager != null) {
            violationManager.flag(player, type);
        }
    }

    boolean remembersSpawn(UUID crystal) {
        return crystalSpawns.containsKey(crystal);
    }

    public void cleanup(UUID playerId) {
        breakRate.remove(playerId);
        crystalStreak.remove(playerId);
        anchorStreak.remove(playerId);
        lastCharge.remove(playerId);
    }
}
