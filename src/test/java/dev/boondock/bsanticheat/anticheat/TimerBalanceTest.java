package dev.boondock.bsanticheat.anticheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Timer check's two guards against punishing a bad connection.
 *
 * <p>Live case, 2026-08-23: a player whose measured round trip ran 1275-1444ms reached a
 * balance of 779ms and was flagged. No single gap in their packet stream ever exceeded
 * PACKET_GAP_MS, so the stall grace never engaged — the backlog arrived spread over hundreds
 * of milliseconds, faster than one packet per tick but never all at once. The balance climbed
 * the whole way, held over the limit for longer than {@code timer_sustained_ms}, and fired.
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
        // 300ms of backlog arriving at ~20ms spacing — the live signature. Not one packet
        // exceeds PACKET_GAP_MS (400ms), so no stall grace applies.
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
        // The live measurement: 1444ms round trip. The catch-up that produced the false
        // positive lasted about one round trip, so requiring more than that rules it out.
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
}
