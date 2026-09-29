package dev.boondock.bsanticheat.anticheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Telling a swing from an item plugin's ranged ability.
 *
 * <p>Example: a sword whose right-click fires a 10-block beam. Item plugins deliver that
 * damage as {@code LivingEntity.damage(amount, player)}, which Bukkit reports as ENTITY_ATTACK
 * with the player as damager — the exact shape of a melee hit. Reach measured on such damage
 * is bounded by the ability's range, not by the player's arm.
 */
class MeleeTrackerTest {

    private static final long T = 5_000_000L;
    private static final int TARGET = 42;

    @Test
    @DisplayName("A swing at one entity does not vouch for damage to another")
    void attackIsBoundToItsTarget() {
        // A ranged ability landing on a different entity right after an ordinary swing is
        // still ability damage.
        MeleeTracker t = new MeleeTracker();
        UUID id = UUID.randomUUID();
        t.noteAttack(id, TARGET, T);
        assertFalse(t.isMeleeHit(id, TARGET + 1, T + 50), "the client attacked something else");
    }

    @Test
    @DisplayName("Several targets attacked in one tick are all vouched for")
    void multiTargetAttacksAllCount() {
        // Multi-aura sends several attack packets before the server processes the first hit;
        // remembering only the last one would hide exactly that pattern.
        MeleeTracker t = new MeleeTracker();
        UUID id = UUID.randomUUID();
        t.noteAttack(id, 1, T);
        t.noteAttack(id, 2, T);
        t.noteAttack(id, 3, T);
        assertTrue(t.isMeleeHit(id, 1, T + 10));
        assertTrue(t.isMeleeHit(id, 2, T + 10));
        assertTrue(t.isMeleeHit(id, 3, T + 10));
    }

    @Test
    @DisplayName("Damage right after an attack packet is a melee hit")
    void attackPacketVouchesForTheHit() {
        MeleeTracker t = new MeleeTracker();
        UUID id = UUID.randomUUID();
        t.noteAttack(id, TARGET, T);
        assertTrue(t.isMeleeHit(id, TARGET, T));
        assertTrue(t.isMeleeHit(id, TARGET, T + 200), "still the same swing being processed");
    }

    @Test
    @DisplayName("Damage with no attack packet is not judged")
    void abilityDamageIsNotAMeleeHit() {
        MeleeTracker t = new MeleeTracker();
        UUID shooter = UUID.randomUUID();
        // Somebody else swung; this player only right-clicked.
        t.noteAttack(UUID.randomUUID(), TARGET, T);
        assertFalse(t.isMeleeHit(shooter, TARGET, T), "the client never asked to attack anything");
    }

    @Test
    @DisplayName("An old attack packet stops vouching")
    void staleAttackDoesNotVouch() {
        // Otherwise one swing would cover every ability the player fires afterwards.
        MeleeTracker t = new MeleeTracker();
        UUID id = UUID.randomUUID();
        t.noteAttack(id, TARGET, T);
        assertFalse(t.isMeleeHit(id, TARGET, T + 1000));
    }

    @Test
    @DisplayName("Without PacketEvents the checks run as before")
    void inactiveTrackerNeverBlocks() {
        // The tracker is fed from the packet layer, which is a softdepend. If it is absent
        // nothing is ever recorded, and treating that as "no swing" would switch the combat
        // checks off entirely instead of making them more precise.
        MeleeTracker t = new MeleeTracker();
        assertFalse(t.isActive());
        assertTrue(t.isMeleeHit(UUID.randomUUID(), TARGET, T));
    }

    @Test
    @DisplayName("Cleanup forgets the player")
    void cleanupForgets() {
        MeleeTracker t = new MeleeTracker();
        UUID id = UUID.randomUUID();
        t.noteAttack(id, TARGET, T);
        t.cleanup(id);
        assertFalse(t.sawAttackRecently(id, TARGET, T));
    }
}
