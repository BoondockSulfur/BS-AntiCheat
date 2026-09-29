package dev.boondock.bsanticheat.anticheat;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Shape of a player's recent digging, used as EXONERATING evidence by the X-Ray checks.
 *
 * <p>The counting checks cannot tell a lucky miner from someone who knew where to walk; a
 * player who strip-mines a lot of stone and clears a few thick veins can reach the ore
 * thresholds honestly. What separates the two is not how much they dug but the SHAPE of it:
 * how tightly the digging holds one Y level, how much of it is corridor, and whether the ore
 * came out of the band the corridors run through.
 *
 * <p>Strip mining is unmistakable: a single Y level, straight corridors, and ore that comes
 * out of the same narrow band the corridors run through. Nothing about a count can see that.
 *
 * <p>These are vetoes and only vetoes. They can remove alerts, never raise one, so they
 * cannot introduce a false positive; there is no confirmed X-Ray sample to calibrate an
 * incriminating signal against.
 *
 * <p>The ore-in-band requirement is what keeps the veto from becoming a hiding place: a
 * player who strip-mines honestly for ten minutes and then takes ore from five levels above
 * and below still has a tight corridor profile, and without it the veto would cover exactly
 * the detours worth hiding.
 */
final class MiningProfile {

    private MiningProfile() {}

    /** One recorded stone break: when, and where. */
    record StoneBreak(long time, int x, int y, int z) {}

    /** Population standard deviation of the Y coordinates, or -1 for an empty sample. */
    static double yStdDev(Collection<StoneBreak> stone) {
        int n = stone.size();
        if (n == 0) return -1;
        double mean = 0;
        for (StoneBreak s : stone) mean += s.y();
        mean /= n;
        double sq = 0;
        for (StoneBreak s : stone) {
            double d = s.y() - mean;
            sq += d * d;
        }
        return Math.sqrt(sq / n);
    }

    /** The most frequently dug Y level, or {@link Integer#MIN_VALUE} for an empty sample. */
    static int modalY(Collection<StoneBreak> stone) {
        if (stone.isEmpty()) return Integer.MIN_VALUE;
        Map<Integer, Integer> counts = new HashMap<>();
        int best = Integer.MIN_VALUE;
        int bestCount = -1;
        for (StoneBreak s : stone) {
            int c = counts.merge(s.y(), 1, Integer::sum);
            if (c > bestCount) {
                bestCount = c;
                best = s.y();
            }
        }
        return best;
    }

    /**
     * Fraction of the sample that sits inside a corridor — a block with dug neighbours on
     * BOTH sides along the same axis, which is what a tunnel is and a cave is not.
     */
    static double corridorFraction(Collection<StoneBreak> stone) {
        int n = stone.size();
        if (n == 0) return 0;
        Set<Long> dug = new HashSet<>(n * 2);
        for (StoneBreak s : stone) dug.add(key(s.x(), s.y(), s.z()));
        int inCorridor = 0;
        for (StoneBreak s : stone) {
            boolean alongX = dug.contains(key(s.x() + 1, s.y(), s.z()))
                    && dug.contains(key(s.x() - 1, s.y(), s.z()));
            boolean alongZ = dug.contains(key(s.x(), s.y(), s.z() + 1))
                    && dug.contains(key(s.x(), s.y(), s.z() - 1));
            if (alongX || alongZ) inCorridor++;
        }
        return (double) inCorridor / n;
    }

    /**
     * True when the recent digging looks like strip mining AND the ore in question came out
     * of the level it runs through.
     *
     * @param stone       recent stone breaks (positions), the profile window
     * @param oreYs       Y coordinates of the ore being judged
     * @param minSample   below this many stone breaks the shape says nothing and the veto
     *                    stands down — an empty profile must never excuse anything
     * @param maxYStdDev  how tightly the digging must hold one level
     * @param minCorridor how much of it must be corridor rather than open excavation
     * @param maxOreBand  how far the ore may sit from the modal digging level
     */
    static boolean looksLikeStripMining(Collection<StoneBreak> stone, Collection<Integer> oreYs,
                                        int minSample, double maxYStdDev,
                                        double minCorridor, int maxOreBand) {
        if (stone.size() < minSample) return false;
        if (yStdDev(stone) > maxYStdDev) return false;
        if (corridorFraction(stone) < minCorridor) return false;
        int level = modalY(stone);
        for (int oreY : oreYs) {
            // One ore outside the band is enough to withdraw the veto. The whole point is
            // that a strip miner's finds lie in their corridor; a detour does not.
            if (Math.abs(oreY - level) > maxOreBand) return false;
        }
        return !oreYs.isEmpty();
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }
}
