package dev.boondock.bsanticheat.anticheat;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * X-Ray detection driven through real block-break sequences.
 *
 * <p>The scenarios mirror the three live alerts and what caused them. The central pair is
 * "cave" versus "tunnel": both produce lots of ore and little stone, but only one of them is
 * knowledge the player should not have had.
 */
class XRayScenarioTest extends ScenarioBase {

    private XRayDetector detector;

    @BeforeEach
    void setUpDetector() {
        detector = new XRayDetector(plugin, config, null, lang);
        detector.setViolationManager(violations);
    }

    /**
     * Fill an area with solid rock so ore can be embedded in it. The extent must cover every
     * coordinate a scenario touches: ore placed outside it sits in open air, counts as
     * visible, and the scenario then proves nothing.
     */
    private void bedrock(Material rock) {
        for (int x = -6; x <= 70; x++) {
            for (int y = 38; y <= 44; y++) {
                for (int z = -6; z <= 20; z++) world.getBlockAt(x, y, z).setType(rock);
            }
        }
    }

    private void bedrock() {
        bedrock(Material.DEEPSLATE);
    }

    private void mine(PlayerMock player, int x, int y, int z, Material type) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(type);
        detector.onBlockBreak(new BlockBreakEvent(block, player));
        block.setType(Material.CAVE_AIR); // the block is gone afterwards
    }

    @Test
    @DisplayName("Diamonds taken from a tunnel through solid rock raise XRAY")
    void hiddenDiamondsAreCaught() {
        bedrock();
        PlayerMock player = player(0.5, 41.0, 0.5);
        // Ten buried diamonds from separate deposits, each reached by digging — no face was
        // open before the player opened it.
        for (int i = 0; i < 12; i++) {
            mine(player, i * 5, 40, 0, Material.DEEPSLATE_DIAMOND_ORE);
        }
        assertTrue(violations.count("XRAY_THRESHOLD") > 0,
                "buried ore from scattered deposits is what X-Ray reveals");
    }

    @Test
    @DisplayName("The same diamonds on open cave walls raise nothing")
    void visibleDiamondsAreIgnored() {
        bedrock();
        PlayerMock player = player(0.5, 41.0, 0.5);
        // The live case: a cave system. Every ore has an open face that nobody dug.
        for (int i = 0; i < 12; i++) {
            world.getBlockAt(i * 5, 41, 0).setType(Material.CAVE_AIR); // open above
            mine(player, i * 5, 40, 0, Material.DEEPSLATE_DIAMOND_ORE);
        }
        assertEquals(0, violations.count("XRAY_THRESHOLD"),
                "ore on a cave wall was seen, not located");
    }

    @Test
    @DisplayName("One large vein does not raise XRAY however many blocks it holds")
    void oneVeinIsNotEnough() {
        bedrock();
        PlayerMock player = player(0.5, 41.0, 0.5);
        // A contiguous run of buried diamond — more blocks than the threshold, one deposit.
        for (int i = 0; i < 14; i++) {
            mine(player, i, 40, 0, Material.DEEPSLATE_DIAMOND_ORE);
        }
        assertEquals(0, violations.count("XRAY_THRESHOLD"),
                "a single vein is a lucky find, not knowledge of where ore is");
    }

    @Test
    @DisplayName("Ore found while visibly digging is judged by ratio, not by count")
    void searchingPlayerIsNotCountFlagged() {
        bedrock();
        PlayerMock player = player(0.5, 41.0, 0.5);
        // Move enough stone to count as searching, then find scattered buried diamonds.
        for (int i = 0; i < 80; i++) {
            mine(player, i % 20, 42, i / 20, Material.DEEPSLATE);
        }
        for (int i = 0; i < 12; i++) {
            mine(player, i * 5, 40, 5, Material.DEEPSLATE_DIAMOND_ORE);
        }
        assertEquals(0, violations.count("XRAY_THRESHOLD"),
                "a player shifting that much rock is searching, which X-Ray removes the need for");
    }

    @Test
    @DisplayName("Nether mining counts netherrack as spoil")
    void netherrackCountsAsSpoil() {
        PlayerMock player = player(0.5, 41.0, 0.5);
        bedrock(Material.NETHERRACK);
        // Digging netherrack for debris used to count as no spoil at all, leaving the
        // threshold of 3 to fire on its own.
        for (int i = 0; i < 80; i++) {
            mine(player, i % 20, 42, i / 20, Material.NETHERRACK);
        }
        for (int i = 0; i < 5; i++) {
            mine(player, i * 5, 40, 5, Material.ANCIENT_DEBRIS);
        }
        assertEquals(0, violations.count("XRAY_THRESHOLD"),
                "netherrack is spoil, so debris hunting reads as searching");
    }

    @Test
    @DisplayName("Self-placed ore is ignored entirely")
    void selfPlacedOreIsIgnored() {
        bedrock();
        PlayerMock player = player(0.5, 41.0, 0.5);
        // An ore tower the player built themselves, then took down.
        for (int i = 0; i < 14; i++) {
            Block block = world.getBlockAt(0, 60 + i, 0);
            block.setType(Material.DEEPSLATE_DIAMOND_ORE);
            detector.onBlockPlace(new org.bukkit.event.block.BlockPlaceEvent(
                    block, block.getState(), world.getBlockAt(0, 59 + i, 0),
                    player.getInventory().getItemInMainHand(), player, true,
                    org.bukkit.inventory.EquipmentSlot.HAND));
        }
        for (int i = 0; i < 14; i++) {
            mine(player, 0, 60 + i, 0, Material.DEEPSLATE_DIAMOND_ORE);
        }
        assertEquals(0, violations.count("XRAY_THRESHOLD"),
                "breaking your own decoration is not mining");
    }
}
