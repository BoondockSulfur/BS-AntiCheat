package dev.boondock.bsanticheat.anticheat;

import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Remembers when each player last actually swung at something.
 *
 * <p>The combat checks judge the geometry of a hit — how far away the target was, how far off
 * the aim, how many targets at once. All of that assumes the hit came from a swing. It does
 * not always: an item plugin that deals damage through an ability calls
 * {@code LivingEntity.damage(amount, player)}, and Bukkit reports that as
 * {@code ENTITY_ATTACK} with the player as damager — the exact shape of a melee hit, from
 * wherever the ability reaches.
 *
 * <p>Such abilities (beams, projectiles, smites triggered by right-click) can reach well
 * beyond melee range, so judging them as melee hits produces false REACH alerts.
 *
 * <p>What separates the two is the client: a real melee hit is preceded by an
 * {@code INTERACT_ENTITY} packet with action ATTACK. Ability damage has none, because the
 * player never attacked anything — they right-clicked. This class carries that one fact from
 * the packet layer to the combat checks, together with the entity the attack targeted.
 *
 * <p>Fed from a Netty thread, read from a region thread, hence the concurrent map.
 */
final class MeleeTracker {

    /** How long an attack packet vouches for a damage event that follows it. */
    private static final long MELEE_WINDOW_MS = 250L;
    // Attack packets remembered per player. Several targets can be attacked before the server
    // processes the first damage event (multi-aura), so the last one alone is not enough.
    private static final int MAX_RECENT_ATTACKS = 8;

    // Per attacker: recent attacks as {targetEntityId, timeMs}, newest last.
    private final Map<UUID, Deque<long[]>> recentAttacks = new ConcurrentHashMap<>();
    private volatile boolean everFed = false;

    /** Record a client attack packet against the entity with this protocol entity id. */
    void noteAttack(UUID id, int targetEntityId, long now) {
        Deque<long[]> dq = recentAttacks.computeIfAbsent(id, k -> new ConcurrentLinkedDeque<>());
        dq.addLast(new long[]{targetEntityId, now});
        while (dq.size() > MAX_RECENT_ATTACKS) dq.pollFirst();
        everFed = true;
    }

    /**
     * Whether this tracker is being supplied at all.
     *
     * <p>Without PacketEvents nothing ever reaches {@link #noteAttack}, and every hit would
     * look like ability damage — which would switch the combat checks off entirely rather
     * than make them more precise. While that is the case the checks judge every hit. Attack
     * packets are recorded independently of the packet_checks toggle, so turning that off
     * does not leave the tracker active but unfed.
     */
    boolean isActive() {
        return everFed;
    }

    /**
     * True when the player attacked this very entity recently enough to explain a damage
     * event. Keyed on the target: a plugin ability hitting another entity shortly after an
     * ordinary swing is still not a melee hit on that entity.
     */
    boolean sawAttackRecently(UUID id, int targetEntityId, long now) {
        Deque<long[]> dq = recentAttacks.get(id);
        if (dq == null) return false;
        for (long[] a : dq) {
            if (a[0] == targetEntityId && now - a[1] <= MELEE_WINDOW_MS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a damage event from this player against this entity should be judged as a
     * melee hit at all. Anything the client never asked for is another plugin's damage, and
     * its geometry says nothing about the player.
     */
    boolean isMeleeHit(UUID id, int targetEntityId, long now) {
        return !isActive() || sawAttackRecently(id, targetEntityId, now);
    }

    void cleanup(UUID id) {
        recentAttacks.remove(id);
    }
}
