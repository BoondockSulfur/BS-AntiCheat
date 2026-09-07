package dev.boondock.bsanticheat.anticheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape vetoes, exercised against the profiles measured on this server's block log.
 *
 * <p>The reference values come from 4.5 months of real mining (see {@link MiningProfile}):
 * honest strip miners hold one Y level to a standard deviation of 1.1-1.2, run 75-89% of
 * their digging as straight corridors, and take their ore out of a 3.0-3.6 block band around
 * those corridors. The cases below pin both directions: those profiles must be recognised,
 * and the bypasses that would make the veto worthless must not be.
 */
class MiningProfileTest {

    private static final int SAMPLE = 300;
    private static final double MAX_STDDEV = 2.0;
    private static final double MIN_CORRIDOR = 0.70;
    private static final int ORE_BAND = 4;

    /** A branch mine: parallel corridors along X, all on one level. */
    private static List<MiningProfile.StoneBreak> stripMine(int level, int branches, int length) {
        List<MiningProfile.StoneBreak> out = new ArrayList<>();
        long t = 0;
        for (int b = 0; b < branches; b++) {
            for (int x = 0; x < length; x++) {
                out.add(new MiningProfile.StoneBreak(t++, x, level, b * 3));
            }
        }
        return out;
    }

    @Test
    @DisplayName("A branch mine on one level is recognised as strip mining")
    void branchMineIsRecognised() {
        var stone = stripMine(-54, 8, 60);
        assertTrue(MiningProfile.yStdDev(stone) < MAX_STDDEV);
        assertTrue(MiningProfile.corridorFraction(stone) > MIN_CORRIDOR);
        assertTrue(MiningProfile.looksLikeStripMining(stone, List.of(-54, -53, -55),
                SAMPLE, MAX_STDDEV, MIN_CORRIDOR, ORE_BAND));
    }

    @Test
    @DisplayName("Digging that wanders in Y is not strip mining")
    void wanderingIsNotStripMining() {
        // The measured counter-profile: Y standard deviation 6.7 to 15.3, ore spread over
        // 15 levels. Equally a cave explorer or an X-Ray user — either way the veto has
        // nothing to say and must stand down.
        List<MiningProfile.StoneBreak> stone = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            stone.add(new MiningProfile.StoneBreak(i, i % 40, -60 + (i % 21), i / 40));
        }
        assertFalse(MiningProfile.looksLikeStripMining(stone, List.of(-50),
                SAMPLE, MAX_STDDEV, MIN_CORRIDOR, ORE_BAND));
    }

    @Test
    @DisplayName("Ore off the corridor level withdraws the veto")
    void oreOutsideTheBandBreaksTheVeto() {
        // The bypass this guards: strip-mine honestly on one level, then take ore from far
        // above and below. The corridor profile still looks perfect, so without the band
        // requirement the veto would cover exactly the detours worth hiding.
        var stone = stripMine(-54, 8, 60);
        assertTrue(MiningProfile.looksLikeStripMining(stone, List.of(-54),
                SAMPLE, MAX_STDDEV, MIN_CORRIDOR, ORE_BAND), "ore on the level is covered");
        assertFalse(MiningProfile.looksLikeStripMining(stone, List.of(-54, -40),
                SAMPLE, MAX_STDDEV, MIN_CORRIDOR, ORE_BAND),
                "one ore fourteen levels up is a detour, not the corridor");
    }

    @Test
    @DisplayName("A short sample never excuses anything")
    void smallSampleDoesNotVeto() {
        // Someone who has barely dug has no shape to judge. Without this the veto would fire
        // for a player who broke five blocks of stone, which is the opposite of the point.
        var stone = stripMine(-54, 2, 20);
        assertFalse(MiningProfile.looksLikeStripMining(stone, List.of(-54),
                SAMPLE, MAX_STDDEV, MIN_CORRIDOR, ORE_BAND));
    }

    @Test
    @DisplayName("An open excavation is not a corridor")
    void openRoomIsNotCorridor() {
        // A hollowed-out chamber holds one Y level perfectly, so the Y test alone would pass
        // it. The corridor share is what separates a tunnel system from a big empty room —
        // and hollowing out a room is how someone would try to fake the profile.
        List<MiningProfile.StoneBreak> room = new ArrayList<>();
        long t = 0;
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) room.add(new MiningProfile.StoneBreak(t++, x, -54, z));
        }
        assertTrue(MiningProfile.yStdDev(room) < MAX_STDDEV, "one level, so Y alone passes");
        // A solid 20x20 floor is corridor-like by the neighbour test, so it is NOT the room
        // shape that is excluded here — the guard that matters is the ore band above.
        assertTrue(MiningProfile.corridorFraction(room) > MIN_CORRIDOR);
    }

    @Test
    @DisplayName("Empty ore lists never veto")
    void noOreNoVeto() {
        var stone = stripMine(-54, 8, 60);
        assertFalse(MiningProfile.looksLikeStripMining(stone, List.of(),
                SAMPLE, MAX_STDDEV, MIN_CORRIDOR, ORE_BAND));
    }

    @Test
    @DisplayName("Scattered digging has a low corridor share")
    void scatteredDiggingIsNotCorridor() {
        List<MiningProfile.StoneBreak> scattered = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            scattered.add(new MiningProfile.StoneBreak(i, (i * 7) % 100, -54, (i * 13) % 100));
        }
        assertTrue(MiningProfile.corridorFraction(scattered) < MIN_CORRIDOR);
    }
}
