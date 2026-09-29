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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @DisplayName("A busy redstone farm elsewhere does not evict a real piston elevator")
    void busyFarmDoesNotEvictElevator() {
        firePiston(10, 64, 10);
        // Far more pulses than the old global ring buffer held, in other chunks.
        for (int i = 0; i < 400; i++) {
            firePiston(200 + i * 10, 64, 0);
        }
        assertTrue(tracker.wasPushedRecently(new Location(world, 10, 64, 10)),
                "a push inside its grace window must survive unrelated traffic");
    }

    @Test
    @DisplayName("A clock circuit cannot grow one chunk's bucket without bound")
    void chunkBucketStaysBounded() {
        for (int i = 0; i < PistonTracker.CHUNK_CAPACITY * 3; i++) {
            firePiston(4, 64, 4);
        }
        assertTrue(tracker.size() <= PistonTracker.CHUNK_CAPACITY,
                "a single chunk is capped at " + PistonTracker.CHUNK_CAPACITY);
        assertTrue(tracker.wasPushedRecently(new Location(world, 4, 64, 4)));
    }

    @Test
    @DisplayName("Expired pushes and empty chunk buckets are swept away")
    void expiredBucketsAreSwept() throws InterruptedException {
        firePiston(10, 64, 10);
        firePiston(100, 64, 100);
        Thread.sleep(PistonTracker.GRACE_MS + 50);
        tracker.sweepNow();
        assertEquals(0, tracker.bucketCount());
        assertFalse(tracker.wasPushedRecently(new Location(world, 10, 64, 10)));
    }

    @Test
    @DisplayName("A horizontal push excuses horizontal displacement only")
    void horizontalPushIsNotVertical() {
        firePiston(10, 64, 10); // pushes EAST
        Location beside = new Location(world, 12, 64, 12);
        assertTrue(tracker.horizontalPushNear(beside));
        assertFalse(tracker.verticalPushNear(beside),
                "a sideways push next to the player does not hold them up");
    }

    @Test
    @DisplayName("A piston elevator excuses vertical displacement")
    void verticalPushIsVertical() {
        Block piston = world.getBlockAt(10, 62, 10);
        piston.setType(Material.PISTON);
        Block pushed = world.getBlockAt(10, 63, 10);
        pushed.setType(Material.STONE);
        tracker.onPistonExtend(new BlockPistonExtendEvent(piston, List.of(pushed), BlockFace.UP));
        Location rider = new Location(world, 10.5, 65, 10.5);
        assertTrue(tracker.verticalPushNear(rider));
        assertFalse(tracker.horizontalPushNear(rider));
    }

    @Test
    @DisplayName("A floor moved sideways under the feet still counts as vertical support")
    void movingFloorSupportsRider() {
        firePiston(10, 63, 10); // the moved block ends at (11, 63, 10)
        Location rider = new Location(world, 11.5, 64, 10.5);
        assertTrue(tracker.verticalPushNear(rider));
    }
}
