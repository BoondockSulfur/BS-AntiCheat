package dev.boondock.bsanticheat.anticheat;

import org.bukkit.Location;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.function.IntToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boat-Fly that keeps moving vertically: sinking slowly, or bobbing around one height, is
 * compared with the fall gravity requires over the whole airborne stretch.
 *
 * <p>MockBukkit does not implement vehicle passengers, so the airborne-stretch judgement is
 * driven directly with the samples the move handler would pass it.
 */
class BoatGravityTest extends ScenarioBase {

    private VehicleChecker checker;

    @BeforeEach
    void setUpChecker() {
        checker = new VehicleChecker(plugin, config, null, lang);
        checker.setViolationManager(violations);
    }

    /** One airborne boat sample per tick along +X, with dy given per tick index. */
    private void drive(PlayerMock player, double startY, int ticks, IntToDoubleFunction dy) throws InterruptedException {
        double y = startY;
        double x = 0.5;
        for (int i = 0; i < ticks; i++) {
            Location from = loc(x, y, 0.5);
            y += dy.applyAsDouble(i);
            x += 0.3;
            checker.checkBoatGravity(player, player.getUniqueId(), from, loc(x, y, 0.5), true);
            Thread.sleep(50);
        }
    }

    @Test
    @DisplayName("A boat sinking slowly through the air raises BOATFLY")
    void slowSinkIsCaught() throws Exception {
        PlayerMock player = player(0.5, 100.0, 0.5);
        // -0.02 per tick never meets the per-sample "not falling" test (dy > -0.01).
        drive(player, 100.0, 40, i -> -0.02);
        assertTrue(violations.count("BOATFLY") > 0, "a boat falls at 0.04 b/t per tick");
    }

    @Test
    @DisplayName("A boat bobbing around one height raises BOATFLY")
    void oscillationIsCaught() throws Exception {
        PlayerMock player = player(0.5, 100.0, 0.5);
        drive(player, 100.0, 40, i -> i % 2 == 0 ? -0.05 : 0.04);
        assertTrue(violations.count("BOATFLY") > 0);
    }

    @Test
    @DisplayName("A boat falling off a cliff raises nothing")
    void realFallIsQuiet() throws Exception {
        PlayerMock player = player(0.5, 100.0, 0.5);
        drive(player, 100.0, 40, i -> -0.04 * i);
        assertEquals(0, violations.count("BOATFLY"));
    }

    @Test
    @DisplayName("A boat thrown upwards keeps the speed it was seen with")
    void launchedBoatIsQuiet() throws Exception {
        PlayerMock player = player(0.5, 100.0, 0.5);
        // Off a bubble column: 1.0 b/t upwards, then gravity.
        drive(player, 100.0, 60, i -> 1.0 - 0.04 * i);
        assertEquals(0, violations.count("BOATFLY"));
    }
}
