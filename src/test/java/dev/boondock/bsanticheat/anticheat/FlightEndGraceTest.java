package dev.boondock.bsanticheat.anticheat;

import org.bukkit.GameMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flight that ends in mid-air. Live case 2026-10-08: an operator relogged while flying in
 * creative, the world forced survival on join, and the client kept flying until the next
 * /gmc. The two-second flight-end grace had run out, so nine hover alerts followed.
 */
class FlightEndGraceTest {

    @Test
    @DisplayName("Leaving creative or spectator for a mode without flight ends flight")
    void leavingFlyingModes() {
        assertTrue(MovementChecker.endsFlight(GameMode.CREATIVE, GameMode.SURVIVAL));
        assertTrue(MovementChecker.endsFlight(GameMode.CREATIVE, GameMode.ADVENTURE));
        assertTrue(MovementChecker.endsFlight(GameMode.SPECTATOR, GameMode.SURVIVAL));
    }

    @Test
    @DisplayName("Changes that keep or never had flight do not")
    void otherChanges() {
        assertFalse(MovementChecker.endsFlight(GameMode.SURVIVAL, GameMode.ADVENTURE));
        assertFalse(MovementChecker.endsFlight(GameMode.ADVENTURE, GameMode.SURVIVAL));
        assertFalse(MovementChecker.endsFlight(GameMode.CREATIVE, GameMode.SPECTATOR));
        assertFalse(MovementChecker.endsFlight(GameMode.SURVIVAL, GameMode.CREATIVE));
    }

    @Test
    @DisplayName("The grace holds in the air beyond the two-second momentum grace")
    void holdsWhileAirborne() {
        // The live alerts ran 3 to 15 seconds after the change.
        assertTrue(MovementChecker.flightEndGraceHolds(0L, false, 15_000L));
    }

    @Test
    @DisplayName("Landing ends the grace")
    void endsOnLanding() {
        assertFalse(MovementChecker.flightEndGraceHolds(0L, true, 500L));
    }

    @Test
    @DisplayName("The grace is capped")
    void isCapped() {
        assertFalse(MovementChecker.flightEndGraceHolds(0L, false, MovementChecker.FLIGHT_END_MAX_AIR_MS));
    }
}
