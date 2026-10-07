package dev.boondock.bsanticheat.anticheat;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.util.Constants;
import dev.boondock.bsanticheat.util.Scheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Transaction-based latency system (Masterplan phase 2.1).
 *
 * <p>Each tick a play {@code Ping(id)} is sent to every online player and the send time is
 * recorded. Because a client processes packets strictly in order, the matching {@code Pong(id)}
 * proves the client has processed everything sent before that ping — and its round-trip time
 * is the true transaction latency, independent of the coarse {@link Player#getPing()}.
 *
 * <p>This is the foundation for precise, false-positive-safe combat checks (velocity,
 * lag-compensated reach): a check can send a ping, act, and only judge the outcome once the
 * pong confirms the client has acknowledged the action.
 *
 * <p><b>Ping spoofing.</b> The round trip is measured from replies the client chooses when to
 * send, and every latency allowance in the plugin grows with it — so holding pongs back buys
 * slack. Three observations expose that (only with {@code pingspoof_detection} on):
 * <ul>
 *   <li><i>order</i>: a pong arrives while an older ping is still unanswered. The client
 *       answers pings in the order it receives them over one TCP stream, so the older one was
 *       dropped or is being held.</li>
 *   <li><i>stall</i>: a keep-alive written to the wire AFTER a ping has been answered while
 *       that ping has not. The client must have received the ping first, so its pong is being
 *       withheld. Download congestion cannot cause this: it delays the keep-alive just as
 *       much.</li>
 *   <li><i>divergence</i>: transaction round trips well above the keep-alive round trip for
 *       several keep-alives in a row. Both travel the same connection; the client answers
 *       keep-alives on its network thread and pings on its main thread, so pings may lag by
 *       one client frame, not by hundreds of milliseconds.</li>
 * </ul>
 * Once the signals reach the alert threshold, {@link #roundTripMs} stops reporting the
 * inflated value for a while. Clients below 1.17 (via ViaVersion) and Bedrock players are
 * never judged: their ping replies are produced by a translating proxy.
 *
 * <p>Pings are sent on the main thread; pongs arrive on the Netty thread ({@link #onPong}).
 * All shared state is concurrent; the per-player spoof state is guarded by its own lock.
 */
public class TransactionManager {

    /** Receives ping-spoof verdicts. Called from the Netty thread. */
    public interface SpoofSink {
        void onSpoof(UUID player, String name, String kind, double value);
    }

    private final Plugin plugin;
    private final PluginConfig config;
    private final DatabaseManager database;

    // Per player: pending pings in send order. Bounded per player.
    private final Map<UUID, PendingPings> pending = new ConcurrentHashMap<>();
    // Per player: most recent measured round-trip latency in nanos.
    private final Map<UUID, Long> lastRttNanos = new ConcurrentHashMap<>();
    // Debug-only: whether we already logged the one-shot "transaction confirmed" marker.
    private final Map<UUID, Boolean> markerLogged = new ConcurrentHashMap<>();
    // Ping-spoof bookkeeping per player.
    private final Map<UUID, SpoofState> spoof = new ConcurrentHashMap<>();

    private final AtomicInteger idGen = new AtomicInteger(1);
    private ScheduledTask pingTask;
    private volatile SpoofSink spoofSink;
    // Players whose ping replies say nothing about their client (legacy protocol via a proxy,
    // Bedrock via Geyser). Null: nobody is excluded.
    private volatile Predicate<UUID> spoofExempt;

    private static final int MAX_PENDING_PER_PLAYER = 60;
    // Keep-alives are sent seconds apart (vanilla: every 15s), so pings must stay pending at
    // least that long for the stall comparison to have anything to compare with.
    private static final long PENDING_HORIZON_MS = 20_000L;
    private static final int MAX_PENDING_HARD_CAP = 800;
    // Without spoof detection a pending ping only has to live long enough to measure a
    // round trip, so a much shorter horizon suffices.
    private static final long PENDING_HORIZON_NO_SPOOF_MS = 5_000L;
    private static final int MIN_PENDING_NO_SPOOF = 30;
    // Signals are counted inside this window before they become an alert.
    private static final long SPOOF_WINDOW_MS = 60_000L;
    // How long the round trip stays sanitised after the last signal.
    private static final long SUSPECT_MS = 60_000L;
    // A keep-alive round trip older than this is no reference for the current connection:
    // it is neither reported nor used to lower the transaction round trip.
    static final long KEEPALIVE_MAX_AGE_MS = 10_000L;
    // Smoothing of the transaction round trip used by the stall rule (weight of a new sample).
    private static final double RTT_SMOOTHING = 0.125;
    // Arrival times of recent client tick packets kept for the stall rule; must be at least
    // STALL_MIN_CLIENT_TICKS.
    private static final int TICK_HISTORY = 64;
    // Consecutive keep-alives with diverging round trips before it counts as a signal.
    private static final int DIVERGENCE_STREAK = 3;
    // Pongs a connection must have answered correctly before it is judged at all: proves
    // the ping channel works for this client (proxies translating pings for old protocol
    // versions can break it entirely).
    static final int MIN_MATCHED_PONGS = 20;
    private static final int MAX_TRACKED_KEEPALIVES = 16;
    // Client tick and movement packets the client must have sent after RECEIVING a ping
    // before the ping's absence counts. The client answers keep-alives on its network thread
    // but pings on its main thread, so a keep-alive reply may overtake a pong while the main
    // thread is busy (loading, a long frame). Tick packets come from that main thread: once
    // it has sent this many after the ping reached it, it has also worked through its packet
    // queue. Only packets arriving later than the ping's write time plus the smoothed round
    // trip count — earlier ones may have been in flight before the ping arrived. Counts both
    // CLIENT_TICK_END and movement packets, so roughly two seconds on a modern client.
    static final int STALL_MIN_CLIENT_TICKS = 40;

    /** One ping as sent: per-player send order, send time, and wire position once written. */
    private static final class PendingPing {
        final long seq;
        final long sentNanos;
        volatile long wireSeq = -1L;
        volatile long wireNanos;

        PendingPing(long seq, long sentNanos) {
            this.seq = seq;
            this.sentNanos = sentNanos;
        }
    }

    /**
     * A player's pending pings in send order (insertion order equals sequence order, since
     * the sequence number is assigned under the same lock). Eviction of the oldest and the
     * "older than" scan on a pong both work from the head, without sorting.
     */
    private static final class PendingPings {
        private final LinkedHashMap<Integer, PendingPing> byId = new LinkedHashMap<>();
        private long nextSeq;

        synchronized PendingPing add(int id, long sentNanos, int cap) {
            PendingPing p = new PendingPing(++nextSeq, sentNanos);
            byId.put(id, p);
            Iterator<PendingPing> it = byId.values().iterator();
            while (byId.size() > cap && it.hasNext()) {
                it.next();
                it.remove();
            }
            return p;
        }

        synchronized PendingPing get(int id) {
            return byId.get(id);
        }

        synchronized PendingPing remove(int id) {
            return byId.remove(id);
        }

        /** Remove and count every ping sent before {@code seq}. */
        synchronized int removeOlderThan(long seq) {
            int n = 0;
            Iterator<PendingPing> it = byId.values().iterator();
            while (it.hasNext() && it.next().seq < seq) {
                it.remove();
                n++;
            }
            return n;
        }

        /** Remove and count pings written before {@code wireSeq} that the client must have processed. */
        synchronized int removeWithheld(long kaWire, java.util.function.LongPredicate processed) {
            int n = 0;
            Iterator<PendingPing> it = byId.values().iterator();
            while (it.hasNext()) {
                PendingPing p = it.next();
                if (p.wireSeq >= 0 && p.wireSeq < kaWire && processed.test(p.wireNanos)) {
                    it.remove();
                    n++;
                }
            }
            return n;
        }

        synchronized int size() {
            return byId.size();
        }
    }

    /** Per-player spoof state; all fields guarded by {@code this}. */
    private static final class SpoofState {
        long wireSeq;               // per-player outbound order of pings and keep-alives
        final long[] tickNanos = new long[TICK_HISTORY]; // ring of client tick arrival times
        int tickCount;              // ticks recorded (saturates at TICK_HISTORY)
        int tickHead;               // next ring slot to write
        long smoothedRttNanos = -1L; // smoothed transaction round trip
        int matched;                // pongs matched to a pending ping
        int exempt;                 // 0 unknown, 1 exempt (legacy/Bedrock), 2 judged
        final Map<Long, long[]> keepAlives = new LinkedHashMap<>(); // id -> {sentNanos, wireSeq}
        long keepAliveRttNanos = -1L;
        long keepAliveAtNanos;
        int divergenceStreak;
        long suspectedUntilMs;
        final Deque<Long> signals = new ArrayDeque<>();
    }

    public TransactionManager(Plugin plugin, PluginConfig config, DatabaseManager database) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
    }

    public void setSpoofSink(SpoofSink sink) {
        this.spoofSink = sink;
    }

    /** Who is never judged (legacy protocol via a proxy, Bedrock). Evaluated once per player. */
    public void setSpoofExemption(Predicate<UUID> exempt) {
        this.spoofExempt = exempt;
    }

    /**
     * Begin sending transaction pings to every online player. The interval is configurable
     * because this is pure per-player packet overhead: every tick means 20 extra packets
     * per second per player, which on a full server is thousands for a measurement that
     * stays accurate at a coarser rate.
     */
    public void start() {
        long period = Math.max(1L, config.transactionIntervalTicks());
        pingTask = Scheduler.runGlobalTimer(plugin, this::tick, 20L, period);
    }

    public void stop() {
        if (pingTask != null) {
            pingTask.cancel();
            pingTask = null;
        }
    }

    /**
     * Re-read the ping interval and reschedule. The period is fixed when the task is created,
     * so a changed {@code transaction_interval_ticks} only takes effect through this.
     */
    public void restart() {
        stop();
        start();
    }

    private void tick() {
        Set<UUID> online = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
            sendPing(player);
        }
        retainOnline(online);
    }

    /**
     * Drop state for players that are no longer online. On Folia this timer runs on the
     * global region thread while the quit cleanup runs on the player's region thread, so a
     * ping sent for a player who quit mid-loop can re-create their entry after
     * {@link #cleanup} already removed it. Sweeping against the online snapshot on every run
     * bounds such a leftover to a single interval instead of keeping it forever.
     */
    void retainOnline(Set<UUID> online) {
        pending.keySet().retainAll(online);
        lastRttNanos.keySet().retainAll(online);
        markerLogged.keySet().retainAll(online);
        spoof.keySet().retainAll(online);
    }

    /**
     * How many pings may be pending per player: with spoof detection at least the keep-alive
     * horizon's worth, without it only enough to measure a round trip.
     */
    int pendingCap() {
        long periodMs = Math.max(1L, config.transactionIntervalTicks()) * 50L;
        if (!config.pingSpoofDetectionEnabled()) {
            long forHorizon = PENDING_HORIZON_NO_SPOOF_MS / periodMs + 10L;
            return (int) Math.min(MAX_PENDING_HARD_CAP, Math.max(MIN_PENDING_NO_SPOOF, forHorizon));
        }
        long forHorizon = PENDING_HORIZON_MS / periodMs + 10L;
        return (int) Math.min(MAX_PENDING_HARD_CAP, Math.max(MAX_PENDING_PER_PLAYER, forHorizon));
    }

    void sendPing(Player player) {
        // A player whose quit has already been processed must not get a fresh entry.
        if (!player.isOnline()) return;
        UUID uuid = player.getUniqueId();
        int id = recordPing(uuid, System.nanoTime());
        boolean sent = false;
        try {
            User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            if (user != null) {
                user.sendPacket(new WrapperPlayServerPing(id));
                sent = true;
            }
        } catch (Throwable ignored) {
            // PacketEvents not ready for this connection yet — skip this tick.
        }
        // A ping that never left must not wait for an answer: the order check would read the
        // next pong as having jumped over it.
        if (!sent) {
            PendingPings map = pending.get(uuid);
            if (map != null) map.remove(id);
        }
    }

    /** Register a ping about to be sent and return its id. Package-private for tests. */
    int recordPing(UUID uuid, long nowNanos) {
        int id = idGen.getAndIncrement() & 0x7FFFFFFF;
        spoof.computeIfAbsent(uuid, k -> new SpoofState());
        // Bounded: the oldest pings are dropped if the client stopped answering.
        pending.computeIfAbsent(uuid, k -> new PendingPings()).add(id, nowNanos, pendingCap());
        return id;
    }

    /**
     * A ping of ours reached the wire (PacketEvents send listener). Records its position in
     * the outbound stream relative to keep-alives.
     */
    public void onPingWritten(UUID uuid, int pingId) {
        onPingWritten(uuid, pingId, System.nanoTime());
    }

    /** {@link #onPingWritten(UUID, int)} with an explicit clock, for tests. */
    void onPingWritten(UUID uuid, int pingId, long nowNanos) {
        PendingPings map = pending.get(uuid);
        if (map == null) return;
        PendingPing p = map.get(pingId);
        if (p == null) return;
        SpoofState st = spoof.computeIfAbsent(uuid, k -> new SpoofState());
        synchronized (st) {
            p.wireNanos = nowNanos;
            p.wireSeq = ++st.wireSeq;
        }
    }

    /** A client tick-end or movement packet (Netty thread). Only recorded while detection is on. */
    public void onClientTick(UUID uuid) {
        onClientTick(uuid, System.nanoTime());
    }

    /** {@link #onClientTick(UUID)} with an explicit clock, for tests. */
    void onClientTick(UUID uuid, long nowNanos) {
        if (!config.pingSpoofDetectionEnabled()) return;
        SpoofState st = spoof.get(uuid);
        if (st == null) return;
        synchronized (st) {
            st.tickNanos[st.tickHead] = nowNanos;
            st.tickHead = (st.tickHead + 1) % TICK_HISTORY;
            if (st.tickCount < TICK_HISTORY) st.tickCount++;
        }
    }

    /** Client tick packets that arrived strictly after {@code sinceNanos}; caller holds the lock. */
    private static int ticksSince(SpoofState st, long sinceNanos) {
        int n = 0;
        for (int i = 1; i <= st.tickCount; i++) {
            long t = st.tickNanos[Math.floorMod(st.tickHead - i, TICK_HISTORY)];
            if (t - sinceNanos <= 0) break; // the ring is in arrival order
            n++;
        }
        return n;
    }

    /** The server wrote a keep-alive to this player. */
    public void onKeepAliveWritten(UUID uuid, long keepAliveId, long nowNanos) {
        if (!config.pingSpoofDetectionEnabled()) return;
        SpoofState st = spoof.computeIfAbsent(uuid, k -> new SpoofState());
        synchronized (st) {
            st.keepAlives.put(keepAliveId, new long[]{nowNanos, ++st.wireSeq});
            if (st.keepAlives.size() > MAX_TRACKED_KEEPALIVES) {
                Iterator<Long> it = st.keepAlives.keySet().iterator();
                it.next();
                it.remove();
            }
        }
    }

    /**
     * The client answered a keep-alive. Measures its round trip, compares it with the
     * transaction round trip, and checks for pings sent before that keep-alive that are still
     * unanswered although the client's main thread has kept ticking since.
     */
    public void onKeepAliveReply(UUID uuid, long keepAliveId, long nowNanos, String name) {
        if (!config.pingSpoofDetectionEnabled()) return;
        SpoofState st = spoof.get(uuid);
        if (st == null) return;
        long kaWire;
        long kaRtt;
        int matched;
        boolean diverging;
        synchronized (st) {
            long[] sent = st.keepAlives.remove(keepAliveId);
            if (sent == null) return;
            kaWire = sent[1];
            kaRtt = nowNanos - sent[0];
            st.keepAliveRttNanos = kaRtt;
            st.keepAliveAtNanos = nowNanos;
            matched = st.matched;
            Long tx = lastRttNanos.get(uuid);
            boolean over = tx != null
                    && (tx - kaRtt) / 1_000_000L > config.pingSpoofDivergenceMs();
            st.divergenceStreak = over ? st.divergenceStreak + 1 : 0;
            diverging = st.divergenceStreak >= DIVERGENCE_STREAK;
            if (diverging) st.divergenceStreak = 0;
        }
        if (matched < MIN_MATCHED_PONGS) return;

        // Pings that were on the wire before this keep-alive and are still unanswered although
        // the client's main thread has ticked enough since it received them (reported once).
        PendingPings map = pending.get(uuid);
        int withheld = 0;
        if (map != null) {
            long rttEstimate = rttEstimateNanos(st, kaRtt);
            withheld = map.removeWithheld(kaWire, wireNanos -> {
                synchronized (st) {
                    return ticksSince(st, wireNanos + rttEstimate) >= STALL_MIN_CLIENT_TICKS;
                }
            });
        }
        if (withheld > 0) signal(uuid, name, "stall", withheld);
        if (diverging) {
            Long tx = lastRttNanos.get(uuid);
            signal(uuid, name, "divergence", tx == null ? 0 : (tx - kaRtt) / 1.0e6);
        }
    }

    /**
     * Handle a client Pong (Netty thread). Matches the id, records the round-trip latency,
     * and in debug mode logs a one-shot marker so the test harness can prove the ping→pong
     * round-trip works end to end.
     */
    public void onPong(UUID uuid, int pingId, String name) {
        onPong(uuid, pingId, name, System.nanoTime());
    }

    /** {@link #onPong(UUID, int, String)} with an explicit clock, for tests. */
    void onPong(UUID uuid, int pingId, String name, long nowNanos) {
        PendingPings map = pending.get(uuid);
        if (map == null) return;
        PendingPing p = map.remove(pingId);
        if (p == null) {
            // Not an id we are waiting for: another plugin's ping, one already dropped as
            // skipped or evicted, or a forged id. Other plugins use this packet too, so an
            // unknown id is no evidence on its own.
            if (config.debugMode() && config.pingSpoofDetectionEnabled()) {
                plugin.getLogger().info("[PINGSPOOF-DEBUG] " + name + " unknown pong id " + pingId);
            }
            return;
        }
        long rtt = nowNanos - p.sentNanos;
        lastRttNanos.put(uuid, rtt);

        if (config.pingSpoofDetectionEnabled()) {
            SpoofState st = spoof.get(uuid);
            int matched = 0;
            if (st != null) {
                synchronized (st) {
                    matched = ++st.matched;
                    st.smoothedRttNanos = st.smoothedRttNanos < 0 ? rtt
                            : Math.round(st.smoothedRttNanos + RTT_SMOOTHING * (rtt - st.smoothedRttNanos));
                }
            }
            // Older pings still waiting were jumped over: answered out of order or never.
            int skipped = map.removeOlderThan(p.seq);
            if (skipped > 0 && matched >= MIN_MATCHED_PONGS) signal(uuid, name, "order", skipped);
        }

        if (config.debugMode() && markerLogged.putIfAbsent(uuid, Boolean.TRUE) == null && database != null) {
            double ms = rtt / 1.0e6;
            database.logAsync(uuid, "transaction", ms, name + ": transaction confirmed rtt=" + String.format(java.util.Locale.ROOT, "%.1fms", ms));
        }
    }

    /** Round trip for the stall rule: smoothed transaction RTT, else this keep-alive's. */
    private static long rttEstimateNanos(SpoofState st, long kaRttNanos) {
        synchronized (st) {
            return st.smoothedRttNanos >= 0 ? st.smoothedRttNanos : Math.max(0L, kaRttNanos);
        }
    }

    /**
     * Count one spoof signal; raise a verdict once enough accumulate inside the window. The
     * round trip is distrusted from that same moment on — a single signal can come from a
     * glitch and must not shrink every latency allowance of an honest player.
     */
    private void signal(UUID uuid, String name, String kind, double value) {
        SpoofState st = spoof.computeIfAbsent(uuid, k -> new SpoofState());
        long now = System.currentTimeMillis();
        boolean flag;
        synchronized (st) {
            st.signals.addLast(now);
            while (!st.signals.isEmpty() && st.signals.peekFirst() < now - SPOOF_WINDOW_MS) st.signals.pollFirst();
            flag = st.signals.size() >= config.pingSpoofViolations();
            if (flag) {
                st.signals.clear();
                st.suspectedUntilMs = now + SUSPECT_MS;
            }
        }
        if (config.debugMode()) {
            plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                    "[PINGSPOOF-DEBUG] %s signal=%s value=%.1f", name, kind, value));
        }
        SpoofSink sink = spoofSink;
        if (flag && sink != null) sink.onSpoof(uuid, name, kind, value);
    }

    /**
     * Whether the round trip of this player is currently distrusted. Never for players whose
     * ping replies are produced by a translating proxy (legacy protocol, Bedrock).
     */
    public boolean isSpoofSuspected(UUID uuid) {
        SpoofState st = spoof.get(uuid);
        if (st == null) return false;
        synchronized (st) {
            if (System.currentTimeMillis() >= st.suspectedUntilMs) return false;
        }
        return !isExempt(uuid, st);
    }

    /** Exemption lookup, evaluated once per player and cached in its state. */
    private boolean isExempt(UUID uuid, SpoofState st) {
        synchronized (st) {
            if (st.exempt != 0) return st.exempt == 1;
        }
        Predicate<UUID> probe = spoofExempt;
        if (probe == null) return false;
        boolean exempt;
        try {
            exempt = probe.test(uuid);
        } catch (RuntimeException e) {
            return false; // player not resolvable right now: judge normally, ask again later
        }
        synchronized (st) {
            st.exempt = exempt ? 1 : 2;
        }
        return exempt;
    }

    /** Last keep-alive round trip in ms if fresh (see KEEPALIVE_MAX_AGE_MS), else -1. */
    public double keepAliveRoundTripMs(UUID uuid) {
        SpoofState st = spoof.get(uuid);
        if (st == null) return -1;
        synchronized (st) {
            if (st.keepAliveRttNanos < 0) return -1;
            if ((System.nanoTime() - st.keepAliveAtNanos) / 1_000_000L > KEEPALIVE_MAX_AGE_MS) return -1;
            return st.keepAliveRttNanos / 1.0e6;
        }
    }

    /**
     * Measured transaction round-trip in milliseconds (getPing()-equivalent), or -1 when no
     * transaction has completed yet. More precise than {@link Player#getPing()}, which is
     * derived from the coarse 15s keep-alive.
     *
     * <p>While spoofing is suspected the value is not trusted: it is replaced by the lower
     * keep-alive round trip when a fresh one exists, otherwise capped. Every latency allowance in
     * the plugin reads this, so an inflated value would widen all of them at once.
     */
    public double roundTripMs(UUID uuid) {
        Long rtt = lastRttNanos.get(uuid);
        if (rtt == null) return -1;
        double tx = rtt / 1.0e6;
        if (!isSpoofSuspected(uuid)) return tx;
        return sanitisedRtt(tx, keepAliveRoundTripMs(uuid));
    }

    /** The round trip to use while spoofing is suspected. Package-private for tests. */
    static double sanitisedRtt(double txMs, double keepAliveMs) {
        if (keepAliveMs >= 0) return Math.min(txMs, keepAliveMs);
        return Math.min(txMs, Constants.PINGSPOOF_RTT_CAP_MS);
    }

    /** Whether any per-player state is held for this player (for tests). */
    boolean isTracked(UUID uuid) {
        return pending.containsKey(uuid) || lastRttNanos.containsKey(uuid);
    }

    /** Pings currently awaiting an answer (for tests). */
    int pendingCount(UUID uuid) {
        PendingPings map = pending.get(uuid);
        return map == null ? 0 : map.size();
    }

    public void cleanup(UUID uuid) {
        pending.remove(uuid);
        lastRttNanos.remove(uuid);
        markerLogged.remove(uuid);
        spoof.remove(uuid);
    }
}
