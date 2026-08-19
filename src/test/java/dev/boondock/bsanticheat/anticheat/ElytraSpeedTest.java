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
 * The elytra speed ceiling, which had no end-to-end coverage at all.
 *
 * <p>Added while investigating seven live alerts of 204-311 b/s against a 140 ceiling. Whether
 * those were real could not be settled from the data — see the 1.0.5 notes — but the check
 * itself should at least be known to fire on sustained over-speed, which nothing verified
 * before.
 */
class ElytraSpeedTest extends ScenarioBase {

    private MovementChecker checker;

    @BeforeEach
    void setUpChecker() {
        checker = new MovementChecker(plugin, config, null, lang);
        checker.setViolationManager(violations);
        floor(60, Material.STONE);
    }

    /** A gliding player, or the test is meaningless. */
    private PlayerMock glidingPlayer() {
        PlayerMock player = player(0.5, 120.0, 0.5);
        player.setGliding(true);
        assertTrue(player.isGliding(), "fixture check: the player must actually be gliding");
        return player;
    }

    /** Fly a straight line, one event per step, with a chosen pause between events. */
    private Location fly(PlayerMock player, Location from, double perStep, int steps, long pauseMs)
            throws InterruptedException {
        Location previous = from;
        for (int i = 0; i < steps; i++) {
            Location next = loc(previous.getX() + perStep, 120.0, 0.5);
            player.teleport(previous);
            checker.onPlayerMove(new PlayerMoveEvent(player, previous, next));
            previous = next;
            if (pauseMs > 0) Thread.sleep(pauseMs);
        }
        return previous;
    }

    @Test
    @DisplayName("Genuinely impossible elytra speed is still caught")
    void realOverSpeedIsCaught() throws Exception {
        PlayerMock player = glidingPlayer();
        clearGrace();
        // 2 blocks every 12 ms is about 165 b/s — over the 140 ceiling, and sustained.
        fly(player, loc(0.5, 120.0, 0.5), 2.0, 80, 12);
        assertTrue(violations.count("ELYTRA") > 0, "sustained over-speed must survive the fix");
    }

}
