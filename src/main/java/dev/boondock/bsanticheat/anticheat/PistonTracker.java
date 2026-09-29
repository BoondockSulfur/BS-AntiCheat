package dev.boondock.bsanticheat.anticheat;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Remembers where pistons recently moved, so the movement checks can tell a shoved player
 * apart from a cheating one.
 *
 * <p>A piston displaces players without applying velocity: it fires no
 * {@code PlayerVelocityEvent}, so the knockback immunity every other check relies on never
 * engages. The player is simply somewhere else next tick — which reads as Speed, as vertical
 * Fly, or as walking with a container open. Piston elevators, flying machines and door
 * mechanisms all produce it.
 *
 * <p>Storage is per world and per chunk, and entries leave by age rather than by count: a
 * busy redstone farm in one place must not push a real piston elevator elsewhere out of
 * memory inside its grace window. Each chunk still has a hard cap so a clock circuit cannot
 * grow its bucket without limit, and emptied buckets are swept away periodically.
 *
 * <p>Each push also records its axis. A piston moves what it pushes by at most one block, and
 * only along that axis, so the checks ask a narrower question than "was there a piston
 * nearby": horizontal speed is only excused by a horizontal push, vertical motion only by a
 * vertical push or by footing that a piston is moving — a clock circuit beside a cheater no
 * longer covers every check at once.
 *
 * <p>Cost is kept off the piston event, which can fire many times a second: the handler only
 * appends to the chunk's bucket. The distance search runs in the checkers' would-flag paths.
 */
public final class PistonTracker implements Listener {

    /** How long a piston movement keeps its exemption. */
    static final long GRACE_MS = 1500L;
    /** How far from the recorded position a player is still considered shoved. */
    private static final double RADIUS = 4.0;
    private static final double RADIUS_SQ = RADIUS * RADIUS;
    /**
     * Displacement a piston can add per tick, with margin. A moving block advances half a
     * block per tick and shoves entities in its path by the same amount; slime and honey
     * carry them the full block.
     */
    public static final double MAX_PUSH_PER_TICK = 1.1;
    /** Hard cap per chunk bucket — far above what a chunk produces within {@link #GRACE_MS}. */
    static final int CHUNK_CAPACITY = 1024;
    /** Every this many recorded pushes, empty buckets are swept out of the index. */
    private static final int SWEEP_INTERVAL = 256;

    enum Axis { X, Y, Z }

    private record Push(double x, double y, double z, Axis axis, long time) {}

    // world -> chunk key -> pushes in arrival order (oldest first)
    private final Map<UUID, Map<Long, ArrayDeque<Push>>> pushes = new ConcurrentHashMap<>();
    private final AtomicInteger sinceSweep = new AtomicInteger();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        record(event.getBlock(), event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        record(event.getBlock(), event.getBlocks(), event.getDirection());
    }

    private static Axis axisOf(BlockFace face) {
        if (face == null) return Axis.Y;
        if (face.getModY() != 0) return Axis.Y;
        return face.getModX() != 0 ? Axis.X : Axis.Z;
    }

    /**
     * Record the piston itself and the far end of what it moved. Two points rather than every
     * moved block: a 12-block push is covered by the two ends plus {@link #RADIUS}.
     */
    private void record(Block piston, List<Block> moved, BlockFace direction) {
        long now = System.currentTimeMillis();
        Axis axis = axisOf(direction);
        add(piston.getLocation(), axis, now);
        if (!moved.isEmpty()) {
            add(moved.get(moved.size() - 1).getLocation(), axis, now);
        }
        if (sinceSweep.incrementAndGet() >= SWEEP_INTERVAL) {
            sinceSweep.set(0);
            sweep(now);
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private void add(Location loc, Axis axis, long now) {
        if (loc.getWorld() == null) return;
        // World maps are never removed (there are only a handful); chunk buckets are created
        // and dropped through compute, which is atomic per key, so a sweep cannot discard a
        // bucket that an insert is writing to.
        Map<Long, ArrayDeque<Push>> chunks =
                pushes.computeIfAbsent(loc.getWorld().getUID(), k -> new ConcurrentHashMap<>());
        Push push = new Push(loc.getX(), loc.getY(), loc.getZ(), axis, now);
        long cutoff = now - GRACE_MS;
        chunks.compute(chunkKey(loc.getBlockX() >> 4, loc.getBlockZ() >> 4), (k, bucket) -> {
            if (bucket == null) bucket = new ArrayDeque<>();
            synchronized (bucket) {
                // Expired entries leave first; the cap only bites when a single chunk produces
                // more movement than any real machine within the grace window.
                while (!bucket.isEmpty() && bucket.peekFirst().time() < cutoff) bucket.pollFirst();
                while (bucket.size() >= CHUNK_CAPACITY) bucket.pollFirst();
                bucket.addLast(push);
            }
            return bucket;
        });
    }

    /** Remove expired entries and empty buckets everywhere. */
    private void sweep(long now) {
        long cutoff = now - GRACE_MS;
        for (Map<Long, ArrayDeque<Push>> chunks : pushes.values()) {
            for (Long key : chunks.keySet()) {
                chunks.computeIfPresent(key, (k, bucket) -> {
                    synchronized (bucket) {
                        while (!bucket.isEmpty() && bucket.peekFirst().time() < cutoff) bucket.pollFirst();
                        return bucket.isEmpty() ? null : bucket;
                    }
                });
            }
        }
    }

    /** Number of chunk buckets currently indexed (for tests). */
    int bucketCount() {
        int n = 0;
        for (Map<Long, ArrayDeque<Push>> chunks : pushes.values()) n += chunks.size();
        return n;
    }

    /** Run the periodic sweep now (for tests). */
    void sweepNow() {
        sweep(System.currentTimeMillis());
    }

    /** What a query is looking for. */
    private interface Match {
        boolean test(Push p, Location loc);
    }

    private boolean any(Location loc, Match match) {
        if (loc == null || loc.getWorld() == null) return false;
        Map<Long, ArrayDeque<Push>> chunks = pushes.get(loc.getWorld().getUID());
        if (chunks == null || chunks.isEmpty()) return false;
        long cutoff = System.currentTimeMillis() - GRACE_MS;
        int minCx = (int) Math.floor(loc.getX() - RADIUS) >> 4;
        int maxCx = (int) Math.floor(loc.getX() + RADIUS) >> 4;
        int minCz = (int) Math.floor(loc.getZ() - RADIUS) >> 4;
        int maxCz = (int) Math.floor(loc.getZ() + RADIUS) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                ArrayDeque<Push> bucket = chunks.get(chunkKey(cx, cz));
                if (bucket == null) continue;
                synchronized (bucket) {
                    for (Iterator<Push> it = bucket.descendingIterator(); it.hasNext(); ) {
                        Push p = it.next();
                        if (p.time() < cutoff) break; // newest first: the rest are older still
                        if (match.test(p, loc)) return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean within(Push p, Location loc) {
        double dx = p.x() - loc.getX();
        double dy = p.y() - loc.getY();
        double dz = p.z() - loc.getZ();
        return dx * dx + dy * dy + dz * dz <= RADIUS_SQ;
    }

    /**
     * True when a piston moved close to this location within the grace window, in any
     * direction. Call from would-flag paths only.
     */
    public boolean wasPushedRecently(Location loc) {
        return any(loc, PistonTracker::within);
    }

    /** True when a recent push along X or Z happened near this location. */
    public boolean horizontalPushNear(Location loc) {
        return any(loc, (p, l) -> p.axis() != Axis.Y && within(p, l));
    }

    /**
     * True when something near this location could have held or lifted the player: a push
     * along Y (piston elevators, vertical flying machines), or any push whose recorded block
     * sits under the player's feet — a moving floor carries whoever stands on it.
     */
    public boolean verticalPushNear(Location feet) {
        return any(feet, (p, l) -> {
            if (!within(p, l)) return false;
            if (p.axis() == Axis.Y) return true;
            // Block coordinates: the block spans [y, y+1). Under the feet means its top is at
            // most a couple of blocks below them, inside the player's footprint.
            double below = l.getY() - (p.y() + 1.0);
            return below >= -0.5 && below <= 2.5
                    && Math.abs(p.x() + 0.5 - l.getX()) <= 1.5
                    && Math.abs(p.z() + 0.5 - l.getZ()) <= 1.5;
        });
    }

    /** Number of pushes currently held (for tests). */
    int size() {
        int n = 0;
        for (Map<Long, ArrayDeque<Push>> chunks : pushes.values()) {
            for (ArrayDeque<Push> bucket : chunks.values()) {
                synchronized (bucket) {
                    n += bucket.size();
                }
            }
        }
        return n;
    }
}
