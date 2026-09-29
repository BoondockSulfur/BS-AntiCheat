package dev.boondock.bsanticheat.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Latency slack, which widens the threshold of nearly every movement and combat check.
 *
 * <p>It is worth pinning precisely because it is invisible in practice: too little and
 * players on poor connections are flagged for their latency, too much and it becomes a free
 * allowance that a cheat can claim by inflating its own ping.
 */
class CheckMathTest {

    @Test
    @DisplayName("Up to 100ms there is no slack at all")
    void noSlackBelowThreshold() {
        assertEquals(1.0, CheckMath.pingSlack(0));
        assertEquals(1.0, CheckMath.pingSlack(100));
        assertEquals(1.0, CheckMath.pingSlack(-50)); // nonsense input must not widen anything
    }

    @Test
    @DisplayName("Documented scaling points hold: 200ms → +10%, 500ms → +20%")
    void documentedScaling() {
        assertEquals(1.10, CheckMath.pingSlack(200), 0.001);
        assertEquals(1.20, CheckMath.pingSlack(500), 0.001);
    }

    @Test
    @DisplayName("Holding pongs buys no slack beyond the cap")
    void slackIsCapped() {
        double cap = CheckMath.pingSlack(CheckMath.MAX_SLACK_PING_MS);
        assertEquals(cap, CheckMath.pingSlack(1000), 1e-9);
        assertEquals(cap, CheckMath.pingSlack(3000), 1e-9);
        assertEquals(cap, CheckMath.pingSlack(Integer.MAX_VALUE), 1e-9);
    }

    @Test
    @DisplayName("Slack grows with ping but stays bounded in practice")
    void monotonicAndBounded() {
        double previous = 0.0;
        for (int ping = 0; ping <= 2000; ping += 50) {
            double slack = CheckMath.pingSlack(ping);
            assertTrue(slack >= previous, "slack must not fall as ping rises (at " + ping + "ms)");
            previous = slack;
        }
        // Even an absurd ping must not hand out a multiple of the limit.
        assertTrue(CheckMath.pingSlack(10_000) < 2.0,
                "a 10s ping must not double every threshold");
    }

    @Test
    @DisplayName("Free-fall closed form matches tick-by-tick vanilla physics")
    void ballisticRiseMatchesIteration() {
        double[][] cases = {{0.42, 0.08, 0.98}, {0.0, 0.08, 0.98}, {1.5, 0.04, 0.98}, {0.1, 0.04, 1.0}};
        for (double[] c : cases) {
            double y = 0, v = c[0];
            for (int t = 1; t <= 60; t++) {
                y += v;
                v = (v - c[1]) * c[2];
                assertEquals(y, CheckMath.ballisticRise(c[0], c[1], c[2], t), 1e-9,
                        "v0=" + c[0] + " g=" + c[1] + " drag=" + c[2] + " t=" + t);
            }
        }
        assertEquals(0.0, CheckMath.ballisticRise(0.42, 0.08, 0.98, 0));
    }
}
