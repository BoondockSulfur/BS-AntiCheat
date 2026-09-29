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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nuker and FastPlace: rate checks over a one-second window.
 *
 * <p>A single window over the limit must not flag, since a plugin breaking several blocks per
 * action, or a burst of place packets, can cross it without anyone cheating. Both require
 * consecutive windows — a cheat holds the rate up, a burst does not.
 */
class WorldScenarioTest extends ScenarioBase {

    private WorldChecker checker;
    private long clock = 1_000_000L;

    @BeforeEach
    void setUpChecker() {
        checker = new WorldChecker(plugin, config, null, lang);
        checker.setViolationManager(violations);
        checker.clock = () -> clock;
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
        // Enough one-second windows to satisfy the streak requirement several times over.
        for (int window = 0; window < config.nukerViolations() + 1; window++) {
            for (int i = 0; i < perWindow; i++) {
                breakBlock(player, i % 16, 70 - window, i / 16);
                clock += 1000L / perWindow;
            }
            clock += 50L;
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
                clock += 1000L / perWindow;
            }
            clock += 50L;
        }
        assertTrue(violations.count("FASTPLACE") > 0);
    }

    @Test
    @DisplayName("One large burst in a single tick is a single streak step")
    void oneLargeBurstCountsOnce() {
        // A vein miner breaking 78 blocks in one action must not clear and refill the window
        // three times over and reach the default streak of three on its own.
        PlayerMock player = player(0.5, 80.0, 0.5);
        for (int i = 0; i < 78; i++) breakBlock(player, i % 16, 70 - i / 64, (i / 16) % 4);
        assertEquals(0, violations.count("NUKER"), "one action is one burst");
    }

    @Test
    @DisplayName("Breaks a plugin fires from inside another break are not counted")
    void nestedPluginBreaksAreIgnored() {
        // The player breaks one block per second; a vein miner breaks 30 more from inside the
        // handling of each. Several seconds of that must not read as a nuker.
        PlayerMock player = player(0.5, 80.0, 0.5);
        for (int action = 0; action < config.nukerViolations() + 2; action++) {
            Block outer = world.getBlockAt(0, 70, action);
            outer.setType(Material.STONE);
            BlockBreakEvent outerEvent = new BlockBreakEvent(outer, player);
            checker.onBlockBreakEnter(outerEvent);
            for (int i = 0; i < 30; i++) {
                Block inner = world.getBlockAt(1 + i % 8, 69 - i / 8, action);
                inner.setType(Material.STONE);
                BlockBreakEvent innerEvent = new BlockBreakEvent(inner, player);
                checker.onBlockBreakEnter(innerEvent);
                checker.onBlockBreak(innerEvent);
            }
            checker.onBlockBreak(outerEvent);
            clock += 1100L;
        }
        assertEquals(0, violations.count("NUKER"), "the player broke one block per action");
    }

    @Test
    @DisplayName("FastBreak compares server ticks with slack")
    void fastBreakTickRule() {
        // Vanilla rejects breaks under 70% progress itself, so at the default tolerance a
        // legitimate dig, even with a couple of ticks of jitter, is never too fast.
        assertFalse(WorldChecker.isTooFast(18, 20, 0.7));
        assertFalse(WorldChecker.isTooFast(12, 20, 0.7), "12 + 2 slack is exactly 70%");
        assertTrue(WorldChecker.isTooFast(10, 20, 0.7));
        // A raised tolerance catches breaks between 70% and itself.
        assertTrue(WorldChecker.isTooFast(15, 20, 0.9));
        assertFalse(WorldChecker.isTooFast(17, 20, 0.9), "within two ticks of jitter");
    }
}
