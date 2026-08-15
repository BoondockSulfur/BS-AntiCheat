package dev.boondock.bsanticheat.anticheat;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Proves the mock server can do what the movement and X-Ray tests need of it: place a
 * player, set blocks around them and read those blocks back. Everything else builds on this,
 * so when the harness breaks it should be obvious here rather than inside a check's test.
 */
class ServerHarnessTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("A world with a player exists")
    void worldAndPlayer() {
        World world = server.addSimpleWorld("test");
        assertNotNull(world);
        Player player = server.addPlayer();
        assertNotNull(player);
        assertNotNull(player.getLocation());
    }

    @Test
    @DisplayName("Blocks can be set and read back")
    void blocksAreWritable() {
        World world = server.addSimpleWorld("test");
        Block block = world.getBlockAt(10, 64, 10);
        block.setType(Material.DEEPSLATE_DIAMOND_ORE);
        assertEquals(Material.DEEPSLATE_DIAMOND_ORE, world.getBlockAt(10, 64, 10).getType());

        // Neighbour access — what the ore-exposure test walks.
        world.getBlockAt(11, 64, 10).setType(Material.CAVE_AIR);
        assertEquals(Material.CAVE_AIR, block.getRelative(1, 0, 0).getType());
    }
}
