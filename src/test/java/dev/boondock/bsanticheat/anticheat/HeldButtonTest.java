package dev.boondock.bsanticheat.anticheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The AutoClicker held-button exclusion.
 *
 * <p>These cases exist because of a live false positive: a player holding the mouse button
 * was flagged at "23 CPS (Max: 22)" four times. The exclusion was keyed off the CPS count,
 * which is a sliding window over packet ARRIVAL times — network jitter bunches arrivals and
 * lifts the count while the actual cadence never changes. The fix reads the cadence instead,
 * and the tests below pin both halves of that: jitter must not break the exclusion, and the
 * exclusion must not become a hiding place for a genuine clicker.
 */
class HeldButtonTest {

    /** Intervals around a mean, alternating over/under by {@code jitter}. */
    private static long[] intervals(int count, long mean, long jitter) {
        long[] out = new long[count];
        for (int i = 0; i < count; i++) {
            out[i] = mean + (i % 2 == 0 ? jitter : -jitter);
        }
        return out;
    }

    @Test
    @DisplayName("A held button ticking at exactly 50ms is recognised")
    void cleanTickCadence() {
        assertTrue(PacketChecker.isHeldButton(intervals(20, 50, 0)));
    }

    @Test
    @DisplayName("A held button still counts when the network jitters it")
    void heldButtonWithJitter() {
        // This is the live case: arrivals scatter, the window reads 23 CPS, the cadence is
        // untouched. Under the old CPS-range rule this fell into the gap and was flagged.
        assertTrue(PacketChecker.isHeldButton(intervals(20, 50, 8)));
    }

    @Test
    @DisplayName("A single pause does not break the exclusion")
    void heldButtonSurvivesOnePause() {
        // Reloading, a brief stop — one outlier. It would wreck a standard deviation but
        // leaves the median where it is, which is why MAD is used.
        long[] iv = intervals(20, 50, 3);
        iv[9] = 900;
        assertTrue(PacketChecker.isHeldButton(iv));
    }

    @Test
    @DisplayName("A hand clicking at 23 CPS is NOT excluded")
    void handClickingIsNotHeld() {
        // 23 CPS is a 43.5ms interval — off the tick cadence, so the exclusion must not
        // apply. If this ever passes, closing the false positive has opened a hiding place.
        assertFalse(PacketChecker.isHeldButton(intervals(20, 43, 2)));
    }

    @Test
    @DisplayName("A fast autoclicker is NOT excluded")
    void fastAutoClickerIsNotHeld() {
        assertFalse(PacketChecker.isHeldButton(intervals(20, 25, 1))); // 40 CPS, metronomic
    }

    @Test
    @DisplayName("Irregular human clicking is NOT excluded")
    void irregularClickingIsNotHeld() {
        long[] iv = {50, 70, 45, 90, 40, 65, 55, 80, 48, 72, 51, 95};
        assertFalse(PacketChecker.isHeldButton(iv));
    }

    @Test
    @DisplayName("Too few samples never count as a held button")
    void tooFewSamples() {
        assertFalse(PacketChecker.isHeldButton(intervals(6, 50, 0)));
        assertFalse(PacketChecker.isHeldButton(new long[0]));
    }

    @Test
    @DisplayName("Median and MAD behave on known input")
    void statistics() {
        org.junit.jupiter.api.Assertions.assertEquals(3.0, PacketChecker.median(new long[]{1, 3, 5}));
        org.junit.jupiter.api.Assertions.assertEquals(3.5, PacketChecker.median(new long[]{1, 3, 4, 9}));
        org.junit.jupiter.api.Assertions.assertEquals(0.0, PacketChecker.median(new long[0]));
        // MAD ignores the outlier that would dominate a standard deviation.
        org.junit.jupiter.api.Assertions.assertEquals(
                2.0, PacketChecker.medianAbsoluteDeviation(new long[]{48, 50, 52, 50, 900}, 50.0));
    }
}
