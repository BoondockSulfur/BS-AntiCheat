package dev.boondock.bsanticheat.anticheat;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.util.Scheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <p>Pings are sent on the main thread; pongs arrive on the Netty thread ({@link #onPong}).
 * All shared state is concurrent.
 */
public class TransactionManager {

    private final Plugin plugin;
    private final PluginConfig config;
    private final DatabaseManager database;

    // Per player: pending ping id -> send time (nanos). Bounded per player.
    private final Map<UUID, Map<Integer, Long>> pending = new ConcurrentHashMap<>();
    // Per player: most recent measured round-trip latency in nanos.
    private final Map<UUID, Long> lastRttNanos = new ConcurrentHashMap<>();
    // Debug-only: whether we already logged the one-shot "transaction confirmed" marker.
    private final Map<UUID, Boolean> markerLogged = new ConcurrentHashMap<>();

    private final AtomicInteger idGen = new AtomicInteger(1);
    private ScheduledTask pingTask;

    private static final int MAX_PENDING_PER_PLAYER = 60;

    public TransactionManager(Plugin plugin, PluginConfig config, DatabaseManager database) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
    }

    /** Begin sending a transaction ping to every online player each tick. */
    public void start() {
        pingTask = Scheduler.runGlobalTimer(plugin, this::tick, 20L, 1L);
    }

    public void stop() {
        if (pingTask != null) pingTask.cancel();
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            sendPing(player);
        }
    }

    private void sendPing(Player player) {
        UUID uuid = player.getUniqueId();
        int id = idGen.getAndIncrement() & 0x7FFFFFFF;
        Map<Integer, Long> map = pending.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        map.put(id, System.nanoTime());
        // Bound memory: drop the oldest ids if the client stopped answering.
        if (map.size() > MAX_PENDING_PER_PLAYER) {
            map.keySet().stream().sorted().limit(map.size() - MAX_PENDING_PER_PLAYER)
                    .toList().forEach(map::remove);
        }
        try {
            User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            if (user != null) user.sendPacket(new WrapperPlayServerPing(id));
        } catch (Throwable ignored) {
            // PacketEvents not ready for this connection yet — skip this tick.
        }
    }

    /**
     * Handle a client Pong (Netty thread). Matches the id, records the round-trip latency,
     * and in debug mode logs a one-shot marker so the test harness can prove the ping→pong
     * round-trip works end to end.
     */
    public void onPong(UUID uuid, int pingId, String name) {
        Map<Integer, Long> map = pending.get(uuid);
        if (map == null) return;
        Long sent = map.remove(pingId);
        if (sent == null) return;
        long rtt = System.nanoTime() - sent;
        lastRttNanos.put(uuid, rtt);

        if (config.debugMode() && markerLogged.putIfAbsent(uuid, Boolean.TRUE) == null && database != null) {
            double ms = rtt / 1.0e6;
            database.logAsync("transaction", ms, name + ": transaction confirmed rtt=" + String.format("%.1fms", ms));
        }
    }

    /**
     * Effective one-way latency in milliseconds for lag compensation: half the measured
     * transaction round-trip. Returns -1 when no transaction has completed yet, so callers
     * can fall back to {@link Player#getPing()}.
     */
    public double oneWayLatencyMs(UUID uuid) {
        Long rtt = lastRttNanos.get(uuid);
        if (rtt == null) return -1;
        return (rtt / 1.0e6) / 2.0;
    }

    /**
     * Measured transaction round-trip in milliseconds (getPing()-equivalent), or -1 when no
     * transaction has completed yet. More precise than {@link Player#getPing()}, which is
     * derived from the coarse 15s keep-alive.
     */
    public double roundTripMs(UUID uuid) {
        Long rtt = lastRttNanos.get(uuid);
        return rtt == null ? -1 : rtt / 1.0e6;
    }

    public void cleanup(UUID uuid) {
        pending.remove(uuid);
        lastRttNanos.remove(uuid);
        markerLogged.remove(uuid);
    }
}
