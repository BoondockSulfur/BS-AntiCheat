package dev.boondock.bsanticheat.anticheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Telling a swing from an item plugin's ranged ability.
 *
 * <p>Live case, rattenkolonie 2026-08-27: a sword whose right-click fires a 10-block beam.
 * MythicLib delivers that damage as {@code LivingEntity.damage(amount, player)}, which Bukkit
 * reports as ENTITY_ATTACK with the player as damager — the exact shape of a melee hit. All 17
 * of that player's REACH alerts (4.14 to 9.37 blocks) fell inside the beam's range.
 */
class MeleeTrackerTest {

    private static final long T = 5_000_000L;

    @Test
    @DisplayName("Damage right after an attack packet is a melee hit")
    void attackPacketVouchesForTheHit() {
        MeleeTracker t = new MeleeTracker();
        UUID id = UUID.randomUUID();
        t.noteAttack(id, T);
        assertTrue(t.isMeleeHit(id, T));
        assertTrue(t.isMeleeHit(id, T + 200), "still the same swing being processed");
    }

    @Test
    @DisplayName("Damage with no attack packet is not judged")
    void abilityDamageIsNotAMeleeHit() {
        MeleeTracker t = new MeleeTracker();
        UUID shooter = UUID.randomUUID();
        // Somebody else swung; this player only right-clicked.
        t.noteAttack(UUID.randomUUID(), T);
        assertFalse(t.isMeleeHit(shooter, T), "the client never asked to attack anything");
    }

    @Test
    @DisplayName("An old attack packet stops vouching")
    void staleAttackDoesNotVouch() {
        // Otherwise one swing would cover every ability the player fires afterwards.
        MeleeTracker t = new MeleeTracker();
        UUID id = UUID.randomUUID();
        t.noteAttack(id, T);
        assertFalse(t.isMeleeHit(id, T + 1000));
    }

    @Test
    @DisplayName("Without PacketEvents the checks run as before")
    void inactiveTrackerNeverBlocks() {
        // The tracker is fed from the packet layer, which is a softdepend. If it is absent
        // nothing is ever recorded, and treating that as "no swing" would switch the combat
        // checks off entirely instead of making them more precise.
        MeleeTracker t = new MeleeTracker();
        assertFalse(t.isActive());
        assertTrue(t.isMeleeHit(UUID.randomUUID(), T));
    }

    @Test
    @DisplayName("Cleanup forgets the player")
    void cleanupForgets() {
        MeleeTracker t = new MeleeTracker();
        UUID id = UUID.randomUUID();
        t.noteAttack(id, T);
        t.cleanup(id);
        assertFalse(t.sawAttackRecently(id, T));
    }
}
