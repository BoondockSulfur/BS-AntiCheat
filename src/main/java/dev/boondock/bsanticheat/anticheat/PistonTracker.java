package dev.boondock.bsanticheat.anticheat;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
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
 * <p>Cost is deliberately kept off the piston event, which on a redstone-heavy server can
 * fire many times a second: the handler only appends a position to a bounded ring buffer.
 * The distance search runs in the checkers' would-flag paths, the same arrangement
 * {@code hasEntitySupport} uses.
 */
public final class PistonTracker implements Listener {

    /** Bounded so a clock circuit cannot grow it without limit. */
    private static final int CAPACITY = 128;
    /** How long a piston movement keeps its exemption. */
    private static final long GRACE_MS = 1500L;
    /** How far from the recorded position a player is still considered shoved. */
    private static final double RADIUS = 4.0;
    private static final double RADIUS_SQ = RADIUS * RADIUS;

    private record Push(UUID world, double x, double y, double z, long time) {}

    private final ConcurrentLinkedDeque<Push> pushes = new ConcurrentLinkedDeque<>();
    // ConcurrentLinkedDeque.size() walks the whole list, and this runs on every piston pulse —
    // a clock circuit fires many per second — so the length is tracked separately.
    private final AtomicInteger count = new AtomicInteger();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        record(event.getBlock(), event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        record(event.getBlock(), event.getBlocks());
    }

    /**
     * Record the piston itself and the far end of what it moved. Two points rather than every
     * moved block: a 12-block push is covered by the two ends plus {@link #RADIUS}, and the
     * ring buffer stays useful instead of being flooded by one large machine.
     */
    private void record(Block piston, List<Block> moved) {
        long now = System.currentTimeMillis();
        add(piston.getLocation(), now);
        if (!moved.isEmpty()) {
            add(moved.get(moved.size() - 1).getLocation(), now);
        }
        while (count.get() > CAPACITY && drop()) { /* trim */ }
    }

    private void add(Location loc, long now) {
        if (loc.getWorld() == null) return;
        pushes.addLast(new Push(loc.getWorld().getUID(), loc.getX(), loc.getY(), loc.getZ(), now));
        count.incrementAndGet();
    }

    /**
     * Remove the oldest entry, keeping the counter in step. Piston events arrive on several
     * region threads, so the counter and the deque are not updated atomically together and
     * can drift apart by a little; clamping at zero keeps that drift from turning into an
     * endless trim loop.
     */
    private boolean drop() {
        if (pushes.pollFirst() == null) {
            count.set(0);
            return false;
        }
        count.updateAndGet(c -> Math.max(0, c - 1));
        return true;
    }

    /**
     * True when a piston moved close to this location within the grace window. Call from
     * would-flag paths only — it walks the whole buffer.
     */
    public boolean wasPushedRecently(Location loc) {
        if (loc == null || loc.getWorld() == null || pushes.isEmpty()) return false;
        long cutoff = System.currentTimeMillis() - GRACE_MS;
        UUID world = loc.getWorld().getUID();
        boolean hit = false;
        for (Push p : pushes) {
            if (p.time() < cutoff) continue;
            if (!p.world().equals(world)) continue;
            double dx = p.x() - loc.getX();
            double dy = p.y() - loc.getY();
            double dz = p.z() - loc.getZ();
            if (dx * dx + dy * dy + dz * dz <= RADIUS_SQ) {
                hit = true;
                break;
            }
        }
        // Drop entries that can no longer match, so an idle buffer empties itself.
        Push head;
        while ((head = pushes.peekFirst()) != null && head.time() < cutoff) {
            if (!drop()) break;
        }
        return hit;
    }
}
