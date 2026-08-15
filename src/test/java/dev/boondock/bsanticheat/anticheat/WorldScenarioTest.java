package dev.boondock.bsanticheat.anticheat;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nuker and FastPlace: rate checks over a one-second window.
 *
 * <p>Both used to flag on a single window over the limit, which a plugin breaking several
 * blocks per action, or a burst of place packets, can cross without anyone cheating. They now
 * require consecutive windows — a cheat holds the rate up, a burst does not.
 */
class WorldScenarioTest extends ScenarioBase {

    private WorldChecker checker;

    @BeforeEach
    void setUpChecker() {
        checker = new WorldChecker(plugin, config, null, lang);
        checker.setViolationManager(violations);
    }

    private void breakBlock(PlayerMock player, int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.STONE);
        checker.onBlockBreak(new BlockBreakEvent(block, player));
    }

    private void placeBlock(PlayerMock player, int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        Block against = world.getBlockAt(x, y - 1, z);
        against.setType(Material.STONE);
        checker.onBlockPlace(new BlockPlaceEvent(block, block.getState(), against,
                player.getInventory().getItemInMainHand(), player, true, EquipmentSlot.HAND));
    }

    @Test
    @DisplayName("A single burst over the limit does not flag NUKER")
    void singleBurstIsForgiven() {
        // A vein miner or multi-block tool empties one window in a single action.
        PlayerMock player = player(0.5, 80.0, 0.5);
        for (int i = 0; i < config.nukerMaxBreaksPerSecond() + 5; i++) {
            breakBlock(player, i % 16, 70, i / 16);
        }
        assertEquals(0, violations.count("NUKER"), "one bundled window is not evidence");
    }

    @Test
    @DisplayName("Sustained breaking well over the limit does flag NUKER")
    void sustainedRateIsCaught() {
        PlayerMock player = player(0.5, 80.0, 0.5);
        int perWindow = config.nukerMaxBreaksPerSecond() + 5;
        // Enough windows to satisfy the streak requirement several times over.
        for (int window = 0; window < config.nukerViolations() + 1; window++) {
            for (int i = 0; i < perWindow; i++) {
                breakBlock(player, i % 16, 70 - window, i / 16);
            }
        }
        assertTrue(violations.count("NUKER") > 0, "a held-up rate must still be caught");
    }

    @Test
    @DisplayName("Ordinary mining raises nothing")
    void ordinaryMiningIsQuiet() {
        PlayerMock player = player(0.5, 80.0, 0.5);
        for (int i = 0; i < 8; i++) breakBlock(player, i, 70, 0);
        assertEquals(0, violations.count("NUKER"));
    }

    @Test
    @DisplayName("A single burst over the limit does not flag FASTPLACE")
    void placeBurstIsForgiven() {
        PlayerMock player = player(0.5, 80.0, 0.5);
        for (int i = 0; i < config.fastPlaceMaxPerSecond() + 3; i++) {
            placeBlock(player, i % 16, 72, i / 16);
        }
        assertEquals(0, violations.count("FASTPLACE"));
    }

    @Test
    @DisplayName("Sustained placing does flag FASTPLACE")
    void sustainedPlacingIsCaught() {
        PlayerMock player = player(0.5, 80.0, 0.5);
        int perWindow = config.fastPlaceMaxPerSecond() + 3;
        for (int window = 0; window < config.fastPlaceViolations() + 1; window++) {
            for (int i = 0; i < perWindow; i++) {
                placeBlock(player, i % 16, 72 + window, i / 16);
            }
        }
        assertTrue(violations.count("FASTPLACE") > 0);
    }
}
