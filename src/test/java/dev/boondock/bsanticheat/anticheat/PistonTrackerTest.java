package dev.boondock.bsanticheat.anticheat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Piston displacement tracking.
 *
 * <p>A piston moves a player without applying velocity — it fires no
 * {@code PlayerVelocityEvent} — so the knockback immunity the other checks rely on never
 * engages, and the player simply being somewhere else next tick reads as Speed, as vertical
 * Fly, or as walking with a container open. Piston elevators and door mechanisms do this
 * constantly.
 */
class PistonTrackerTest {

    private ServerMock server;
    private World world;
    private PistonTracker tracker;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("redstone");
        tracker = new PistonTracker();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Fire a piston at the given position, pushing one block. */
    private void firePiston(int x, int y, int z) {
        Block piston = world.getBlockAt(x, y, z);
        piston.setType(Material.PISTON);
        Block pushed = world.getBlockAt(x + 1, y, z);
        pushed.setType(Material.STONE);
        tracker.onPistonExtend(
                new BlockPistonExtendEvent(piston, List.of(pushed), BlockFace.EAST));
    }

    @Test
    @DisplayName("Nothing is exempt before any piston fires")
    void quietByDefault() {
        assertFalse(tracker.wasPushedRecently(new Location(world, 0, 64, 0)));
    }

    @Test
    @DisplayName("A player next to a firing piston is covered")
    void playerBesidePiston() {
        firePiston(10, 64, 10);
        assertTrue(tracker.wasPushedRecently(new Location(world, 10, 64, 10)));
        assertTrue(tracker.wasPushedRecently(new Location(world, 12, 65, 11)));
    }

    @Test
    @DisplayName("A player well away from it is not")
    void playerFarAway() {
        firePiston(10, 64, 10);
        assertFalse(tracker.wasPushedRecently(new Location(world, 40, 64, 10)));
        assertFalse(tracker.wasPushedRecently(new Location(world, 10, 90, 10)));
    }

    @Test
    @DisplayName("A piston in another world does not cover anyone here")
    void otherWorldIsSeparate() {
        World other = server.addSimpleWorld("elsewhere");
        firePiston(10, 64, 10);
        assertFalse(tracker.wasPushedRecently(new Location(other, 10, 64, 10)));
    }

    @Test
    @DisplayName("A null location is handled")
    void nullLocation() {
        firePiston(10, 64, 10);
        assertFalse(tracker.wasPushedRecently(null));
    }

    @Test
    @DisplayName("A clock circuit cannot grow the buffer without bound")
    void bufferStaysBounded() {
        // Far more pulses than the capacity, at spread-out positions.
        for (int i = 0; i < 400; i++) {
            firePiston(i * 10, 64, 0);
        }
        // The most recent pulse is still known...
        assertTrue(tracker.wasPushedRecently(new Location(world, 3990, 64, 0)));
        // ...while the oldest has been evicted, which is what keeps memory flat.
        assertFalse(tracker.wasPushedRecently(new Location(world, 0, 64, 0)));
    }
}
