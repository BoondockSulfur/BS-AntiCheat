package dev.boondock.bsanticheat.anticheat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.function.IntToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The free-fall check: over an airborne stretch, a player must come down at least as far as
 * gravity takes them. It covers what the hover band cannot see — bobbing around one height,
 * a slow constant descent, and a steady climb — and it runs regardless of the opt-in
 * sustained-ascent and spider toggles.
 *
 * <p>Samples are spaced like real ticks, because the check judges against the clock.
 */
class FreeFallTest extends ScenarioBase {

    private MovementChecker checker;

    @BeforeEach
    void setUpChecker() {
        checker = new MovementChecker(plugin, config, null, lang);
        checker.setViolationManager(violations);
        checker.setPistonTracker(new PistonTracker());
        // Far below: every sample is clearly airborne.
        floor(40, Material.STONE);
    }

    /** Drive a vertical profile, one event per tick, with dy given per tick index. */
    private void fly(PlayerMock player, double startY, int ticks, IntToDoubleFunction dy) throws InterruptedException {
        double y = startY;
        double x = 0.5;
        Location previous = loc(x, y, 0.5);
        player.teleport(previous);
        checker.onPlayerMove(new PlayerMoveEvent(player, previous, loc(x + 0.001, y, 0.5)));
        previous = loc(x + 0.001, y, 0.5);
        Thread.sleep(50);
        for (int i = 0; i < ticks; i++) {
            y += dy.applyAsDouble(i);
            x += 0.01;
            Location next = loc(x, y, 0.5);
            player.teleport(previous);
            checker.onPlayerMove(new PlayerMoveEvent(player, previous, next));
            previous = next;
            Thread.sleep(50);
        }
    }

    @Test
    @DisplayName("Bobbing up and down around one height raises FLY")
    void oscillationIsCaught() throws Exception {
        PlayerMock player = player(0.5, 100.0, 0.5);
        clearGrace();
        // +-0.09 sits just outside the hover band on every sample.
        fly(player, 100.0, 40, i -> i % 2 == 0 ? 0.09 : -0.09);
        assertTrue(violations.count("FLY") > 0, "net altitude never drops");
    }

    @Test
    @DisplayName("Sinking at a constant slow rate raises FLY")
    void slowDescentIsCaught() throws Exception {
        PlayerMock player = player(0.5, 100.0, 0.5);
        clearGrace();
        fly(player, 100.0, 40, i -> -0.09);
        assertTrue(violations.count("FLY") > 0, "gravity would have the player falling far faster");
    }

    @Test
    @DisplayName("A steady climb raises FLY even with the opt-in ascent check off")
    void steadyClimbIsCaught() throws Exception {
        PlayerMock player = player(0.5, 100.0, 0.5);
        clearGrace();
        fly(player, 100.0, 40, i -> 0.15);
        assertTrue(violations.count("FLY") > 0, "nothing is lifting this player");
    }

    @Test
    @DisplayName("A real fall raises nothing")
    void realFallIsQuiet() throws Exception {
        PlayerMock player = player(0.5, 120.0, 0.5);
        clearGrace();
        // Vanilla: walked off an edge, so dy starts at zero and gravity takes over.
        double[] v = {0.0};
        fly(player, 120.0, 40, i -> {
            double d = v[0];
            v[0] = (v[0] - 0.08) * 0.98;
            return d;
        });
        assertEquals(0, violations.count("FLY"));
    }

    @Test
    @DisplayName("A jump off a ledge raises nothing")
    void ledgeJumpIsQuiet() throws Exception {
        PlayerMock player = player(0.5, 120.0, 0.5);
        clearGrace();
        double[] v = {0.42};
        fly(player, 120.0, 40, i -> {
            double d = v[0];
            v[0] = (v[0] - 0.08) * 0.98;
            return d;
        });
        assertEquals(0, violations.count("FLY"));
    }

    @Test
    @DisplayName("Slow Falling exempts a slow descent")
    void slowFallingIsExempt() throws Exception {
        PlayerMock player = player(0.5, 100.0, 0.5);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 600, 0));
        clearGrace();
        fly(player, 100.0, 40, i -> -0.09);
        assertEquals(0, violations.count("FLY"));
    }

    @Test
    @DisplayName("Flight allowed by the server exempts it")
    void allowFlightIsExempt() throws Exception {
        PlayerMock player = player(0.5, 100.0, 0.5);
        player.setAllowFlight(true);
        clearGrace();
        fly(player, 100.0, 40, i -> -0.09);
        assertEquals(0, violations.count("FLY"));
    }
}
