package dev.boondock.bsanticheat.anticheat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How move events become judged samples, and the speed budget judged against the real clock.
 *
 * <p>Events here are spaced like real ticks (50 ms) unless a scenario is about events that
 * arrive closer together than that.
 */
class MovementSamplingTest extends ScenarioBase {

    private MovementChecker checker;

    @BeforeEach
    void setUpChecker() {
        checker = new MovementChecker(plugin, config, null, lang);
        checker.setViolationManager(violations);
        checker.setPistonTracker(new PistonTracker());
        floor(79, Material.STONE);
    }

    private double x = 0.5;

    /** One move event along +X by {@code dx}; the player's own location stays on the floor. */
    private void step(PlayerMock player, double dx) {
        Location from = loc(x, 80.0, 0.5);
        x += dx;
        checker.onPlayerMove(new PlayerMoveEvent(player, from, loc(x, 80.0, 0.5)));
    }

    /** Start a session: a first event that only sets the baseline, then a pause. */
    private PlayerMock start() throws InterruptedException {
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        step(player, 0.01);
        Thread.sleep(50);
        return player;
    }

    @Test
    @DisplayName("A second packet sent right behind the first is judged, not dropped")
    void doublePacketsAreJudged() throws InterruptedException {
        PlayerMock player = start();
        // Every tick: a plausible step, then 1 ms later a second one carrying extra ground.
        // Each pair covers 1.2 blocks per tick against a 0.4 walking cap.
        for (int i = 0; i < 30; i++) {
            step(player, 0.2);
            Thread.sleep(1);
            step(player, 1.0);
            Thread.sleep(49);
        }
        assertTrue(violations.count("SPEED") > 0, "the folded-in packets must count");
    }

    @Test
    @DisplayName("A teleport split across packets inside one sample is still a teleport")
    void splitTeleportIsCaught() throws InterruptedException {
        PlayerMock player = start();
        step(player, 0.2);
        Thread.sleep(50);
        // Two 12-block hops 1 ms apart: each under the 15-block threshold on its own.
        step(player, 12.0);
        step(player, 12.0);
        assertTrue(violations.count("TELEPORT") > 0, "24 blocks in one millisecond is a teleport");
    }

    @Test
    @DisplayName("A lagging player's bunched packets are not speed")
    void bunchedPacketsAfterStallAreQuiet() throws InterruptedException {
        PlayerMock player = start();
        for (int round = 0; round < 6; round++) {
            // Walking pace, then a 250 ms stall whose five ticks of travel arrive together.
            for (int i = 0; i < 5; i++) {
                step(player, 0.25);
                Thread.sleep(50);
            }
            Thread.sleep(250);
            for (int i = 0; i < 5; i++) {
                step(player, 0.25);
                Thread.sleep(1);
            }
            Thread.sleep(49);
        }
        assertEquals(0, violations.count("SPEED"), "the backlog of a stall carries no extra speed");
        assertEquals(0, violations.count("TELEPORT"));
    }

    @Test
    @DisplayName("Alternating fast and slow ticks that average over the cap raise SPEED")
    void pulsingSpeedIsCaught() throws InterruptedException {
        PlayerMock player = start();
        // 0.8 / 0.2 averages 0.5 b/t against a 0.4 walking cap. The per-sample streak only
        // falls back under 70% of the cap, so the old check never flagged this.
        for (int i = 0; i < 90; i++) {
            step(player, i % 2 == 0 ? 0.8 : 0.2);
            Thread.sleep(50);
        }
        assertTrue(violations.count("SPEED") > 0, "the average is over the cap");
    }

    @Test
    @DisplayName("Steady walking just under the cap stays quiet over a long stretch")
    void steadyWalkingUnderCapIsQuiet() throws InterruptedException {
        PlayerMock player = start();
        for (int i = 0; i < 90; i++) {
            step(player, 0.36);
            Thread.sleep(50);
        }
        assertEquals(0, violations.count("SPEED"));
    }
}
