package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.integration.GeyserHook;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.lang.LanguageManager;
import dev.boondock.bsanticheat.util.CheckMath;
import dev.boondock.bsanticheat.util.Constants;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * World-interaction checks: Nuker / FastBreak (breaking too many blocks per second) and
 * FastPlace (placing too many blocks per second).
 *
 * <p>Event-based and rate-only, so thresholds are generous to avoid false positives from
 * legitimate fast mining (Efficiency + Haste on instamine blocks). Creative is exempt.
 */
public class WorldChecker implements Listener {

    private static final long WINDOW_MS = 1000L;

    private final Plugin plugin;
    private final PluginConfig config;
    private final DatabaseManager database;
    private final LanguageManager lang;
    private LuckPermsHook luckPerms;
    private GeyserHook geyser;
    private MovementAlertManager alertManager;
    private ViolationManager violationManager;

    private final Map<UUID, ConcurrentLinkedDeque<Long>> breaks = new ConcurrentHashMap<>();
    private final Map<UUID, ConcurrentLinkedDeque<Long>> places = new ConcurrentHashMap<>();
    // Scaffold: consecutive places made while not looking at the block
    private final Map<UUID, Integer> consecutiveScaffold = new ConcurrentHashMap<>();
    // FastBreak: when the player started digging which block
    private final Map<UUID, DigState> digStart = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> consecutiveFastBreak = new ConcurrentHashMap<>();
    // Nuker/FastPlace: one-second windows over the rate limit, counted within a time window.
    // State: [0]=count, [1]=timestamp of the last over-limit window (ms).
    //
    // These are the only rate checks whose counter cannot be reset by an ordinary event:
    // a window that stays UNDER the limit produces no event at all here — the counters are
    // only touched on the would-flag path — so a plain counter would never come down and
    // single bursts hours apart would add up to "the rate was held up". Counts therefore
    // expire after a time window, as in CombatChecker's bumpStreak.
    private final Map<UUID, long[]> nukerStreak = new ConcurrentHashMap<>();
    private final Map<UUID, long[]> fastPlaceStreak = new ConcurrentHashMap<>();
    // How long an over-limit window keeps counting towards the next one. A cheat crosses
    // the limit again within seconds; unrelated bursts are minutes apart.
    private static final long RATE_STREAK_WINDOW_MS = 10_000L;
    // Over-limit windows closer together than this are one burst and count once. The window
    // is cleared on every exceedance, so the rest of a single burst (one vein-miner action
    // of dozens of blocks) would otherwise refill it and count again.
    private static final long RATE_BUMP_SPACING_MS = WINDOW_MS;
    // FastBreak: server ticks a legitimate dig may appear shorter by, because START and
    // FINISH are processed at tick boundaries and network jitter can delay one relative to
    // the other.
    private static final long FASTBREAK_TICK_SLACK = 2L;

    // Block events currently being dispatched on this thread, outermost first. An event
    // fired while another is still in dispatch was fired by a plugin from inside a handler
    // (vein miners, tree fellers, multi-block tools) — the player sent one action, not many.
    private static final ThreadLocal<java.util.ArrayDeque<org.bukkit.event.Event>> DISPATCH =
            ThreadLocal.withInitial(java.util.ArrayDeque::new);

    // Clock for the rate checks; replaceable so tests can step time instead of sleeping.
    java.util.function.LongSupplier clock = System::currentTimeMillis;

    private record DigState(long startTick, long expectedTicks, String world, int x, int y, int z) {
        boolean matches(Block block) {
            return block.getX() == x && block.getY() == y && block.getZ() == z
                    && block.getWorld().getName().equals(world);
        }
    }

    public WorldChecker(Plugin plugin, PluginConfig config, DatabaseManager database, LanguageManager lang) {
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

    /**
     * Over-limit-window streak: increment while the previous one is still recent, restart
     * once it has lapsed. An exceedance less than {@link #RATE_BUMP_SPACING_MS} after the
     * last counted one is the same burst and leaves the count unchanged. Returns the current
     * streak length.
     *
     * <p>Package-private and taking its clock as an argument, so the lapse rule can be
     * tested without a test that sleeps for the length of the window.
     */
    static int bumpStreak(Map<UUID, long[]> map, UUID id, long now, long windowMs) {
        long[] st = map.compute(id, (k, v) -> {
            if (v == null || now - v[1] > windowMs) return new long[]{1, now};
            if (now - v[1] < RATE_BUMP_SPACING_MS) return v;
            v[0]++;
            v[1] = now;
            return v;
        });
        return (int) st[0];
    }

    /** True while an exceedance would still belong to the burst last counted in this streak. */
    static boolean inCountedBurst(Map<UUID, long[]> map, UUID id, long now) {
        long[] st = map.get(id);
        return st != null && now - st[1] < RATE_BUMP_SPACING_MS;
    }

    /**
     * One over-limit window: counted and cleared, unless it is still the burst counted last.
     * Then the window is left as it is — clearing it would only let the rest of the same
     * burst refill it — and it is counted once the spacing has passed if the rate held.
     */
    private int countExceedance(Map<UUID, ConcurrentLinkedDeque<Long>> window, Map<UUID, long[]> streak, UUID id) {
        long now = clock.getAsLong();
        if (inCountedBurst(streak, id, now)) return 0;
        clearWindow(window, id);
        return bumpStreak(streak, id, now, RATE_STREAK_WINDOW_MS);
    }

    /** Track when a player starts digging a block (for the per-block FastBreak timing). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        if (!config.worldChecksEnabled() || !config.fastBreakDetectionEnabled()) return;
        UUID id = event.getPlayer().getUniqueId();
        if (event.getInstaBreak()) {
            digStart.remove(id); // instamine has no measurable dig time
            return;
        }
        Block b = event.getBlock();
        // Expected dig time measured NOW: the player's state at dig start (on ground /
        // airborne, haste, tool) is what governed most of the actual dig. Timed in the
        // player's own server ticks, not milliseconds: the server's break-progress rule counts
        // ticks too, and wall-clock time drifts from ticks whenever the server lags or
        // catches up.
        digStart.put(id, new DigState(event.getPlayer().getTicksLived(), expectedDigTicks(b, event.getPlayer()),
                b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
    }

    /**
     * Expected dig time in ticks from getBreakSpeed() (damage per tick, tool/enchants/haste/
     * conditions included), or 0 when instamined / not measurable.
     */
    private long expectedDigTicks(Block block, Player player) {
        float speed = block.getBreakSpeed(player);
        if (speed <= 0.0f || speed >= 1.0f) return 0L;
        return (long) Math.ceil(1.0 / speed);
    }

    /**
     * Whether a dig of {@code ticksTaken} server ticks is too fast for a block expected to
     * take {@code expectedTicks}. Package-private and free of server state for testing.
     *
     * <p>Vanilla already rejects breaks below 70% of the expected progress, counted in the
     * same ticks, so at the default tolerance of 0.7 this only fires if that server rule is
     * bypassed; a higher tolerance catches breaks between 70% and the tolerance.
     */
    static boolean isTooFast(long ticksTaken, long expectedTicks, double tolerance) {
        return ticksTaken + FASTBREAK_TICK_SLACK < expectedTicks * tolerance;
    }

    /** Outermost handler: note that a block event is in dispatch on this thread. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreakEnter(BlockBreakEvent event) {
        enterDispatch(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlaceEnter(BlockPlaceEvent event) {
        enterDispatch(event);
    }

    /**
     * Leave the dispatch of this event and report whether it was fired from inside another
     * block event's dispatch. Tolerates events that never passed the LOWEST handler (called
     * directly, or registered late).
     */
    private static boolean leaveDispatch(org.bukkit.event.Event event) {
        java.util.ArrayDeque<org.bukkit.event.Event> stack = DISPATCH.get();
        if (stack.peekLast() == event) {
            stack.pollLast();
        } else if (stack.contains(event)) {
            // An inner event skipped its MONITOR pass; drop everything above this one.
            while (!stack.isEmpty() && stack.pollLast() != event) { /* unwind */ }
        }
        return !stack.isEmpty();
    }

    private static void enterDispatch(org.bukkit.event.Event event) {
        java.util.ArrayDeque<org.bukkit.event.Event> stack = DISPATCH.get();
        // Real nesting is a handful deep; anything beyond that is a leftover from an event
        // whose MONITOR pass never ran, and must not mark every later event as nested.
        if (stack.size() >= 16) stack.clear();
        stack.addLast(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockDamageAbort(BlockDamageAbortEvent event) {
        digStart.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        // Always leave the dispatch, cancelled or not, so the nesting stack stays balanced.
        boolean nested = leaveDispatch(event);
        // A break a plugin fires from inside another break's handling is part of one player
        // action: it neither counts towards the rate nor consumes the player's dig timing.
        if (event.isCancelled() || nested) return;
        if (!config.worldChecksEnabled()) return;
        Player player = event.getPlayer();
        if (ServerLoad.isLagging(config, player)) return;
        UUID id = player.getUniqueId();
        DigState dig = digStart.remove(id);
        if (Exemptions.isExempt(player, config, luckPerms, geyser)) return;

        // Nuker: too many blocks per second.
        // The rate alone is not enough to flag on. Plugins that break several blocks in one
        // action (vein miners, tree fellers, MMOItems-style tools) fire a burst of
        // BlockBreakEvents inside a single tick, and instamining with Efficiency V plus Haste
        // reaches ~20 blocks/s by hand — both sit close enough to the cap that one bundled
        // window crosses it. A cheat holds the rate up; a burst does not, so require the
        // window to come back over the limit repeatedly.
        if (config.nukerDetectionEnabled()) {
            int rate = recordAndCount(breaks, id);
            int max = config.nukerMaxBreaksPerSecond();
            if (rate > max) {
                int c = countExceedance(breaks, nukerStreak, id);
                if (c >= config.nukerViolations()) {
                    handleViolation(player, "NUKER", lang.format("alert.nuker", rate, max), rate,
                            event.getBlock().getLocation());
                    nukerStreak.remove(id);
                }
            }
        }

        // FastBreak: block broken clearly faster than its expected break time. The
        // expected time is measured at BOTH dig start and dig end and the smaller one
        // wins: the player's state can legitimately change mid-dig (landing from a jump,
        // a haste beacon kicking in, a tool switch) and judging only the end state reads
        // the on-ground/boosted dig as impossibly fast. Instamine and very short digs
        // are skipped.
        if (config.fastBreakDetectionEnabled() && dig != null && dig.matches(event.getBlock())) {
            long expectedEndTicks = expectedDigTicks(event.getBlock(), player);
            if (expectedEndTicks > 0L && dig.expectedTicks() > 0L) {
                long expectedTicks = Math.min(dig.expectedTicks(), expectedEndTicks);
                long actualTicks = player.getTicksLived() - dig.startTick();
                long expectedMs = expectedTicks * 50L;
                long actualMs = actualTicks * 50L;
                if (expectedMs >= Constants.FASTBREAK_MIN_EXPECTED_MS && actualTicks >= 0L
                        && isTooFast(actualTicks, expectedTicks, config.fastBreakTolerance())) {
                    int c = consecutiveFastBreak.merge(id, 1, Integer::sum);
                    if (config.debugMode()) {
                        plugin.getLogger().info(String.format(java.util.Locale.ROOT, "[FASTBREAK-DEBUG] %s actual=%dms expected=%dms (%d/%d)",
                                player.getName(), actualMs, expectedMs, c, config.fastBreakViolations()));
                    }
                    if (c >= config.fastBreakViolations()) {
                        handleViolation(player, "FASTBREAK",
                                lang.format("alert.fastbreak", actualMs, expectedMs), actualMs,
                                event.getBlock().getLocation());
                        consecutiveFastBreak.put(id, 0);
                    }
                } else {
                    consecutiveFastBreak.remove(id);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent event) {
        boolean nested = leaveDispatch(event);
        // A place fired by a plugin from inside another block event is not a player action.
        if (event.isCancelled() || nested) return;
        if (!config.worldChecksEnabled()) return;
        Player player = event.getPlayer();
        if (ServerLoad.isLagging(config, player)) return;
        if (Exemptions.isExempt(player, config, luckPerms, geyser)) return;
        UUID id = player.getUniqueId();

        // FastPlace: too many blocks per second
        if (config.fastPlaceDetectionEnabled()) {
            int rate = recordAndCount(places, id);
            int max = config.fastPlaceMaxPerSecond();
            if (rate > max) {
                // Same reasoning as Nuker: one bundled window is not evidence, and windows
                // far apart are not a streak.
                int c = countExceedance(places, fastPlaceStreak, id);
                if (c >= config.fastPlaceViolations()) {
                    handleViolation(player, "FASTPLACE", lang.format("alert.fastplace", rate, max), rate,
                            event.getBlock().getLocation());
                    fastPlaceStreak.remove(id);
                }
            }
        }

        // Scaffold: block placed far outside the player's view (placing "blind" while
        // bridging/towering). The angle is measured to the nearest point of the CLICKED
        // block, not the placed block's center: placing a block at your own feet
        // legitimately puts the new block below/behind you (95°+ off aim) while the face
        // you actually clicked was in view the whole time.
        if (config.scaffoldDetectionEnabled()) {
            Block against = event.getBlockAgainst();
            org.bukkit.Location eye = player.getEyeLocation();
            double nx = Math.max(against.getX(), Math.min(eye.getX(), against.getX() + 1.0));
            double ny = Math.max(against.getY(), Math.min(eye.getY(), against.getY() + 1.0));
            double nz = Math.max(against.getZ(), Math.min(eye.getZ(), against.getZ() + 1.0));
            Vector look = eye.getDirection();
            Vector toBlock = new Vector(nx, ny, nz).subtract(eye.toVector());
            if (toBlock.lengthSquared() > 1.0e-6) {
                double angle = Math.toDegrees(look.angle(toBlock));
                if (config.debugMode()) {
                    plugin.getLogger().info(String.format(java.util.Locale.ROOT, "[SCAFFOLD-DEBUG] %s angle=%.0f (max %.0f)",
                            player.getName(), angle, config.scaffoldMaxAngle()));
                }
                if (angle > config.scaffoldMaxAngle()) {
                    int c = consecutiveScaffold.merge(id, 1, Integer::sum);
                    if (c >= config.scaffoldViolations()) {
                        handleViolation(player, "SCAFFOLD",
                                lang.format("alert.scaffold", angle), angle, event.getBlock().getLocation());
                        consecutiveScaffold.put(id, 0);
                    }
                } else {
                    consecutiveScaffold.remove(id);
                }
            }
        }
    }

    /**
     * Empty a player's rate window if it is still there. The entry is created by
     * {@link #recordAndCount} moments earlier, but a disconnect handled on another thread
     * (Folia runs quit and block events on different threads) can remove it in between —
     * and {@code map.get(id).clear()} then throws inside a block-break handler.
     */
    private static void clearWindow(Map<UUID, ConcurrentLinkedDeque<Long>> map, UUID id) {
        ConcurrentLinkedDeque<Long> window = map.get(id);
        if (window != null) window.clear();
    }

    /** Add a timestamp, trim to the sliding window and return the current count. */
    private int recordAndCount(Map<UUID, ConcurrentLinkedDeque<Long>> map, UUID id) {
        long now = clock.getAsLong();
        long cutoff = now - WINDOW_MS;
        ConcurrentLinkedDeque<Long> deque = map.computeIfAbsent(id, k -> new ConcurrentLinkedDeque<>());
        deque.addLast(now);
        Long head;
        // Entries exactly one window old are out, so a burst stops counting after WINDOW_MS
        // just as RATE_BUMP_SPACING_MS lapses.
        while ((head = deque.peekFirst()) != null && head <= cutoff) {
            deque.pollFirst();
        }
        return deque.size();
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
        breaks.remove(playerId);
        places.remove(playerId);
        consecutiveScaffold.remove(playerId);
        digStart.remove(playerId);
        consecutiveFastBreak.remove(playerId);
        nukerStreak.remove(playerId);
        fastPlaceStreak.remove(playerId);
    }
}
