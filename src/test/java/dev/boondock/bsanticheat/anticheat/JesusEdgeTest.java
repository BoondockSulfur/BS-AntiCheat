package dev.boondock.bsanticheat.anticheat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jesus at shorelines: water under the player's centre is not walking on water when a corner
 * of the hitbox rests on a block beside it.
 */
class JesusEdgeTest extends ScenarioBase {

    private MovementChecker checker;

    @BeforeEach
    void setUpChecker() throws Exception {
        File file = new File(plugin.getDataFolder(), "config.yml");
        Files.writeString(file.toPath(), Files.readString(file.toPath())
                .replace("jesus_detection: false", "jesus_detection: true"));
        config.reload();
        assertTrue(config.jesusDetectionEnabled(), "fixture check: the check is on");
        checker = new MovementChecker(plugin, config, null, lang);
        checker.setViolationManager(violations);
        // A pond at y=79 with a one-wide pier along x=1.
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                setBlock(x, 78, z, Material.STONE);
                setBlock(x, 79, z, x == 1 ? Material.OAK_PLANKS : Material.WATER);
            }
        }
    }

    /** Walk along +Z at the given X on the water surface. */
    private void walkAlong(PlayerMock player, double x) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            Location from = loc(x, 80.0, -4.5 + i * 0.2);
            Location to = loc(x, 80.0, -4.5 + (i + 1) * 0.2);
            player.teleport(from);
            checker.onPlayerMove(new PlayerMoveEvent(player, from, to));
            tick();
        }
    }

    @Test
    @DisplayName("Walking along the edge of a pier is not Jesus")
    void pierEdgeIsQuiet() throws InterruptedException {
        // Centre over water at x=0.8, the +X corners (x=1.1) over the planks.
        PlayerMock player = player(0.8, 80.0, -4.5);
        clearGrace();
        walkAlong(player, 0.8);
        assertEquals(0, violations.count("JESUS"));
    }

    @Test
    @DisplayName("Walking across open water is still Jesus")
    void openWaterIsCaught() throws InterruptedException {
        PlayerMock player = player(-3.5, 80.0, -4.5);
        clearGrace();
        walkAlong(player, -3.5);
        assertTrue(violations.count("JESUS") > 0);
    }
}
