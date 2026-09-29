package dev.boondock.bsanticheat.anticheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The short-window tick clock behind the lag exemption.
 */
class ServerLoadTest {

    private static final long TICK = 50_000_000L;
    private static final double FLOOR = 18.0;

    /** Feed {@code n} ticks at the given interval, returning the time of the last one. */
    private static long run(ServerLoad.TickClock clock, long t, int n, long interval) {
        for (int i = 0; i < n; i++) {
            t += interval;
            clock.tick(t);
        }
        return t;
    }

    @Test
    @DisplayName("A clock that never ticked reports no lag")
    void neverStartedIsNotLagging() {
        assertFalse(new ServerLoad.TickClock().lagging(FLOOR, System.nanoTime()));
    }

    @Test
    @DisplayName("A healthy server reads 20 TPS")
    void healthyServer() {
        ServerLoad.TickClock clock = new ServerLoad.TickClock();
        long t = run(clock, 1_000_000_000L, 100, TICK);
        assertEquals(20.0, clock.tps(), 0.01);
        assertFalse(clock.lagging(FLOOR, t + TICK / 2));
    }

    @Test
    @DisplayName("A five-second freeze suspends checks only for a few seconds")
    void freezeRecoversQuickly() {
        ServerLoad.TickClock clock = new ServerLoad.TickClock();
        long t = run(clock, 1_000_000_000L, 100, TICK);
        t += 5_000_000_000L;
        clock.tick(t);
        assertTrue(clock.lagging(FLOOR, t), "the freeze is seen on the first tick after it");
        // One window of healthy ticks later the freeze has left the window entirely. The
        // one-minute average used before stayed under 18 TPS for most of a minute.
        t = run(clock, t, ServerLoad.WINDOW_TICKS, TICK);
        assertFalse(clock.lagging(FLOOR, t), "recovered within one window");
    }

    @Test
    @DisplayName("A tick that is overdue right now counts as lag")
    void overdueTickIsLag() {
        ServerLoad.TickClock clock = new ServerLoad.TickClock();
        long t = run(clock, 1_000_000_000L, 100, TICK);
        assertTrue(clock.lagging(FLOOR, t + ServerLoad.STALE_NANOS + 1));
    }

    @Test
    @DisplayName("Sustained slow ticks read as lag")
    void sustainedSlowTicks() {
        ServerLoad.TickClock clock = new ServerLoad.TickClock();
        long t = run(clock, 1_000_000_000L, 100, 70_000_000L); // ~14 TPS
        assertTrue(clock.lagging(FLOOR, t));
    }
}
