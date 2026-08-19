package dev.boondock.bsanticheat.anticheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The streak rule behind Nuker and FastPlace.
 *
 * <p>Both checks ask for several one-second windows over the rate limit before flagging,
 * because a plugin breaking a whole vein in one action, or a bundle of place packets, crosses
 * the limit once without anyone cheating. The requirement only means anything if the count
 * lapses: these two are the only rate checks whose counter cannot be reset by an ordinary
 * event — a window that stays UNDER the limit produces no event at all on this path — so a
 * plain counter never came down again, and three unrelated bursts spread over a session added
 * up to a flag. That is the regression these cases hold shut.
 *
 * <p>The clock is passed in rather than slept through, so the lapse can be exercised without
 * the test taking as long as the window it tests.
 */
class RateStreakTest {

    private static final long WINDOW_MS = 10_000L;

    private final Map<UUID, long[]> streaks = new HashMap<>();
    private final UUID player = UUID.randomUUID();

    private int bump(long atMs) {
        return WorldChecker.bumpStreak(streaks, player, atMs, WINDOW_MS);
    }

    @Test
    @DisplayName("Windows inside the streak window accumulate")
    void consecutiveWindowsCount() {
        assertEquals(1, bump(1_000));
        assertEquals(2, bump(2_000));
        assertEquals(3, bump(3_000));
    }

    @Test
    @DisplayName("A window past the lapse restarts the count")
    void lapsedStreakRestarts() {
        assertEquals(1, bump(1_000));
        assertEquals(2, bump(2_000));
        // Minutes later — a second vein-miner use, unrelated to the first.
        assertEquals(1, bump(2_000 + WINDOW_MS + 1), "an unrelated burst is not the next window");
    }

    @Test
    @DisplayName("Bursts spread over a session never reach the threshold")
    void separatedBurstsNeverAccumulate() {
        long at = 0;
        int highest = 0;
        // Ten bursts, each a full minute apart: the shape of ordinary play with a vein miner.
        for (int i = 0; i < 10; i++) {
            at += 60_000;
            highest = Math.max(highest, bump(at));
        }
        assertEquals(1, highest, "bursts minutes apart must never build a streak");
    }

    @Test
    @DisplayName("The lapse is measured from the last window, not the first")
    void windowSlidesAlong() {
        assertEquals(1, bump(0));
        // Each step sits just inside the window relative to the one before it, so a cheat
        // holding the rate up cannot outrun the count by pacing itself.
        assertEquals(2, bump(WINDOW_MS));
        assertEquals(3, bump(2 * WINDOW_MS));
    }
}
