package dev.boondock.bsanticheat.anticheat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p>Live case, rattenkolonie 2026-08-27: a sword whose right-click fires a VOID_ZAPPER beam
 * with {@code length: 10.0}. Every one of that player's 17 REACH alerts (4.14 to 9.37 blocks)
 * fell inside that range. The server has 21 such right-click abilities configured —
 * CURSED_BEAM, FIREBOLT, HOLY_MISSILE, SHULKER_MISSILE, SMITE and others — and none of them
 * is a swing.
 *
 * <p>What separates the two is the client: a real melee hit is preceded by an
 * {@code INTERACT_ENTITY} packet with action ATTACK. Ability damage has none, because the
 * player never attacked anything — they right-clicked. This class carries that one fact from
 * the packet layer to the combat checks.
 *
 * <p>Fed from a Netty thread, read from a region thread, hence the concurrent map.
 */
final class MeleeTracker {

    /** How long an attack packet vouches for a damage event that follows it. */
    private static final long MELEE_WINDOW_MS = 250L;

    private final Map<UUID, Long> lastAttack = new ConcurrentHashMap<>();
    private volatile boolean everFed = false;

    /** Record a client attack packet. */
    void noteAttack(UUID id, long now) {
        lastAttack.put(id, now);
        everFed = true;
    }

    /**
     * Whether this tracker is being supplied at all.
     *
     * <p>Without PacketEvents nothing ever reaches {@link #noteAttack}, and every hit would
     * look like ability damage — which would switch the combat checks off entirely rather
     * than make them more precise. While that is the case the checks run as before.
     */
    boolean isActive() {
        return everFed;
    }

    /** True when the player swung at something recently enough to explain a damage event. */
    boolean sawAttackRecently(UUID id, long now) {
        Long last = lastAttack.get(id);
        return last != null && now - last <= MELEE_WINDOW_MS;
    }

    /**
     * Whether a damage event from this player should be judged as a melee hit at all.
     * Anything the client never asked for is another plugin's damage, and its geometry says
     * nothing about the player.
     */
    boolean isMeleeHit(UUID id, long now) {
        return !isActive() || sawAttackRecently(id, now);
    }

    void cleanup(UUID id) {
        lastAttack.remove(id);
    }
}
