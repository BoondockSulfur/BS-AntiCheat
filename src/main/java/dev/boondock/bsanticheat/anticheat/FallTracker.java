package dev.boondock.bsanticheat.anticheat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fall distance measured by the server from the positions it accepted, independent of the
 * client's on-ground flag.
 *
 * <p>Vanilla's own fall distance ({@code Player#getFallDistance}) is driven by that flag: a
 * client claiming to stand on the ground resets it, and one claiming to be airborne keeps it
 * growing. This tracker only counts descent while the server itself finds nothing to stand on,
 * so the two can be compared (NoFall, mace smash).
 *
 * <p>A measurement is <em>reliable</em> only after an ordinary landing the server could see and
 * without anything since that moved the player in a way the tracker does not model (knockback,
 * explosions, teleports, bounces, grace windows). Checks comparing against it must ignore an
 * unreliable one.
 *
 * <p>Written from the player's region thread (move events), read from the victim's region
 * thread (mace hits on Folia); each state is replaced as a whole.
 */
public final class FallTracker {

    /** Descent since the last reset, and whether it can be trusted. */
    public record State(double distance, boolean reliable, long updatedAt) {}

    private static final State NONE = new State(0.0, false, 0L);
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    /** The player descended by {@code dy} (positive) while nothing supported them. */
    public void addDescent(UUID id, double dy, long now) {
        if (dy <= 0) return;
        states.compute(id, (k, v) -> {
            State s = v == null ? NONE : v;
            return new State(s.distance() + dy, s.reliable(), now);
        });
    }

    /** A landing the server could see, or anything else vanilla resets fall distance on. */
    public void land(UUID id, long now) {
        states.put(id, new State(0.0, true, now));
    }

    /**
     * Something the tracker does not model moved the player. The measurement restarts and
     * stays unreliable until the next ordinary landing.
     */
    public void disturb(UUID id, long now) {
        states.put(id, new State(0.0, false, now));
    }

    /** Standing on something no block scan sees (an entity): distance restarts, trust kept. */
    public void resetKeepingTrust(UUID id, long now) {
        states.compute(id, (k, v) -> new State(0.0, v != null && v.reliable(), now));
    }

    public State state(UUID id) {
        State s = states.get(id);
        return s == null ? NONE : s;
    }

    public double distance(UUID id) {
        return state(id).distance();
    }

    public boolean reliable(UUID id) {
        return state(id).reliable();
    }

    public void cleanup(UUID id) {
        states.remove(id);
    }
}
