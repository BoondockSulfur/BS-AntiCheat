package dev.boondock.bsanticheat.anticheat;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The distinction the whole X-Ray detector now rests on: was the ore visible, or buried?
 *
 * <p>X-Ray tells someone where ore is that they cannot see, and acting on that means digging
 * to it. Clearing an open cave means taking ore off walls that were visible all along — which
 * produces the same "lots of ore, hardly any stone" statistics, in fact more extreme ones,
 * because nothing has to be dug at all. Three live alerts came from exactly that.
 *
 * <p>The tunnel cases below are the other half: a face the player just broke open must NOT
 * count as visibility, or digging straight to concealed ore would excuse itself.
 */
class OreVisibilityTest {

    private ServerMock server;
    private World world;
    private Map<String, Long> recentlyBroken;
    private long now;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("mine");
        recentlyBroken = new HashMap<>();
        now = 1_000_000L;
        sealRock();
    }

    /** Fill the area under test with solid rock again. */
    private void sealRock() {
        recentlyBroken.clear();
        for (int x = -3; x <= 3; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -3; z <= 3; z++) {
                    world.getBlockAt(x, 40 + y, z).setType(Material.DEEPSLATE);
                }
            }
        }
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Block ore(int x, int y, int z) {
        Block b = world.getBlockAt(x, y, z);
        b.setType(Material.DEEPSLATE_DIAMOND_ORE);
        return b;
    }

    /** cutoff is "anything broken at or after this counts as freshly opened by the player". */
    private boolean visible(Block block) {
        return XRayDetector.wasVisible(block, recentlyBroken, now - 60_000L);
    }

    private void brokenJustNow(int x, int y, int z) {
        world.getBlockAt(x, y, z).setType(Material.CAVE_AIR);
        recentlyBroken.put(XRayDetector.getLocationKey(world.getBlockAt(x, y, z).getLocation()), now);
    }

    @Test
    @DisplayName("Ore sealed in rock is not visible")
    void buriedOreIsHidden() {
        assertFalse(visible(ore(0, 40, 0)));
    }

    @Test
    @DisplayName("Ore on an open cave wall is visible")
    void oreOnCaveWallIsVisible() {
        // The live case: cave air on one side, nobody dug it.
        world.getBlockAt(1, 40, 0).setType(Material.CAVE_AIR);
        assertTrue(visible(ore(0, 40, 0)));
    }

    @Test
    @DisplayName("Ore exposed to water or lava is visible")
    void liquidsCountAsOpen() {
        world.getBlockAt(0, 41, 0).setType(Material.LAVA);
        assertTrue(visible(ore(0, 40, 0)));

        world.getBlockAt(0, 41, 0).setType(Material.DEEPSLATE);
        world.getBlockAt(0, 39, 0).setType(Material.WATER);
        assertTrue(visible(ore(0, 40, 0)));
    }

    @Test
    @DisplayName("A tunnel the player just dug does NOT make the ore visible")
    void freshTunnelDoesNotExcuse() {
        // This is the X-Ray behaviour itself: dig to concealed ore, then break it.
        brokenJustNow(1, 40, 0);
        assertFalse(visible(ore(0, 40, 0)));
    }

    @Test
    @DisplayName("An old opening does count — the ore was on view")
    void oldOpeningCounts() {
        world.getBlockAt(1, 40, 0).setType(Material.CAVE_AIR);
        recentlyBroken.put(
                XRayDetector.getLocationKey(world.getBlockAt(1, 40, 0).getLocation()),
                now - 120_000L); // two minutes ago, outside the window
        assertTrue(visible(ore(0, 40, 0)));
    }

    @Test
    @DisplayName("One fresh face and one natural face: still visible")
    void anyNaturalFaceIsEnough() {
        brokenJustNow(1, 40, 0);                              // dug by the player
        world.getBlockAt(-1, 40, 0).setType(Material.CAVE_AIR); // open all along
        assertTrue(visible(ore(0, 40, 0)));
    }

    @Test
    @DisplayName("Every face checked, not just the sides")
    void allSixFaces() {
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] f : faces) {
            sealRock(); // start from solid rock each time
            world.getBlockAt(f[0], 40 + f[1], f[2]).setType(Material.CAVE_AIR);
            assertTrue(visible(ore(0, 40, 0)),
                    "face " + f[0] + "," + f[1] + "," + f[2] + " should expose the ore");
        }
    }
}
