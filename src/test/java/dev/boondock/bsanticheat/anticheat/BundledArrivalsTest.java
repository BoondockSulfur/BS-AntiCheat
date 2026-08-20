package dev.boondock.bsanticheat.anticheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Click rate when the network bundles the packets.
 *
 * <p>AutoClicker used to read its rate off a sliding count of arrival times, which puts the
 * network in charge of the answer: a connection that delivers a tick's swings in clumps makes
 * the count read whatever the clumping lines up with, while nothing about the clicking changed.
 *
 * <p>The live case this reconstructs: an alert at 26 CPS from a player who was placing blocks,
 * whose interval median sat at exactly 50.0 ms — one server tick, the held-button cadence —
 * with a MAD of 49 ms. Those two numbers together describe a bimodal arrival pattern, half the
 * intervals near 0 ms and half near 100 ms, which is what {@link #bundledPairs} builds. The
 * median was right throughout; only the count was wrong.
 */
class BundledArrivalsTest {

    /**
     * Swings emitted one per 50 ms tick, but delivered two at a time: each pair lands together
     * and the next pair follows 100 ms later. Reproduces median 50 ms / MAD 49 ms exactly.
     */
    private static long[] bundledPairs(int pairs) {
        // One arrival more than the pairs themselves, so the interval count is even and the
        // median falls between the two clusters — which is how it landed on 50 ms live.
        long[] out = new long[pairs * 2 + 1];
        for (int i = 0; i < pairs; i++) {
            out[i * 2] = i * 100L;
            out[i * 2 + 1] = i * 100L + 1;
        }
        out[pairs * 2] = pairs * 100L;
        return out;
    }

    private static long[] intervalsOf(long[] arrivals) {
        long[] intervals = new long[arrivals.length - 1];
        for (int i = 1; i < arrivals.length; i++) intervals[i - 1] = arrivals[i] - arrivals[i - 1];
        return intervals;
    }

    /** Swings at a steady human rate, arriving cleanly. */
    private static long[] steady(int count, long intervalMs) {
        long[] out = new long[count];
        for (int i = 0; i < count; i++) out[i] = i * intervalMs;
        return out;
    }

    @Test
    @DisplayName("The fixture reproduces the live numbers")
    void fixtureMatchesTheLiveAlert() {
        long[] intervals = intervalsOf(bundledPairs(10));
        double median = PacketChecker.median(intervals);
        assertEquals(50.0, median, 0.001, "live alert reported median=50.0ms");
        assertEquals(49.0, PacketChecker.medianAbsoluteDeviation(intervals, median), 0.001,
                "live alert reported mad=49.0ms");
    }

    @Test
    @DisplayName("Bundled arrivals do not inflate the rate")
    void bundlingDoesNotInflateTheRate() {
        long[] intervals = intervalsOf(bundledPairs(10));
        // 20 packets inside one second, which is what the old sliding count would have read
        // — and it climbed past 25 in the live case as the bundles lined up.
        int arrivals = 26;
        assertEquals(20, PacketChecker.cpsFromInterval(PacketChecker.median(intervals), arrivals),
                "one swing per tick is 20 CPS however the packets are delivered");
    }

    @Test
    @DisplayName("A genuinely fast clicker still reads fast")
    void realFastClickingStillReadsFast() {
        // 26 CPS by hand is a 38 ms interval, nowhere near a tick.
        long[] intervals = intervalsOf(steady(20, 38));
        assertEquals(26, PacketChecker.cpsFromInterval(PacketChecker.median(intervals), 26));
        assertFalse(PacketChecker.isHeldButton(intervals),
                "38ms is not the held-button cadence and must not be excluded");
    }

    @Test
    @DisplayName("A short fast burst is not a sustained rate")
    void shortBurstIsNotASustainedRate() {
        // The live regression: eight clicks about 33 ms apart and then a pause. Every interval
        // in the window is a burst interval, so the median reports the speed inside the burst
        // — but only eight clicks arrived in the second it is supposed to describe.
        long[] intervals = intervalsOf(steady(8, 33));
        double median = PacketChecker.median(intervals);
        assertEquals(30, (int) Math.round(1000.0 / median), "the median alone says 30 CPS");
        assertEquals(8, PacketChecker.cpsFromInterval(median, 8),
                "eight clicks in a second is eight clicks, however fast the burst was");
    }

    @Test
    @DisplayName("Sustained fast clicking is still caught")
    void sustainedFastClickingStillFlags() {
        // Same 33 ms cadence, but held up long enough that the second really does contain 30.
        long[] intervals = intervalsOf(steady(31, 33));
        assertEquals(30, PacketChecker.cpsFromInterval(PacketChecker.median(intervals), 30),
                "min() must not blunt the case the check exists for");
    }

    @Test
    @DisplayName("Too few samples fall back to the raw count")
    void fallsBackWhileTheMedianIsMeaningless() {
        assertEquals(7, PacketChecker.cpsFromInterval(-1, 7), "no median yet — use what we have");
    }

    @Test
    @DisplayName("Clean held-button swings are still recognised")
    void cleanHeldButtonStillRecognised() {
        long[] intervals = intervalsOf(steady(20, 50));
        assertTrue(PacketChecker.isHeldButton(intervals));
        assertEquals(20, PacketChecker.cpsFromInterval(PacketChecker.median(intervals), 20));
    }
}
