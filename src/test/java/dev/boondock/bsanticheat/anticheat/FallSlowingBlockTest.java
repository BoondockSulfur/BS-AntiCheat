package dev.boondock.bsanticheat.anticheat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Fly-check exemption for blocks that slow a descent.
 *
 * <p>From a live false positive: a player standing in a mineshaft was flagged for "hovering"
 * twice. Cobwebs slow a fall below the rate the hover check counts as falling, so the counter
 * kept climbing while the player was in fact sinking — and the support scan only looks
 * downward, so the web holding them at body height was never seen. Honey walls do the same
 * thing sideways.
 *
 * <p>The last two cases guard the other direction: an actual hover must still be visible.
 */
class FallSlowingBlockTest {

    private ServerMock server;
    private World world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("caves");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Feet position at the centre of a block. */
    private Location feet(int x, int y, int z) {
        return new Location(world, x + 0.5, y, z + 0.5);
    }

    private void set(int x, int y, int z, Material material) {
        world.getBlockAt(x, y, z).setType(material);
    }

    @Test
    @DisplayName("Standing in a cobweb is exempt")
    void insideCobweb() {
        set(0, 60, 0, Material.COBWEB);
        assertTrue(MovementChecker.isInFallSlowingBlock(feet(0, 60, 0)));
    }

    @Test
    @DisplayName("A cobweb at head height is exempt — the support scan never looks up")
    void cobwebAtHeadHeight() {
        // The live case: web holding the player at body height, cave below.
        set(0, 61, 0, Material.COBWEB);
        assertTrue(MovementChecker.isInFallSlowingBlock(feet(0, 60, 0)));
    }

    @Test
    @DisplayName("Powder snow is exempt")
    void powderSnow() {
        set(0, 60, 0, Material.POWDER_SNOW);
        assertTrue(MovementChecker.isInFallSlowingBlock(feet(0, 60, 0)));
    }

    @Test
    @DisplayName("A honey wall beside the player is exempt")
    void honeyWallBeside() {
        set(1, 60, 0, Material.HONEY_BLOCK);
        assertTrue(MovementChecker.isInFallSlowingBlock(feet(0, 60, 0)));
    }

    @Test
    @DisplayName("A honey wall at head height counts too")
    void honeyWallAtHeadHeight() {
        set(0, 61, 1, Material.HONEY_BLOCK);
        assertTrue(MovementChecker.isInFallSlowingBlock(feet(0, 60, 0)));
    }

    @Test
    @DisplayName("Open air is NOT exempt — a real hover stays detectable")
    void openAirIsNotExempt() {
        assertFalse(MovementChecker.isInFallSlowingBlock(feet(0, 60, 0)));
    }

    @Test
    @DisplayName("Ordinary blocks are NOT exempt")
    void ordinaryBlocksAreNotExempt() {
        set(0, 60, 0, Material.AIR);
        set(0, 59, 0, Material.STONE);   // standing on stone
        set(1, 60, 0, Material.OAK_LOG); // a wall that slows nothing
        assertFalse(MovementChecker.isInFallSlowingBlock(feet(0, 60, 0)));
    }

    @Test
    @DisplayName("Honey more than one block away does not exempt")
    void distantHoneyDoesNotExempt() {
        set(3, 60, 0, Material.HONEY_BLOCK);
        assertFalse(MovementChecker.isInFallSlowingBlock(feet(0, 60, 0)));
    }
}
