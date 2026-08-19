package dev.boondock.bsanticheat.anticheat;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEditBook;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.integration.GeyserHook;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.lang.LanguageManager;
import dev.boondock.bsanticheat.util.Constants;
import dev.boondock.bsanticheat.util.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Packet-level checks (via PacketEvents).
 * <ul>
 *   <li>AutoClicker — from arm-swing (ANIMATION) packets, using two signals: raw clicks
 *       per second, and click-interval consistency (low standard deviation = metronomic =
 *       autoclicker, which also catches slow-but-perfectly-regular clickers).</li>
 *   <li>BadPackets — impossible rotation values.</li>
 * </ul>
 *
 * <p>Runs on Netty threads, so all Bukkit access (alerts) is hopped back to the main
 * thread before use.
 *
 * <p><b>Threading:</b> the per-player maps are concurrent, but their VALUES are plain
 * {@link ArrayDeque}s. That is safe because a connection's packets are handled on that
 * connection's own event-loop thread, so one player's state is never touched concurrently.
 * If that ever stops holding, these need to become synchronized or concurrent deques.
 */
public class PacketChecker implements PacketListener, org.bukkit.event.Listener {

    private static final long WINDOW_MS = 1000L;
    // How many recent swing timestamps to keep per player for interval analysis.
    private static final int SAMPLE_CAP = 40;
    // Recent clicking window analysed for consistency. Wide enough that even slow
    // (~2 CPS) clickers accumulate enough samples; pauses still raise the outlier signal.
    private static final long CONSISTENCY_WINDOW_MS = 8000L;

    private final Plugin plugin;
    private final PluginConfig config;
    private final DatabaseManager database;
    private final LanguageManager lang;
    private LuckPermsHook luckPerms;
    private GeyserHook geyser;
    private MovementAlertManager alertManager;
    private ViolationManager violationManager;
    private TransactionManager transactionManager;

    private final Map<UUID, ConcurrentLinkedDeque<Long>> clicks = new ConcurrentHashMap<>();
    // Timer balance per player: [0]=balance(centi-ms), [1]=last packet time(ms),
    // [2]=time the balance first went over the limit (0 = currently under)
    private final Map<UUID, long[]> timerState = new ConcurrentHashMap<>();
    private static final long TICK_MS = 50L;
    // Silence longer than this between movement packets is a stalled connection, not play:
    // a vanilla client sends one every tick even while standing still.
    private static final long PACKET_GAP_MS = 400L;
    // How long the catch-up burst after such a stall is left unjudged.
    private static final long STALL_GRACE_MS = 3000L;
    // AimSnap looks at three consecutive rotation packets, which should span ~2 ticks. This
    // bounds how far apart they may actually have arrived before the "flick" is just a player
    // turning around at human speed with a packet gap in the middle.
    private static final long AIMSNAP_MAX_SPAN_MS = 150L;
    private final Map<UUID, Long> timerGraceUntil = new ConcurrentHashMap<>();
    // Held-button (mining, or simply swinging at air) signature to exclude from AutoClicker.
    // It is identified by the RATE the swings arrive at, not by how many land in a window:
    // a held button is bound to the server tick, so its typical interval is TICK_MS. How far
    // the median interval may sit from one tick — 6ms accepts 44-56ms, i.e. 17.9-22.7 CPS,
    // and still excludes a 23 CPS clicker (43.5ms). The upper bound matters: it is what keeps
    // the exclusion from becoming a hiding place for a genuine clicker.
    private static final double HELD_TICK_TOLERANCE_MS = 6.0;
    // Spread around that interval, as median absolute deviation. A single pause between
    // swings inflates a standard deviation but leaves the MAD where it is.
    private static final double HELD_MAD_MAX_MS = 12.0;
    // Below this many intervals the standard deviation is noise, not a signature.
    private static final int MIN_INTERVALS_FOR_SD = 7;
    // KillAura rotation GCD analysis
    private final Map<UUID, Float> lastYaw = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> yawDeltas = new ConcurrentHashMap<>();
    private static final double ROT_EXPANDER = 131072.0;
    // AimSnap: last 3 look directions + recent snap timestamps
    private final Map<UUID, Deque<double[]>> recentDirs = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> snapTimes = new ConcurrentHashMap<>();
    // Packet flood: [0]=window start(ms), [1]=count in window, [2]=consecutive windows over the limit
    private final Map<UUID, long[]> packetCounts = new ConcurrentHashMap<>();
    // Mining state: while a player is breaking a block the client sends a swing every tick,
    // which would false-flag AutoClicker. Value = timestamp until which we treat the player
    // as mining (set on START_DIGGING with a safety cap, cleared on FINISH/CANCEL).
    private final Map<UUID, Long> miningUntil = new ConcurrentHashMap<>();
    private static final long MINING_SAFETY_MS = 5000L;
    // Grace after a teleport/join/respawn/world change. Loading the destination stalls
    // the CLIENT, which then flushes everything it queued in one burst — that burst is
    // what produced the Timer/PacketFlood alerts. MovementChecker has always had these
    // windows; the packet checks had none, because they live outside the Bukkit event
    // world. Hence this class now listens for those events too.
    private final Map<UUID, Long> graceUntil = new ConcurrentHashMap<>();

    public PacketChecker(Plugin plugin, PluginConfig config, DatabaseManager database, LanguageManager lang) {
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

    // ---- Bukkit events that make a client stall and then burst ----

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        grant(event.getPlayer().getUniqueId());
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        grant(event.getPlayer().getUniqueId());
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        grant(event.getPlayer().getUniqueId());
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent event) {
        grant(event.getPlayer().getUniqueId());
    }

    private void grant(UUID id) {
        graceUntil.put(id, System.currentTimeMillis() + Constants.PACKET_GRACE_MS);
        // The accumulated balance is meaningless across a teleport — start clean.
        timerState.remove(id);
        packetCounts.remove(id);
    }

    /** True while the client is still catching up after a teleport/join/world change. */
    private boolean inGrace(UUID id) {
        Long until = graceUntil.get(id);
        return until != null && System.currentTimeMillis() < until;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();

        // Transaction pongs feed the latency system and must run regardless of which
        // checks are enabled — they are latency infrastructure, not a detection.
        if (type == PacketType.Play.Client.PONG) {
            if (transactionManager != null) {
                User u = event.getUser();
                if (u != null && u.getUUID() != null) {
                    transactionManager.onPong(u.getUUID(), new WrapperPlayClientPong(event).getId(), u.getName());
                }
            }
            return;
        }

        if (!config.packetChecksEnabled()) return;

        User u = event.getUser();
        boolean grace = u != null && u.getUUID() != null && inGrace(u.getUUID());

        // Crash protection runs even under lag and even in grace — it CANCELS malicious
        // packets, so suspending it would open exactly the hole it exists to close.
        // Flood counting does back off during grace: a client flushing its queue after a
        // teleport legitimately produces one oversized window.
        if (!grace) handleFlood(event);
        if (type == PacketType.Play.Client.EDIT_BOOK || type == PacketType.Play.Client.UPDATE_SIGN) {
            handleCrasher(event, type);
        }

        // Track mining so held-to-mine swings don't count as AutoClicker clicks.
        if (type == PacketType.Play.Client.PLAYER_DIGGING) {
            handleDigging(event);
        }

        if (grace || ServerLoad.isLagging(config)) return;
        if (type == PacketType.Play.Client.ANIMATION) {
            handleSwing(event);
        } else if (WrapperPlayClientPlayerFlying.isFlying(type)) {
            handleFlying(event);
            handleTimer(event.getUser());
        }
    }

    /**
     * Track block-mining state from PLAYER_DIGGING so held-to-mine arm swings are not
     * counted as AutoClicker clicks (they fire once per tick while breaking a block).
     */
    private void handleDigging(PacketReceiveEvent event) {
        User user = event.getUser();
        if (user == null || user.getUUID() == null) return;
        DiggingAction action = new WrapperPlayClientPlayerDigging(event).getAction();
        long now = System.currentTimeMillis();
        if (action == DiggingAction.START_DIGGING) {
            miningUntil.put(user.getUUID(), now + MINING_SAFETY_MS); // mining until finish (safety-capped)
        } else if (action == DiggingAction.FINISHED_DIGGING || action == DiggingAction.CANCELLED_DIGGING) {
            miningUntil.put(user.getUUID(), now); // done
        }
    }

    /** True while the player is (very recently) breaking a block. */
    private boolean isMining(UUID id) {
        Long until = miningUntil.get(id);
        return until != null && System.currentTimeMillis() < until;
    }

    /**
     * Packet flood: raw packets per second per connection. A vanilla client peaks well
     * below 200/s even in hectic PvP; floods (crash/lag bots) send thousands.
     */
    private void handleFlood(PacketReceiveEvent event) {
        if (!config.packetFloodDetectionEnabled()) return;
        User user = event.getUser();
        if (user == null || user.getUUID() == null) return;
        UUID id = user.getUUID();

        long now = System.currentTimeMillis();
        long[] st = packetCounts.computeIfAbsent(id, k -> new long[]{now, 0L, 0L});
        synchronized (st) {
            if (now - st[0] >= WINDOW_MS) {
                // A window that stayed under the limit ends the streak: a connection
                // catching up after a stall floods exactly one window, an attack floods
                // every window it lasts.
                if (st[1] <= config.packetFloodMaxPerSecond()) st[2] = 0;
                st[0] = now;
                st[1] = 1;
                return;
            }
            st[1]++;
            if (st[1] > config.packetFloodMaxPerSecond()) {
                // Read the count BEFORE the window is restarted below. The alert used to
                // report the configured limit plus one, which is the same number every time
                // and says nothing about what was actually seen — a logged flood could not
                // be told apart from a marginal one afterwards, nor the limit tuned from it.
                long observed = st[1];
                long over = ++st[2];
                st[0] = now;
                st[1] = 0;
                if (over >= config.packetFloodWindows()) {
                    st[2] = 0;
                    String name = user.getName();
                    flagSimple(id, name, "PACKETFLOOD", lang.format("alert.packetflood", observed), observed);
                }
            }
        }
    }

    /**
     * Crash protection: oversized book/sign payloads (classic crasher exploits). The
     * malicious packet is cancelled so the server never processes it; the flag is
     * raised afterwards on the main thread.
     */
    private void handleCrasher(PacketReceiveEvent event, PacketTypeCommon type) {
        if (!config.crasherDetectionEnabled()) return;
        User user = event.getUser();
        if (user == null || user.getUUID() == null) return;
        UUID id = user.getUUID();

        if (type == PacketType.Play.Client.EDIT_BOOK) {
            WrapperPlayClientEditBook wrapper = new WrapperPlayClientEditBook(event);
            var pages = wrapper.getPages();
            int pageCount = pages != null ? pages.size() : 0;
            long totalChars = 0;
            boolean oversizedPage = false;
            if (pages != null) {
                for (String page : pages) {
                    if (page == null) continue;
                    totalChars += page.length();
                    if (page.length() > Constants.CRASHER_MAX_BOOK_PAGE_CHARS) oversizedPage = true;
                }
            }
            if (pageCount > Constants.CRASHER_MAX_BOOK_PAGES || oversizedPage
                    || totalChars > Constants.CRASHER_MAX_BOOK_TOTAL_CHARS) {
                event.setCancelled(true);
                int pc = pageCount;
                flagSimple(id, user.getName(), "CRASHER", lang.format("alert.crasher_book", pc), pc);
            }
        } else {
            WrapperPlayClientUpdateSign wrapper = new WrapperPlayClientUpdateSign(event);
            String[] lines = wrapper.getTextLines();
            if (lines != null) {
                for (String line : lines) {
                    if (line != null && line.length() > Constants.CRASHER_MAX_SIGN_LINE_CHARS) {
                        event.setCancelled(true);
                        flagSimple(id, user.getName(), "CRASHER", lang.get("alert.crasher_sign"), line.length());
                        return;
                    }
                }
            }
        }
    }

    /**
     * Timer: each movement (flying) packet claims one tick (50ms) of game time. A balance
     * accumulates 50ms per packet minus the real time elapsed; if the client sends packets
     * faster than real time (game-speed/timer hack), the balance grows past the limit.
     *
     * <p>The balance is kept in hundredths of a millisecond so the 1% drift leak survives
     * integer arithmetic: real time is counted at 101%, which absorbs benign client clock
     * drift (cheap hardware clocks run up to ~0.5% fast — that otherwise accumulates
     * ~10ms/s and periodically crosses any fixed limit). A real timer hack gains far more.
     */
    private void handleTimer(User user) {
        if (!config.timerDetectionEnabled()) return;
        if (user == null || user.getUUID() == null) return;
        UUID id = user.getUUID();
        long now = System.currentTimeMillis();

        long[] st = timerState.computeIfAbsent(id, k -> new long[]{0L, now, 0L});
        long elapsed = now - st[1];

        // A gap in the packet stream means the connection stalled — a vanilla client sends a
        // movement packet every tick even standing still, so silence this long is not normal
        // play. What follows is the client flushing what it queued, and every queued packet
        // credits a full tick with no real time attached: the balance climbs by the whole
        // backlog at once. The sustained-excursion rule does not catch it, because an
        // eight-second backlog is not paid off in a few hundred milliseconds either.
        // Live data: two TIMER alerts, one of them 8284ms, inside the minutes a player was
        // timing out and reconnecting repeatedly.
        if (elapsed > PACKET_GAP_MS) {
            st[0] = 0L;             // discard the balance built before the stall
            st[1] = now;
            st[2] = 0L;             // and any excursion in progress
            timerGraceUntil.put(id, now + STALL_GRACE_MS);
            return;
        }
        Long graceUntil = timerGraceUntil.get(id);
        if (graceUntil != null) {
            if (now < graceUntil) {
                st[1] = now;        // keep the clock moving, but do not judge the catch-up
                return;
            }
            timerGraceUntil.remove(id);
        }
        long balance = st[0] + TICK_MS * 100L - elapsed * 101L; // centi-ms, 1% drift leak
        if (balance < -100000L) balance = -100000L; // floor (-1000ms): no unlimited idle credit
        st[1] = now;

        st[0] = balance;

        // The balance must stay over the limit, not merely touch it. TCP delivers packets
        // in bundles: five movement packets arriving in the same millisecond each credit a
        // full tick with no real time to subtract, so the balance jumps by exactly 5x50ms
        // and crosses any limit instantly — that was the single biggest source of false
        // Timer alerts. The gap that always follows such a bundle brings the balance back
        // down within a few hundred ms, while a real timer hack holds it up indefinitely.
        if (balance > config.timerMaxBalanceMs() * 100L) {
            if (st[2] == 0L) st[2] = now;                       // excursion started
            if (now - st[2] >= config.timerSustainedMs()) {
                st[0] = 0L;
                st[2] = 0L;
                long balMs = balance / 100L;
                String details = lang.format("alert.timer", balMs);
                Scheduler.runForPlayer(plugin, id, () -> flagOnMain(id, "TIMER", details, balMs));
            }
        } else {
            st[2] = 0L; // back under the limit — it was a bundle, not a hack
        }
    }

    /**
     * AutoClicker, from arm-swing (ANIMATION) packets — covers clicking on air, blocks or
     * entities. Two independent signals:
     * <ol>
     *   <li>raw clicks per second above {@code autoclicker_max_cps};</li>
     *   <li>interval consistency ({@code autoclicker_consistency}, opt-in): three "robotic"
     *       markers, flagged when at least {@code autoclicker_min_signals} of them hold.
     *       This also catches slow-but-metronomic clickers that never exceed the CPS cap.</li>
     * </ol>
     * Holding the button to mine/swing sends one swing per tick (~20 CPS at near-zero
     * jitter); that "held button" signature is excluded from both signals so normal
     * mining/holding doesn't false-flag.
     */
    private void handleSwing(PacketReceiveEvent event) {
        if (!config.autoClickerDetectionEnabled()) return;
        User user = event.getUser();
        if (user == null || user.getUUID() == null) return;
        UUID id = user.getUUID();

        // Swings sent while breaking a block are mining, not clicking — don't count them.
        if (isMining(id)) return;

        long now = System.currentTimeMillis();
        ConcurrentLinkedDeque<Long> buf = clicks.computeIfAbsent(id, k -> new ConcurrentLinkedDeque<>());
        buf.addLast(now);
        long minTime = now - CONSISTENCY_WINDOW_MS;
        Long head;
        while ((head = buf.peekFirst()) != null && head < minTime) buf.pollFirst();
        while (buf.size() > SAMPLE_CAP) buf.pollFirst();

        Long[] times = buf.toArray(new Long[0]);
        int n = times.length;

        // CPS = swings within the last second
        int cps = 0;
        for (Long t : times) if (t >= now - WINDOW_MS) cps++;

        int maxCps = config.autoClickerMaxCps();

        // Click intervals over the consistency window, shared by the held-button exclusion
        // and the pattern analysis below.
        long[] intervals = new long[Math.max(0, n - 1)];
        for (int i = 1; i < n; i++) intervals[i - 1] = times[i] - times[i - 1];
        double mean = 0, sd = -1;
        if (intervals.length >= MIN_INTERVALS_FOR_SD) {
            double sum = 0;
            for (long iv : intervals) sum += iv;
            mean = sum / intervals.length;
            double v = 0;
            for (long iv : intervals) { double d = iv - mean; v += d * d; }
            sd = Math.sqrt(v / intervals.length);
        }
        // Held left-click sends exactly one swing per tick, so the swings' typical INTERVAL is
        // one tick however much their arrival times jitter. That distinction is the whole
        // point of measuring intervals here: `cps` counts arrivals in a sliding window, so
        // bunched packets push it past any fixed ceiling — which is how held-button swinging
        // came to be flagged at 23 CPS against a 22 cap while its interval never moved off
        // 50ms. A hand clicking at 23 CPS, by contrast, has a ~43.5ms interval and is not
        // excluded. Median and MAD rather than mean and standard deviation: one pause between
        // swings shifts a mean and explodes a deviation, but leaves the median where it is.
        double medianInterval = intervals.length >= MIN_INTERVALS_FOR_SD ? median(intervals) : -1;
        boolean heldButton = isHeldButton(intervals);

        if (config.debugMode()) {
            plugin.getLogger().info(String.format(
                    "[AC-DEBUG] %s cps=%d median=%s mad=%s sd=%s cv=%s outliers=%s held=%b (max %d)",
                    user.getName(), cps,
                    medianInterval < 0 ? "n/a" : String.format("%.1fms", medianInterval),
                    medianInterval < 0 ? "n/a"
                            : String.format("%.1fms", medianAbsoluteDeviation(intervals, medianInterval)),
                    sd < 0 ? "n/a" : String.format("%.1f", sd),
                    sd < 0 || mean <= 0 ? "n/a" : String.format("%.2f", sd / mean),
                    intervals.length == 0 ? "n/a" : String.format("%.2f", outlierRatio(intervals)),
                    heldButton, maxCps));
        }

        // (a) Raw click rate
        if (cps > maxCps && !heldButton) {
            buf.clear(); // reset so it must re-accumulate
            String details = lang.format("alert.autoclicker", cps, maxCps);
            int value = cps;
            Scheduler.runForPlayer(plugin, id, () -> flagOnMain(id, "AUTOCLICKER", details, value));
            return;
        }

        // (b) Interval consistency (opt-in). Humans jitter and pause, so they usually fail
        // ALL three markers; an autoclicker fails at most one.
        if (!config.autoClickerConsistencyEnabled()) return;
        if (heldButton || sd < 0 || mean <= 0) return;
        if (n < config.autoClickerMinSamples() || cps < config.autoClickerMinCps()) return;

        int signals = 0;
        if (sd <= config.autoClickerMaxDeviationMs()) signals++;                    // absolute jitter
        if (sd / mean <= config.autoClickerMaxCv()) signals++;                      // jitter relative to rate
        if (outlierRatio(intervals) <= config.autoClickerMaxOutlierRatio()) signals++; // missing human pauses

        if (signals >= config.autoClickerMinSignals()) {
            buf.clear(); // reset so it must re-accumulate
            String details = lang.format("alert.autoclicker_pattern", sd, cps);
            double value = sd;
            Scheduler.runForPlayer(plugin, id, () -> flagOnMain(id, "AUTOCLICKER", details, value));
        }
    }

    /**
     * Share of intervals longer than 1.5x the median. A human pauses regularly (high share),
     * an autoclicker runs without a break (near zero).
     */
    /**
     * Whether these swing intervals carry the cadence of a held mouse button.
     *
     * <p>A held button is bound to the server tick — the client emits exactly one swing per
     * tick — so the TYPICAL interval sits at {@link #TICK_MS} however much individual arrival
     * times jitter. Deciding on the interval distribution rather than on the CPS count is the
     * whole point: the count is a sliding window over arrival times, so bunched packets push
     * it past any fixed ceiling while the cadence never moved.
     *
     * <p>Package-private and free of any server state so the behaviour can be tested
     * directly — this is where the 23-CPS false positives came from.
     */
    static boolean isHeldButton(long[] intervals) {
        if (intervals.length < MIN_INTERVALS_FOR_SD) return false;
        double m = median(intervals);
        return Math.abs(m - TICK_MS) <= HELD_TICK_TOLERANCE_MS
                && medianAbsoluteDeviation(intervals, m) <= HELD_MAD_MAX_MS;
    }

    /** Median of a sample, or 0 for an empty one. */
    static double median(long[] values) {
        if (values.length == 0) return 0.0;
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 0 ? (sorted[mid - 1] + sorted[mid]) / 2.0 : sorted[mid];
    }

    /**
     * Median absolute deviation around {@code centre} — a spread measure that a single
     * outlier cannot inflate, unlike a standard deviation. One pause in a stream of held
     * swings is exactly such an outlier.
     */
    static double medianAbsoluteDeviation(long[] values, double centre) {
        if (values.length == 0) return 0.0;
        long[] deviations = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            deviations[i] = Math.abs(Math.round(values[i] - centre));
        }
        return median(deviations);
    }

    static double outlierRatio(long[] intervals) {
        if (intervals.length == 0) return 1.0;
        double median = median(intervals);
        if (median <= 0) return 0.0; // no measurable gaps at all — as robotic as it gets
        double limit = median * 1.5;
        int outliers = 0;
        for (long iv : intervals) if (iv > limit) outliers++;
        return (double) outliers / intervals.length;
    }

    /** Rotation packets: BadPackets (impossible pitch) and KillAura rotation GCD. */
    private void handleFlying(PacketReceiveEvent event) {
        boolean bp = config.badPacketsDetectionEnabled();
        boolean rot = config.killAuraRotationDetectionEnabled();
        boolean snap = config.aimSnapDetectionEnabled();
        if (!bp && !rot && !snap) return;

        WrapperPlayClientPlayerFlying wrapper = new WrapperPlayClientPlayerFlying(event);
        if (!wrapper.hasRotationChanged()) return;
        Location loc = wrapper.getLocation();
        if (loc == null) return;
        User user = event.getUser();
        if (user == null || user.getUUID() == null) return;
        UUID id = user.getUUID();
        float pitch = loc.getPitch();
        float yaw = loc.getYaw();

        // BadPackets: impossible rotation values
        if (bp && (!Float.isFinite(pitch) || !Float.isFinite(yaw) || pitch < -90.0f || pitch > 90.0f)) {
            String details = lang.format("alert.badpackets", pitch);
            Scheduler.runForPlayer(plugin, id, () -> flagOnMain(id, "BADPACKETS", details, pitch));
        }

        // KillAura rotation GCD: human mouse input is quantised (yaw deltas share a common
        // divisor); programmatic aim collapses the GCD toward 1. Experimental — off by
        // default; calibrate killaura_rotation_min_gcd via debug_mode (logs measured gcd).
        if (rot && Float.isFinite(yaw)) {
            Float last = lastYaw.put(id, yaw);
            if (last != null) {
                double dyaw = Math.abs(wrapAngle(yaw - last));
                if (dyaw > 0.05 && dyaw < 30.0) { // ignore idle noise and big snaps/spins
                    long scaled = Math.round(dyaw * ROT_EXPANDER);
                    Deque<Long> dq = yawDeltas.computeIfAbsent(id, k -> new ArrayDeque<>());
                    dq.addLast(scaled);
                    int samples = config.killAuraRotationSamples();
                    while (dq.size() > samples) dq.pollFirst();
                    if (dq.size() >= samples) {
                        long g = 0;
                        for (long v : dq) g = gcd(g, v);
                        if (config.debugMode()) {
                            plugin.getLogger().info("[ROT-DEBUG] " + user.getName() + " gcd=" + g);
                        }
                        if (g > 0 && g < config.killAuraRotationMinGcd()) {
                            dq.clear();
                            long flagged = g;
                            String details = lang.format("alert.killaura_rotation", flagged);
                            Scheduler.runForPlayer(plugin, id, () -> flagOnMain(id, "KILLAURA", details, flagged));
                        }
                    }
                }
            }
        }

        // (c) AimSnap: a robotic rotation snaps to the target and back within ~1 tick
        // (look A -> far B -> back near A), which a mouse can't do. Catches rotation-
        // spoofing Scaffold/KillAura even when the per-frame angle to the target is small.
        if (snap && Float.isFinite(yaw) && Float.isFinite(pitch)) {
            Deque<double[]> dirs = recentDirs.computeIfAbsent(id, k -> new ArrayDeque<>());
            dirs.addLast(dirOf(yaw, pitch, System.currentTimeMillis()));
            while (dirs.size() > 3) dirs.pollFirst();
            if (dirs.size() == 3) {
                double[][] d = dirs.toArray(new double[0][]);
                double spikeIn = angleBetween(d[0], d[1]);
                double spikeOut = angleBetween(d[1], d[2]);
                double net = angleBetween(d[0], d[2]);
                if (config.debugMode()) {
                    plugin.getLogger().info(String.format("[SNAP-DEBUG] %s in=%.0f out=%.0f net=%.0f",
                            user.getName(), spikeIn, spikeOut, net));
                }
                // The three samples must be consecutive in TIME, not merely in arrival order.
                // Turning to look at something and back is an entirely ordinary thing to do
                // over a second — it is only beyond a mouse inside two ticks. Without this
                // bound, a packet gap (a stalled connection catching up, which this server
                // sees regularly) hands the check three rotations seconds apart and they read
                // as one impossible flick.
                boolean withinTwoTicks = d[2][3] - d[0][3] <= AIMSNAP_MAX_SPAN_MS;
                if (withinTwoTicks && spikeIn > config.aimSnapMinAngle()
                        && spikeOut > config.aimSnapMinAngle()
                        && net < config.aimSnapReturnAngle()) {
                    long now = System.currentTimeMillis();
                    Deque<Long> st = snapTimes.computeIfAbsent(id, k -> new ArrayDeque<>());
                    st.addLast(now);
                    while (!st.isEmpty() && st.peekFirst() < now - config.aimSnapWindowMs()) st.pollFirst();
                    if (st.size() >= config.aimSnapThreshold()) {
                        st.clear();
                        double angle = spikeIn;
                        String details = lang.format("alert.aimsnap", angle);
                        Scheduler.runForPlayer(plugin, id, () -> flagOnMain(id, "AIMSNAP", details, angle));
                    }
                }
            }
        }
    }

    /**
     * Look direction as a unit vector, with the arrival time appended as a fourth element.
     * {@link #angleBetween} reads only the first three, so the timestamp rides along without
     * affecting the geometry.
     */
    private static double[] dirOf(float yaw, float pitch, long timeMs) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        double cp = Math.cos(p);
        return new double[]{ -cp * Math.sin(y), -Math.sin(p), cp * Math.cos(y), timeMs };
    }

    private static double angleBetween(double[] a, double[] b) {
        double dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        dot = Math.max(-1.0, Math.min(1.0, dot));
        return Math.toDegrees(Math.acos(dot));
    }

    private static double wrapAngle(double a) {
        a %= 360.0;
        if (a >= 180.0) a -= 360.0;
        if (a < -180.0) a += 360.0;
        return a;
    }

    private static long gcd(long a, long b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while (b != 0) {
            long t = b;
            b = a % b;
            a = t;
        }
        return a;
    }

    /**
     * Generic flag path for the simple packet checks (crasher/flood). Callable from the
     * Netty thread: the DB log goes through the thread-safe queue immediately with the
     * connection's name, so the record survives even when a crash/flood attempt drops the
     * connection before the next tick. Alert + VL need Bukkit API, so they hop to the main
     * thread and are best-effort (skipped if the player already disconnected).
     */
    private void flagSimple(UUID id, String name, String type, String details, double value) {
        if (database != null) {
            database.logAsync("anticheat_" + type.toLowerCase(), value, name + ": " + details);
        }
        Runnable main = () -> {
            Player player = Bukkit.getPlayer(id);
            if (player == null || Exemptions.isExempt(player, config, luckPerms, geyser)) return;
            if (alertManager != null) {
                alertManager.addAlert(player, type, details, value, player.getLocation());
            } else if (config.debugMode()) {
                plugin.getLogger().warning("[AntiCheat] " + player.getName() + " " + type + " - " + details);
            }
            if (violationManager != null) {
                violationManager.flag(player, type);
            }
        };
        Scheduler.runForPlayer(plugin, id, main);
    }

    /**
     * Flag path for the checks that need the Player object (alert location, exemption
     * state). Runs on the player's region thread; best-effort — skipped if the player
     * already disconnected.
     */
    private void flagOnMain(UUID id, String type, String details, double value) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) return;
        if (Exemptions.isExempt(player, config, luckPerms, geyser)) {
            if (config.debugMode()) {
                plugin.getLogger().info("[AC-DEBUG] " + type + " flag skipped: " + player.getName() + " is exempt");
            }
            return;
        }
        if (config.debugMode()) {
            plugin.getLogger().info("[AC-DEBUG] FLAG " + player.getName() + " " + type + " - " + details);
        }
        if (database != null) {
            database.logAsync("anticheat_" + type.toLowerCase(), value,
                    player.getName() + ": " + details + " @ "
                            + dev.boondock.bsanticheat.util.CheckMath.formatLocation(player.getLocation()));
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


    public void cleanup(UUID playerId) {
        clicks.remove(playerId);
        timerGraceUntil.remove(playerId);
        graceUntil.remove(playerId);
        timerState.remove(playerId);
        packetCounts.remove(playerId);
        miningUntil.remove(playerId);
        lastYaw.remove(playerId);
        yawDeltas.remove(playerId);
        recentDirs.remove(playerId);
        snapTimes.remove(playerId);
    }
}
