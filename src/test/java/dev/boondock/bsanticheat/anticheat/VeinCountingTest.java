package dev.boondock.bsanticheat.anticheat;

import org.bukkit.Location;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * X-Ray deposit clustering.
 *
 * <p>The per-ore thresholds used to count ore BLOCKS, which cannot tell one thick vein from a
 * dozen scattered finds — copper and redstone veins run past 20 blocks and a vein-miner tool
 * takes a whole vein in one action, so both crossed thresholds without anyone knowing
 * anything they should not. Counting deposits is what makes the threshold mean something.
 *
 * <p>The last case replays real coordinates from the alerts of 2026-08-15, to hold the line
 * that narrowing the evidence did not blunt the detection.
 */
class VeinCountingTest {

    /** Ore position. World is null throughout: sameVein only compares worlds when both exist. */
    private static Location at(int x, int y, int z) {
        return new Location(null, x, y, z);
    }

    private static List<Location> locations(int[][] coords) {
        List<Location> out = new ArrayList<>();
        for (int[] c : coords) out.add(at(c[0], c[1], c[2]));
        return out;
    }

    @Test
    @DisplayName("Nothing and single blocks")
    void degenerateCases() {
        assertEquals(0, XRayDetector.countVeins(new ArrayList<>()));
        assertEquals(1, XRayDetector.countVeins(locations(new int[][]{{0, 0, 0}})));
    }

    @Test
    @DisplayName("One contiguous vein counts once, however big")
    void contiguousVeinIsOne() {
        // A 3x2x2 block of ore — the shape a large copper or redstone vein takes.
        List<Location> pts = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) pts.add(at(x, y, z));
            }
        }
        assertEquals(12, pts.size());
        assertEquals(1, XRayDetector.countVeins(pts));
    }

    @Test
    @DisplayName("A vein with a gap in it still counts once")
    void diagonalChainIsOne() {
        // Linkage is transitive, so a winding vein is one deposit whatever its shape.
        assertEquals(1, XRayDetector.countVeins(locations(new int[][]{
                {0, 0, 0}, {2, 0, 0}, {4, 0, 0}, {6, 0, 0}, {8, 1, 1}
        })));
    }

    @Test
    @DisplayName("Deposits far apart count separately")
    void separateVeins() {
        assertEquals(3, XRayDetector.countVeins(locations(new int[][]{
                {0, 0, 0}, {1, 0, 0},        // vein A
                {50, 0, 0}, {51, 0, 0},      // vein B
                {100, 20, 60}                // vein C
        })));
    }

    @Test
    @DisplayName("Exactly at the link distance = same vein, one beyond = separate")
    void linkDistanceBoundary() {
        assertEquals(1, XRayDetector.countVeins(locations(new int[][]{{0, 0, 0}, {2, 2, 2}})));
        assertEquals(2, XRayDetector.countVeins(locations(new int[][]{{0, 0, 0}, {3, 0, 0}})));
    }

    @Test
    @DisplayName("sameVein treats a null world as 'do not compare'")
    void nullWorldsCompare() {
        assertTrue(XRayDetector.sameVein(at(0, 0, 0), at(1, 0, 0)));
    }

    @Test
    @DisplayName("Live case 2026-08-15: the alerts still fire")
    void realDiamondAlertStillCounts() {
        // Diamond ore broken in the 60s window before the 10:35:27 alert, exactly as
        // CoreProtect recorded it. Four separate deposits — over xray_min_veins (3), so this
        // alert survives the change. A regression that let it fall to two would mean the
        // clustering had started merging genuinely separate finds.
        int veins = XRayDetector.countVeins(locations(new int[][]{
                {111, -35, 2307}, {111, -36, 2307},
                {117, -35, 2314}, {117, -35, 2315}, {117, -36, 2314},
                {117, -36, 2315}, {116, -36, 2314}, {116, -36, 2315},
                {106, -31, 2219}, {105, -32, 2220},
                {66, -34, 2249}
        }));
        assertEquals(4, veins);
        assertTrue(veins >= 3, "live alert must still cross the vein requirement");
    }

    @Test
    @DisplayName("A vein-miner haul stays one deposit")
    void veinMinerHaulIsOneDeposit() {
        // A tool that takes a whole vein in one action yields the block count of a big find
        // but the deposit count of a single one — which is the honest reading of it.
        List<Location> pts = new ArrayList<>();
        for (int i = 0; i < 10; i++) pts.add(at(100 + i, 12, 40));
        assertEquals(10, pts.size());
        assertEquals(1, XRayDetector.countVeins(pts));
    }
}
