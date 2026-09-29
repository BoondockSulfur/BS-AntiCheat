package dev.boondock.bsanticheat.anticheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Timer check's two guards against punishing a bad connection.
 *
 * <p>A connection with a round trip above one second can deliver a backlog spread over
 * hundreds of milliseconds, faster than one packet per tick but never all at once. No single
 * gap is long enough to count as a stall, so the balance climbs the whole way and can stay
 * over the limit for longer than {@code timer_sustained_ms}.
 *
 * <p>What separates that from a hack is not how high the balance goes or how long it stays
 * up, but whether it is still GAINING: a catch-up drains its backlog and plateaus, while a
 * timer hack gains on every tick it runs. These cases pin that difference.
 */
class TimerBalanceTest {

    private static final long LIMIT_CENTI = 200L * 100L;   // timer_max_balance_ms: 200

    /** Run a sequence of packet intervals through the balance and return the final value. */
    private static long run(long start, long... intervalsMs) {
        long balance = start;
        for (long gap : intervalsMs) balance = PacketChecker.updateBalance(balance, gap);
        return balance;
    }

    // ==================== the balance itself ====================

    @Test
    @DisplayName("A vanilla client at one packet per tick stays flat")
    void vanillaPaceIsFlat() {
        long balance = 0;
        for (int i = 0; i < 200; i++) balance = PacketChecker.updateBalance(balance, 50);
        // 50ms credited, 50.5ms charged: the 1% leak drains slightly rather than accumulating.
        assertTrue(balance <= 0, "one packet per tick must never build credit, got " + balance);
        assertTrue(balance > -100_000L, "and must not run away downwards either");
    }

    @Test
    @DisplayName("Idle credit is floored")
    void idleCreditIsFloored() {
        assertEquals(-100_000L, run(0, 5000, 5000, 5000),
                "a long silence must not bank credit to spend later");
    }

    // ==================== catch-up versus hack ====================

    @Test
    @DisplayName("A catch-up burst climbs and then plateaus")
    void catchUpPlateaus() {
        // 300ms of backlog arriving at ~20ms spacing — a typical catch-up. Not one gap
        // is long enough to count as a stall.
        long balance = 0;
        for (int i = 0; i < 15; i++) balance = PacketChecker.updateBalance(balance, 20);
        long peak = balance;
        assertTrue(peak > LIMIT_CENTI, "the burst does cross the limit: " + peak / 100 + "ms");

        // Once drained, the client is back to one packet per tick and the balance stops
        // gaining — this is the plateau the growth rule looks for.
        long after = balance;
        for (int i = 0; i < 20; i++) after = PacketChecker.updateBalance(after, 50);
        assertTrue(after <= peak, "a drained backlog must not keep gaining");
    }

    @Test
    @DisplayName("A timer hack keeps gaining")
    void timerHackKeepsGaining() {
        // 1.5x game speed: packets every ~33ms, indefinitely.
        long balance = 0;
        for (int i = 0; i < 15; i++) balance = PacketChecker.updateBalance(balance, 33);
        long atOneSecond = balance;
        for (int i = 0; i < 30; i++) balance = PacketChecker.updateBalance(balance, 33);
        assertTrue(balance - atOneSecond >= 150L * 100L,
                "a hack must still be gaining a second later, gained "
                        + (balance - atOneSecond) / 100 + "ms");
    }

    @Test
    @DisplayName("The catch-up gains less over the same span than the hack")
    void catchUpGainsLessThanHack() {
        // Both start over the limit; only the hack is still climbing afterwards. This is the
        // whole discriminator, so it is asserted directly rather than inferred.
        long caught = run(LIMIT_CENTI, 50, 50, 50, 50, 50, 50, 50, 50, 50, 50);
        long hacked = run(LIMIT_CENTI, 33, 33, 33, 33, 33, 33, 33, 33, 33, 33);
        assertTrue(caught <= LIMIT_CENTI, "a drained connection loses ground at normal pace");
        assertTrue(hacked - LIMIT_CENTI > 150L * 100L, "the hack gains through the same span");
    }

    // ==================== the round-trip window extension ====================

    @Test
    @DisplayName("Without a measurement the window is the configured one")
    void noRttKeepsConfiguredWindow() {
        assertEquals(1000L, PacketChecker.requiredExcursionMs(1000L, -1, 3000L));
        assertEquals(1000L, PacketChecker.requiredExcursionMs(1000L, 0, 3000L));
    }

    @Test
    @DisplayName("A bad link has to hold the excursion for longer")
    void badLinkNeedsLongerExcursion() {
        // With a 1444ms round trip, a catch-up lasts about one round trip, so requiring
        // more than that rules it out.
        assertEquals(2444L, PacketChecker.requiredExcursionMs(1000L, 1444, 3000L));
    }

    @Test
    @DisplayName("The extension is capped")
    void extensionIsCapped() {
        // Faking latency by answering transaction pings late must not buy immunity — the cap
        // bounds how much it can delay the flag, and the window is stretched, not the limit.
        assertEquals(4000L, PacketChecker.requiredExcursionMs(1000L, 30_000, 3000L));
    }

    @Test
    @DisplayName("A good connection is unaffected")
    void goodConnectionUnaffected() {
        assertEquals(1040L, PacketChecker.requiredExcursionMs(1000L, 40, 3000L));
    }

    // ==================== the per-player accounting ====================

    private static final long WINDOW_MS = 1000L;        // catch-up window without RTT
    private static final long REQUIRED_MS = 1000L;      // timer_sustained_ms
    private static final long MIN_GROWTH_CENTI = 150L * 100L;

    /** Drives a TimerState with tick-end packets and counts the flags it raises. */
    private static final class Client {
        final PacketChecker.TimerState st = new PacketChecker.TimerState();
        long now = 1_000_000L;
        int flags;

        void tickEnd(long afterMs) {
            now += afterMs;
            st.onTickEnd(now, WINDOW_MS);
            judge();
        }

        void move(long afterMs) {
            now += afterMs;
            if (st.onMovement(now, WINDOW_MS)) judge();
        }

        void judge() {
            if (st.judge(now, LIMIT_CENTI, REQUIRED_MS, MIN_GROWTH_CENTI) == PacketChecker.TimerState.FLAG) flags++;
        }

        void ticks(int count, long everyMs) {
            for (int i = 0; i < count; i++) tickEnd(everyMs);
        }
    }

    @Test
    @DisplayName("Vanilla play with movement and tick-end packets never flags")
    void vanillaInterleavedIsQuiet() {
        Client c = new Client();
        for (int i = 0; i < 2000; i++) {
            c.move(0);
            c.tickEnd(50);
        }
        assertEquals(0, c.flags);
        assertTrue(c.st.balance <= 0, "movement packets must not claim a second tick");
    }

    @Test
    @DisplayName("Short pauses no longer open a judgement-free window")
    void pauseAndSprintIsCaught() {
        // A 401ms silence must not reset the balance or suspend judgement for 3s, which
        // would allow running at double speed indefinitely.
        Client c = new Client();
        c.ticks(100, 50);
        for (int cycle = 0; cycle < 3 && c.flags == 0; cycle++) {
            c.now += 401;
            c.ticks(120, 25); // 3s at 2x
        }
        assertTrue(c.flags > 0, "2x speed between short pauses is a timer hack");
    }

    @Test
    @DisplayName("A stalled connection's backlog does not flag")
    void stallBacklogIsAbsorbed() {
        // Eight seconds without a packet, then all 160 queued ticks in one bundle, then
        // normal play. The backlog claims exactly the time the stall took.
        Client c = new Client();
        c.ticks(40, 50);
        c.now += 8000;
        c.tickEnd(0);
        c.ticks(159, 2);
        c.ticks(200, 50);
        assertEquals(0, c.flags, "a catch-up is not a hack");
        assertTrue(c.st.balance <= LIMIT_CENTI);
    }

    @Test
    @DisplayName("A stall's credit expires with its catch-up window")
    void stallCreditIsNotBanked() {
        // Faking a stall must not bank its length: once the window has passed, only the
        // ordinary credit is left.
        Client c = new Client();
        c.ticks(40, 50);
        c.now += 10_000;
        c.tickEnd(0);
        c.ticks(40, 50);   // two seconds of normal play: the window has closed
        assertTrue(c.st.balance >= -100_000L, "credit is back to the ordinary clamp");
        c.ticks(200, 25);  // then 2x
        assertTrue(c.flags > 0, "the stall's length was not kept for later");
    }

    @Test
    @DisplayName("Idle credit is bounded in tick-end mode")
    void idleCreditBounded() {
        Client c = new Client();
        c.ticks(20_000, 50); // ~17 minutes idle: the drift leak piles up credit
        assertTrue(c.st.balance >= -100_000L, "credit must stay clamped at one second");
        c.ticks(400, 25);
        assertTrue(c.flags > 0, "idling must not pay for ten seconds at double speed");
    }

    @Test
    @DisplayName("Dropping tick-end packets does not hide a timer")
    void suppressedTickEndStillCounted() {
        // Once a client has sent tick-end packets, movement packets without one in between
        // are charged as ticks themselves.
        Client c = new Client();
        c.ticks(40, 50);
        for (int i = 0; i < 400; i++) c.move(25);
        assertTrue(c.flags > 0);
    }

    @Test
    @DisplayName("Repeated setbacks do not add up to a timer flag")
    void setbackConfirmationsAreNotTicks() {
        // Short teleports do not reset the accounting, and the client answers each one
        // with an extra movement packet outside its tick loop.
        Client c = new Client();
        for (int i = 0; i < 400; i++) {
            if (i % 5 == 0) {
                c.st.expectTeleportMove();
                c.move(0);
            }
            c.move(0);
            c.tickEnd(50);
        }
        assertEquals(0, c.flags);
    }

    @Test
    @DisplayName("Clients without tick-end fall back to movement packets")
    void movementFallback() {
        Client c = new Client();
        for (int i = 0; i < 400; i++) c.move(50);
        assertEquals(0, c.flags);
        for (int i = 0; i < 400; i++) c.move(25);
        assertTrue(c.flags > 0);
    }
}
