package dev.boondock.bsanticheat.anticheat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Grace after flight ends, and setbacks that actually undo a violation.
 */
class FlightGraceAndSetbackTest extends ScenarioBase {

    private MovementChecker checker;

    @BeforeEach
    void setUpChecker() {
        checker = new MovementChecker(plugin, config, null, lang);
        checker.setViolationManager(violations);
        checker.setPistonTracker(new PistonTracker());
        floor(79, Material.STONE);
    }

    private double x = 0.5;

    private void step(PlayerMock player, double dx) {
        Location from = loc(x, 80.0, 0.5);
        x += dx;
        checker.onPlayerMove(new PlayerMoveEvent(player, from, loc(x, 80.0, 0.5)));
    }

    /** Sprint-flight momentum bleeding off under air drag, one event per tick. */
    private void coast(PlayerMock player) throws InterruptedException {
        double v = 1.0;
        for (int i = 0; i < 20; i++) {
            step(player, v);
            v *= 0.91;
            Thread.sleep(50);
        }
    }

    // ==================== flight ends ====================

    @Test
    @DisplayName("Without any flight before it, the same coast is SPEED")
    void coastWithoutFlightIsSpeed() throws InterruptedException {
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        step(player, 0.01);
        Thread.sleep(50);
        coast(player);
        assertTrue(violations.count("SPEED") > 0, "fixture check: the coast is over the cap");
    }

    @Test
    @DisplayName("Flight revoked mid-air leaves time for the momentum to bleed off")
    void revokedFlightGetsGrace() throws InterruptedException {
        PlayerMock player = player(0.5, 80.0, 0.5);
        player.setAllowFlight(true);
        player.setFlying(true);
        clearGrace();
        step(player, 0.01); // seen flying
        Thread.sleep(50);
        // /fly off or a region flag: no toggle event is fired for this.
        player.setFlying(false);
        player.setAllowFlight(false);
        coast(player);
        assertEquals(0, violations.count("SPEED"));
        assertEquals(0, violations.count("FLY"));
    }

    @Test
    @DisplayName("Toggling flight off grants the same grace")
    void toggledFlightGetsGrace() throws InterruptedException {
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        step(player, 0.01);
        Thread.sleep(50);
        checker.onToggleFlight(new PlayerToggleFlightEvent(player, false));
        coast(player);
        assertEquals(0, violations.count("SPEED"));
    }

    // ==================== setback ====================

    private void enableSetback() throws Exception {
        File file = new File(plugin.getDataFolder(), "config.yml");
        Files.writeString(file.toPath(), Files.readString(file.toPath())
                .replace("setback: false", "setback: true"));
        config.reload();
        assertTrue(config.punishmentsSetback(), "fixture check: setback is on");
    }

    @Test
    @DisplayName("A setback returns to the last clean position and grants no immunity")
    void setbackGoesToLastCleanPosition() throws Exception {
        enableSetback();
        // MockBukkit does not implement teleportAsync; on a server it ends in the same
        // teleport event a synchronous teleport fires.
        PlayerMock player = new PlayerMock(server, "SetbackTarget") {
            @Override
            public java.util.concurrent.CompletableFuture<Boolean> teleportAsync(
                    Location location, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause,
                    io.papermc.paper.entity.TeleportFlag... flags) {
                return java.util.concurrent.CompletableFuture.completedFuture(teleport(location, cause));
            }
        };
        server.addPlayer(player);
        player.setGameMode(org.bukkit.GameMode.SURVIVAL);
        player.setOp(false);
        player.teleport(loc(0.5, 80.0, 0.5));
        // The checker hears its own teleports, as it does on a server. Registered after the
        // player joined, so the join grace does not cover the scenario.
        server.getPluginManager().registerEvents(checker, plugin);
        clearGrace();
        step(player, 0.01);
        Thread.sleep(50);
        for (int i = 0; i < 5; i++) {
            step(player, 0.2);
            Thread.sleep(50);
        }
        Location lastClean = loc(x, 80.0, 0.5);
        // Speed run: every sample over the cap until the streak flags.
        for (int i = 0; i < 6 && violations.count("SPEED") == 0; i++) {
            step(player, 1.0);
            Thread.sleep(50);
        }
        assertTrue(violations.count("SPEED") > 0, "fixture check: the run flags");
        server.getScheduler().performTicks(2);
        assertEquals(lastClean.getX(), player.getLocation().getX(), 1e-6,
                "set back to where the run began, not one packet before the flag");

        // Continue speeding from the setback position straight away: the setback must not
        // have granted the one-second teleport immunity.
        int before = violations.count("SPEED");
        x = player.getLocation().getX();
        for (int i = 0; i < 8 && violations.count("SPEED") == before; i++) {
            step(player, 1.0);
            Thread.sleep(50);
        }
        assertTrue(violations.count("SPEED") > before, "a setback is not a free pass");
    }
}
