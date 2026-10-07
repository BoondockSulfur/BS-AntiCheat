package dev.boondock.bsanticheat.anticheat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-announced impulses from the spear (MC 1.21.11+), for the movement checks.
 *
 * <p>A spear jab arrives as the player-digging action STAB. With the Lunge enchantment it
 * throws the player forwards — a displacement no velocity packet announces, so the movement
 * checks need to know it happened and how strong it was. This class records the fact; what
 * allowance it earns is the movement checks' decision.
 *
 * <p>A STAB packet alone proves nothing: the client can send it at will, and the server applies
 * the Lunge effect only when the jab is accepted (full attack charge, food level of at least 7,
 * not riding, gliding or in water). A lunge therefore counts only with server-side evidence.
 * The evidence used is the Lunge enchantment's own exhaustion cost: vanilla applies it in the
 * same {@code post_piercing_attack} effect as the impulse ({@code apply_exhaustion}, 4 per
 * level), through {@code Player.causeFoodExhaustion}, for which Paper fires an
 * {@code EntityExhaustionEvent} with reason UNKNOWN. A jab from a Lunge spear (the candidate)
 * and an exhaustion of exactly 4 x its level on the player's thread within
 * {@link #EVIDENCE_WINDOW_MS} of each other, in either order, confirm the lunge. Durability
 * loss was not used: Unbreaking makes it probabilistic and unbreakable items never lose any.
 * Confirmed lunges are additionally limited to one per attack cooldown of the held spear
 * (spears need a full charge to jab), so even a forged evidence stream cannot chain them.
 *
 * <p>It also records a hotbar change that arrives within one tick after a STAB. Swapping away
 * from the spear in the same tick as the jab keeps the lunge while hiding the item, which the
 * movement side may want to take into account. It is only recorded, never alerted on.
 *
 * <p>Written from the Netty thread (STAB, slot change) and the region thread (item lookup),
 * read from region threads. All values are immutable records in concurrent maps.
 */
public final class LungeTracker {

    /** A STAB made while holding a spear with Lunge: packet arrival time and enchantment level. */
    public record Lunge(long timeMs, int level) {}

    /** How close after a STAB a hotbar change counts as part of the same tick. */
    static final long SAME_TICK_MS = 50L;
    /** How far apart (ms) a lunge candidate and its exhaustion evidence may be noted. */
    static final long EVIDENCE_WINDOW_MS = 250L;
    /** Rate limit for confirmed lunges when the spear's attack cooldown is not known. */
    static final long DEFAULT_MIN_INTERVAL_MS = 1000L;
    /** Lower bound of the rate limit, whatever the attack speed claims. */
    static final long MIN_INTERVAL_FLOOR_MS = 250L;
    /** Exhaustion the Lunge effect applies per level (vanilla {@code apply_exhaustion}). */
    static final float EXHAUSTION_PER_LEVEL = 4.0f;

    /** A jab from a Lunge spear awaiting server evidence. */
    private record Candidate(long stabTimeMs, int level, long minIntervalMs, long notedAt) {}

    /** A lunge-shaped exhaustion awaiting its jab. */
    private record Evidence(float amount, long at) {}

    private final Map<UUID, Lunge> lunges = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastStab = new ConcurrentHashMap<>();
    // [0] = time of the last same-tick swap (ms), [1] = how many were seen this session.
    private final Map<UUID, long[]> stabSwaps = new ConcurrentHashMap<>();
    private final Map<UUID, Candidate> candidates = new ConcurrentHashMap<>();
    private final Map<UUID, Evidence> evidence = new ConcurrentHashMap<>();
    // Stab time of the last confirmed lunge, for the rate limit.
    private final Map<UUID, Long> lastConfirmed = new ConcurrentHashMap<>();

    /** Any STAB packet, spear or not, Lunge or not. */
    public void noteStab(UUID id, long now) {
        lastStab.put(id, now);
    }

    /** A lunge the server has performed, at {@code level}. Recorded as is (no evidence or rate check). */
    public void noteLunge(UUID id, long timeMs, int level) {
        if (level <= 0) return;
        lunges.merge(id, new Lunge(timeMs, level), (old, fresh) -> fresh.timeMs() >= old.timeMs() ? fresh : old);
    }

    /**
     * A STAB made with a spear carrying Lunge at {@code level} (player thread). Becomes a lunge
     * once matching exhaustion evidence is seen, see the class description.
     *
     * @param minIntervalMs the spear's attack cooldown, or 0 when unknown
     */
    public synchronized void noteLungeCandidate(UUID id, long stabTimeMs, int level, long minIntervalMs, long now) {
        if (level <= 0) return;
        Evidence e = evidence.get(id);
        if (e != null && Math.abs(now - e.at()) <= EVIDENCE_WINDOW_MS && matches(e.amount(), level)) {
            evidence.remove(id);
            candidates.remove(id);
            confirm(id, stabTimeMs, level, minIntervalMs);
            return;
        }
        candidates.put(id, new Candidate(stabTimeMs, level, minIntervalMs, now));
    }

    /** An exhaustion applied to the player (EntityExhaustionEvent, player thread). */
    public synchronized void noteExhaustion(UUID id, float amount, long now) {
        if (!lungeShaped(amount)) return;
        Candidate c = candidates.get(id);
        if (c != null && Math.abs(now - c.notedAt()) <= EVIDENCE_WINDOW_MS && matches(amount, c.level())) {
            candidates.remove(id);
            evidence.remove(id);
            confirm(id, c.stabTimeMs(), c.level(), c.minIntervalMs());
            return;
        }
        evidence.put(id, new Evidence(amount, now));
    }

    /** Record a confirmed lunge unless the previous one is closer than the spear's cooldown. */
    private void confirm(UUID id, long stabTimeMs, int level, long minIntervalMs) {
        long interval = minIntervalMs > 0
                ? Math.max(MIN_INTERVAL_FLOOR_MS, Math.min(DEFAULT_MIN_INTERVAL_MS, minIntervalMs))
                : DEFAULT_MIN_INTERVAL_MS;
        Long last = lastConfirmed.get(id);
        if (last != null && stabTimeMs - last < interval) return;
        lastConfirmed.put(id, stabTimeMs);
        noteLunge(id, stabTimeMs, level);
    }

    static boolean matches(float amount, int level) {
        return Math.abs(amount - EXHAUSTION_PER_LEVEL * level) < 1.0e-3f;
    }

    /** Whether an exhaustion amount is one the Lunge effect can produce (level 1-5). */
    static boolean lungeShaped(float amount) {
        for (int level = 1; level <= 5; level++) {
            if (matches(amount, level)) return true;
        }
        return false;
    }

    /** A hotbar slot change from the client. Recorded when it follows a STAB within one tick. */
    public void noteHeldSlotChange(UUID id, long now) {
        Long stab = lastStab.get(id);
        if (stab == null) return;
        long dt = now - stab;
        if (dt < 0 || dt > SAME_TICK_MS) return;
        stabSwaps.compute(id, (k, v) -> v == null ? new long[]{now, 1L} : new long[]{now, v[1] + 1L});
    }

    /** The most recent lunge, or null if the player has made none this session. */
    public Lunge lastLunge(UUID id) {
        return lunges.get(id);
    }

    /** Time of the last STAB (any), or -1. */
    public long lastStab(UUID id) {
        Long t = lastStab.get(id);
        return t == null ? -1L : t;
    }

    /** Time of the last hotbar change inside the STAB tick, or -1. */
    public long lastStabSwap(UUID id) {
        long[] v = stabSwaps.get(id);
        return v == null ? -1L : v[0];
    }

    /** How many same-tick swaps after a STAB were seen this session. */
    public long stabSwapCount(UUID id) {
        long[] v = stabSwaps.get(id);
        return v == null ? 0L : v[1];
    }

    public void cleanup(UUID id) {
        lunges.remove(id);
        lastStab.remove(id);
        stabSwaps.remove(id);
        candidates.remove(id);
        evidence.remove(id);
        lastConfirmed.remove(id);
    }
}
