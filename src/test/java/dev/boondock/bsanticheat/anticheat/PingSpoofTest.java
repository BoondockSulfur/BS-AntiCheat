package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.util.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ping-spoof analysis on the transaction channel.
 */
class PingSpoofTest extends ScenarioBase {

    private TransactionManager tx;
    private final List<String> verdicts = new ArrayList<>();
    private UUID id;

    private void build(boolean enabled, int violations) {
        plugin.getConfig().set("anticheat.pingspoof_detection", enabled);
        plugin.getConfig().set("anticheat.thresholds.pingspoof_violations", violations);
        config = new PluginConfig(plugin);
        tx = new TransactionManager(plugin, config, null);
        tx.setSpoofSink((player, name, kind, value) -> verdicts.add(kind));
    }

    @BeforeEach
    void setUpId() {
        id = UUID.randomUUID();
        verdicts.clear();
    }

    /** Answer enough pings in order that the channel counts as working. */
    private void provenChannel() {
        for (int i = 0; i < TransactionManager.MIN_MATCHED_PONGS; i++) {
            tx.onPong(id, tx.recordPing(id, System.nanoTime()), "p");
        }
    }

    @Test
    @DisplayName("A pong that jumps over an older ping is an order signal")
    void skippedPingIsCaught() {
        build(true, 1);
        provenChannel();
        int older = tx.recordPing(id, System.nanoTime());
        int newer = tx.recordPing(id, System.nanoTime());
        tx.onPong(id, newer, "p");
        assertEquals(List.of("order"), verdicts);
        assertEquals(0, tx.pendingCount(id), "the skipped ping is reported once and dropped");
        tx.onPong(id, older, "p"); // arriving late now: unknown, not a second verdict
        assertEquals(1, verdicts.size());
    }

    @Test
    @DisplayName("In-order answers raise nothing")
    void orderedIsQuiet() {
        build(true, 1);
        provenChannel();
        for (int i = 0; i < 50; i++) tx.onPong(id, tx.recordPing(id, System.nanoTime()), "p");
        assertTrue(verdicts.isEmpty());
    }

    @Test
    @DisplayName("A connection whose ping channel never worked is not judged")
    void unprovenChannelIsNotJudged() {
        // Proxies translating pings for old clients can break the ids entirely.
        build(true, 1);
        tx.recordPing(id, System.nanoTime());
        tx.onPong(id, tx.recordPing(id, System.nanoTime()), "p");
        assertTrue(verdicts.isEmpty());
    }

    @Test
    @DisplayName("One signal alone does not alert at the default threshold")
    void thresholdApplies() {
        build(true, Constants.PINGSPOOF_VIOLATIONS);
        provenChannel();
        for (int i = 0; i < Constants.PINGSPOOF_VIOLATIONS - 1; i++) {
            tx.recordPing(id, System.nanoTime());
            tx.onPong(id, tx.recordPing(id, System.nanoTime()), "p");
        }
        assertTrue(verdicts.isEmpty());
        tx.recordPing(id, System.nanoTime());
        tx.onPong(id, tx.recordPing(id, System.nanoTime()), "p");
        assertEquals(1, verdicts.size());
    }

    @Test
    @DisplayName("A ping withheld while later keep-alives are answered and the client ticks is a stall")
    void withheldPingIsCaught() {
        build(true, 1);
        provenChannel();
        long now = System.nanoTime();
        int ping = tx.recordPing(id, now);
        tx.onPingWritten(id, ping, now);
        tx.onKeepAliveWritten(id, 77L, now + 1_000_000L);
        for (int i = 0; i < TransactionManager.STALL_MIN_CLIENT_TICKS; i++) tx.onClientTick(id, now + 10_000_000L + i);
        tx.onKeepAliveReply(id, 77L, now + 60_000_000L, "p");
        assertEquals(List.of("stall"), verdicts);
    }

    /** Answer enough pings in order, each after {@code rttMs}, so the smoothed round trip is that. */
    private void provenChannelWithRtt(long start, long rttMs) {
        long t = start;
        for (int i = 0; i < TransactionManager.MIN_MATCHED_PONGS; i++) {
            tx.onPong(id, tx.recordPing(id, t), "p", t + rttMs * 1_000_000L);
            t += 50_000_000L;
        }
    }

    @Test
    @DisplayName("Tick packets that may have been in flight before the ping reached the client do not count")
    void ticksBeforePingArrivalAreNoStall() {
        build(true, 1);
        long t0 = System.nanoTime() - 10_000_000_000L;
        provenChannelWithRtt(t0, 500);
        long wire = t0 + 2_000_000_000L;
        int ping = tx.recordPing(id, wire);
        tx.onPingWritten(id, ping, wire);
        tx.onKeepAliveWritten(id, 77L, wire + 1_000_000L);
        // All arrive within one round trip of the write: sent before the client had the ping.
        for (int i = 0; i < TransactionManager.STALL_MIN_CLIENT_TICKS; i++) {
            tx.onClientTick(id, wire + 100_000_000L + i * 5_000_000L);
        }
        tx.onKeepAliveReply(id, 77L, wire + 480_000_000L, "p");
        assertTrue(verdicts.isEmpty());
    }

    @Test
    @DisplayName("Tick packets sent after the ping reached the client count towards a stall")
    void ticksAfterPingArrivalAreAStall() {
        build(true, 1);
        long t0 = System.nanoTime() - 10_000_000_000L;
        provenChannelWithRtt(t0, 500);
        long wire = t0 + 2_000_000_000L;
        int ping = tx.recordPing(id, wire);
        tx.onPingWritten(id, ping, wire);
        tx.onKeepAliveWritten(id, 77L, wire + 1_000_000L);
        for (int i = 0; i < TransactionManager.STALL_MIN_CLIENT_TICKS; i++) {
            tx.onClientTick(id, wire + 600_000_000L + i * 50_000_000L);
        }
        tx.onKeepAliveReply(id, 77L, wire + 2_700_000_000L, "p");
        assertEquals(List.of("stall"), verdicts);
    }

    @Test
    @DisplayName("Signals below the alert threshold do not lower the round trip")
    void belowThresholdKeepsRoundTrip() {
        build(true, 3);
        provenChannel();
        tx.recordPing(id, System.nanoTime());
        tx.onPong(id, tx.recordPing(id, System.nanoTime() - 900_000_000L), "p");
        assertTrue(verdicts.isEmpty());
        assertFalse(tx.isSpoofSuspected(id), "one order signal is not yet a verdict");
        assertTrue(tx.roundTripMs(id) > 800);
    }

    @Test
    @DisplayName("A stale keep-alive round trip is not used to lower the transaction round trip")
    void staleKeepAliveIsNotUsed() {
        build(true, 1);
        provenChannel();
        long now = System.nanoTime();
        tx.onPong(id, tx.recordPing(id, now - 900_000_000L), "p");
        long old = now - (TransactionManager.KEEPALIVE_MAX_AGE_MS + 1_000L) * 1_000_000L;
        for (long k = 1; k <= 3; k++) {
            tx.onKeepAliveWritten(id, k, old);
            tx.onKeepAliveReply(id, k, old + 50_000_000L, "p");
        }
        assertEquals(List.of("divergence"), verdicts);
        assertEquals(-1.0, tx.keepAliveRoundTripMs(id), 1e-9);
        assertEquals(Constants.PINGSPOOF_RTT_CAP_MS, tx.roundTripMs(id), 1e-9,
                "without a fresh keep-alive only the fixed cap applies");
    }

    @Test
    @DisplayName("Legacy and Bedrock players are never treated as spoofing")
    void exemptPlayersAreNotSuspected() {
        build(true, 1);
        tx.setSpoofExemption(u -> true);
        provenChannel();
        tx.recordPing(id, System.nanoTime());
        tx.onPong(id, tx.recordPing(id, System.nanoTime() - 900_000_000L), "p");
        assertEquals(List.of("order"), verdicts, "fixture check: the verdict itself is filtered by the sink");
        assertFalse(tx.isSpoofSuspected(id));
        assertTrue(tx.roundTripMs(id) > 800);
    }

    @Test
    @DisplayName("Without spoof detection the pending cap is small and evicts the oldest ping")
    void smallCapWithoutDetection() {
        build(false, 1);
        int cap = tx.pendingCap();
        build(true, 1);
        assertTrue(cap < tx.pendingCap(), "detection needs the keep-alive horizon, plain RTT does not");
        build(false, 1);
        long now = System.nanoTime();
        int oldest = tx.recordPing(id, now);
        int newest = oldest;
        for (int i = 0; i < cap + 20; i++) newest = tx.recordPing(id, now);
        assertEquals(cap, tx.pendingCount(id));
        tx.onPong(id, oldest, "p");
        assertEquals(-1.0, tx.roundTripMs(id), 1e-9, "the oldest ping was evicted");
        tx.onPong(id, newest, "p");
        assertTrue(tx.roundTripMs(id) >= 0);
    }

    @Test
    @DisplayName("A busy client main thread is not a stall")
    void noTicksNoStall() {
        // Keep-alives are answered on the client's network thread, pings on its main thread.
        // Without tick packets the main thread may simply not have got to the ping yet.
        build(true, 1);
        provenChannel();
        long now = System.nanoTime();
        int ping = tx.recordPing(id, now);
        tx.onPingWritten(id, ping, now);
        tx.onKeepAliveWritten(id, 77L, now + 1_000_000L);
        tx.onKeepAliveReply(id, 77L, now + 60_000_000L, "p");
        assertTrue(verdicts.isEmpty());
    }

    @Test
    @DisplayName("A ping written after the keep-alive is not expected yet")
    void laterPingIsNotAStall() {
        build(true, 1);
        provenChannel();
        long now = System.nanoTime();
        tx.onKeepAliveWritten(id, 77L, now);
        int ping = tx.recordPing(id, now);
        tx.onPingWritten(id, ping, now);
        for (int i = 0; i < TransactionManager.STALL_MIN_CLIENT_TICKS; i++) tx.onClientTick(id, now + 10_000_000L + i);
        tx.onKeepAliveReply(id, 77L, now + 60_000_000L, "p");
        assertTrue(verdicts.isEmpty());
    }

    @Test
    @DisplayName("Transaction round trips far above keep-alive for several keep-alives diverge")
    void divergenceIsCaughtAndRttSanitised() {
        build(true, 1);
        provenChannel();
        long now = System.nanoTime();
        // A pong held back 900ms...
        tx.onPong(id, tx.recordPing(id, now - 900_000_000L), "p");
        assertTrue(tx.roundTripMs(id) > 800, "fixture check: inflated round trip");
        // ...while keep-alives come back in 50ms.
        for (long k = 1; k <= 3; k++) {
            long sent = System.nanoTime();
            tx.onKeepAliveWritten(id, k, sent);
            tx.onKeepAliveReply(id, k, sent + 50_000_000L, "p");
        }
        assertEquals(List.of("divergence"), verdicts);
        assertTrue(tx.isSpoofSuspected(id));
        assertEquals(50.0, tx.roundTripMs(id), 1.0, "reach and timer now get the keep-alive round trip");
    }

    @Test
    @DisplayName("The sanitised round trip never exceeds the measured one")
    void sanitisedRtt() {
        assertEquals(40.0, TransactionManager.sanitisedRtt(40.0, 90.0), 1e-9);
        assertEquals(90.0, TransactionManager.sanitisedRtt(900.0, 90.0), 1e-9);
        assertEquals(Constants.PINGSPOOF_RTT_CAP_MS, TransactionManager.sanitisedRtt(900.0, -1), 1e-9);
    }

    @Test
    @DisplayName("With detection off nothing is judged and the round trip is left alone")
    void offMeansOff() {
        build(false, 1);
        provenChannel();
        tx.recordPing(id, System.nanoTime());
        tx.onPong(id, tx.recordPing(id, System.nanoTime() - 900_000_000L), "p");
        assertTrue(verdicts.isEmpty());
        assertTrue(tx.roundTripMs(id) > 800);
    }

    @Test
    @DisplayName("Pings stay pending long enough to be compared with a keep-alive")
    void pendingCoversKeepAliveHorizon() {
        build(true, 1);
        for (int i = 0; i < 150; i++) tx.recordPing(id, System.nanoTime());
        assertEquals(150, tx.pendingCount(id), "150 pings at 2 ticks are 15s, inside the 20s horizon");
    }

    @Test
    @DisplayName("A ping that could not be sent is not left waiting")
    void unsentPingIsDropped() {
        build(true, 1);
        PlayerMock p = player(0.5, 64.0, 0.5);
        tx.sendPing(p); // no PacketEvents in tests: the send fails
        assertEquals(0, tx.pendingCount(p.getUniqueId()));
    }
}
