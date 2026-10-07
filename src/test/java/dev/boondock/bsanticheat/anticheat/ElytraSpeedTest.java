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
 * <p>The check must fire on sustained over-speed against the configured ceiling (140 b/s).
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

    @Test
    @DisplayName("A wind charge or TNT boost while gliding is covered by the knockback grace")
    void boostedGlideIsExempt() throws Exception {
        PlayerMock player = glidingPlayer();
        clearGrace();
        // The boost arrives as a server-applied velocity.
        checker.onPlayerVelocity(new org.bukkit.event.player.PlayerVelocityEvent(
                player, new org.bukkit.util.Vector(3.0, 1.0, 0.0)));
        // Same over-speed as above, inside the two-second grace.
        fly(player, loc(0.5, 120.0, 0.5), 2.0, 80, 12);
        assertEquals(0, violations.count("ELYTRA"), "the boost is not the player's doing");
    }

    /** A gliding player whose riptide state the test controls (MockBukkit has none). */
    private static final class RiptidePlayer extends PlayerMock {
        boolean riptiding;

        RiptidePlayer(org.mockbukkit.mockbukkit.ServerMock server) {
            super(server, "Riptider", java.util.UUID.randomUUID());
        }

        @Override
        public boolean isRiptiding() {
            return riptiding;
        }
    }

    private RiptidePlayer glidingRiptidePlayer() {
        RiptidePlayer player = new RiptidePlayer(server);
        server.addPlayer(player);
        player.setGameMode(org.bukkit.GameMode.SURVIVAL);
        player.setOp(false);
        player.teleport(loc(0.5, 120.0, 0.5));
        player.setGliding(true);
        return player;
    }

    @Test
    @DisplayName("A riptide launch while gliding raises the ceiling by the riptide ceiling")
    void riptideWhileGlidingIsQuiet() throws Exception {
        RiptidePlayer player = glidingRiptidePlayer();
        clearGrace();
        player.riptiding = true;
        fly(player, loc(0.5, 120.0, 0.5), 1.0, 2, 12);
        player.riptiding = false; // the animation ends, the impulse carries on
        // About 165 b/s: over the elytra ceiling alone, under elytra + riptide.
        fly(player, loc(2.5, 120.0, 0.5), 2.0, 70, 12);
        assertEquals(0, violations.count("ELYTRA"));
    }

    @Test
    @DisplayName("The riptide allowance ends with the riptide impulse")
    void riptideAllowanceExpires() throws Exception {
        RiptidePlayer player = glidingRiptidePlayer();
        clearGrace();
        player.riptiding = true;
        fly(player, loc(0.5, 120.0, 0.5), 1.0, 2, 12);
        player.riptiding = false;
        Thread.sleep(3100);
        fly(player, loc(2.5, 120.0, 0.5), 2.0, 80, 12);
        assertTrue(violations.count("ELYTRA") > 0, "a riptide three seconds ago explains nothing now");
    }
}
