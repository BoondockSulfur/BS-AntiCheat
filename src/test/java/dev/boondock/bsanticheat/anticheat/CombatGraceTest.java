package dev.boondock.bsanticheat.anticheat;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The combat checks' teleport / join grace.
 *
 * <p>Combat was the only check family without one. MovementChecker keeps recentTeleport and
 * recentJoin, PacketChecker keeps graceUntil — a hit landing while a teleport is still in
 * flight is measured against a position no client involved had yet, and produces a reach
 * figure nobody could have caused. Live alert 2026-08-26 on NewBeginnings: 7.26 blocks
 * against a 4.00 cap, half a minute after an admin teleported to the player.
 */
class CombatGraceTest extends ScenarioBase {

    private CombatChecker checker;

    @BeforeEach
    void setUpChecker() {
        checker = new CombatChecker(plugin, config, null, lang);
        checker.setViolationManager(violations);
    }

    /**
     * An attack from far outside reach — flagged unless something exempts it.
     *
     * <p>The maps have to be EnumMaps of Guava functions: EntityDamageEvent's constructor
     * walks every DamageModifier and a Map.of() misses the ones it does not contain.
     */
    private void hitFromAfar(Player attacker, Player victim) {
        try {
            hitFromAfarSpaced(attacker, victim);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The hits are spaced apart on purpose: the same attacker/victim pair inside one tick is
     * treated as one swing re-delivered by an item plugin, so a burst in a single millisecond
     * would collapse to a single hit and never build a streak.
     */
    private void hitFromAfarSpaced(Player attacker, Player victim) throws InterruptedException {
        victim.teleport(new Location(world, 60.5, 64.0, 0.5)); // 60 blocks away
        Map<EntityDamageEvent.DamageModifier, Double> mods =
                new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        mods.put(EntityDamageEvent.DamageModifier.BASE, 1.0);
        Map<EntityDamageEvent.DamageModifier, com.google.common.base.Function<? super Double, Double>> funcs =
                new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        funcs.put(EntityDamageEvent.DamageModifier.BASE, d -> d);
        for (int i = 0; i < config.reachViolations() + 2; i++) {
            checker.onEntityDamageByEntity(new EntityDamageByEntityEvent(
                    attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, mods, funcs));
            Thread.sleep(70);
        }
    }

    @Test
    @DisplayName("A hit from far outside reach is flagged")
    void reachIsCaught() {
        PlayerMock attacker = player(0.5, 64.0, 0.5);
        PlayerMock victim = player(0.5, 64.0, 0.5);
        hitFromAfar(attacker, victim);
        assertTrue(violations.count("REACH") > 0, "60 blocks is not a reach a cap allows");
    }

    @Test
    @DisplayName("The attacker's own teleport exempts the hit")
    void attackerTeleportExempts() {
        PlayerMock attacker = player(0.5, 64.0, 0.5);
        PlayerMock victim = player(0.5, 64.0, 0.5);
        checker.onTeleport(new PlayerTeleportEvent(attacker,
                new Location(world, 0.5, 64.0, 0.5), new Location(world, 0.5, 64.0, 0.5)));
        hitFromAfar(attacker, victim);
        assertEquals(0, violations.count("REACH"),
                "the attacker's own positions are still in flight");
    }

    @Test
    @DisplayName("A teleport of the TARGET exempts it too")
    void victimTeleportExempts() {
        // The live shape: an admin teleports to the player, and it is the other side whose
        // position the attacking client has not caught up with yet.
        PlayerMock attacker = player(0.5, 64.0, 0.5);
        PlayerMock victim = player(0.5, 64.0, 0.5);
        checker.onTeleport(new PlayerTeleportEvent(victim,
                new Location(world, 0.5, 64.0, 0.5), new Location(world, 0.5, 64.0, 0.5)));
        hitFromAfar(attacker, victim);
        assertEquals(0, violations.count("REACH"),
                "a target that just arrived desyncs the attacker's view of it");
    }

    @Test
    @DisplayName("A join exempts the hit")
    void joinExempts() {
        PlayerMock attacker = player(0.5, 64.0, 0.5);
        PlayerMock victim = player(0.5, 64.0, 0.5);
        checker.onJoin(new org.bukkit.event.player.PlayerJoinEvent(attacker, (String) null));
        hitFromAfar(attacker, victim);
        assertEquals(0, violations.count("REACH"));
    }

    @Test
    @DisplayName("Ability damage is not judged as a hit at all")
    void abilityDamageIsSkippedEndToEnd() throws InterruptedException {
        // A ranged ability reaches the checker shaped exactly like a melee hit. With the melee
        // tracker live and no attack packet behind it, none of the geometry is judged — not
        // reach, not aim angle, not multi-target.
        MeleeTracker melee = new MeleeTracker();
        melee.noteAttack(java.util.UUID.randomUUID(), System.currentTimeMillis()); // active
        checker.setMeleeTracker(melee);
        PlayerMock attacker = player(0.5, 64.0, 0.5);
        PlayerMock victim = player(0.5, 64.0, 0.5);
        hitFromAfarSpaced(attacker, victim);
        assertEquals(0, violations.count("REACH"),
                "damage the client never asked for is not a swing");
    }

    @Test
    @DisplayName("A real swing is still judged")
    void meleeHitStillJudged() throws InterruptedException {
        // The other half: the gate must not disarm the check for genuine attacks.
        MeleeTracker melee = new MeleeTracker();
        checker.setMeleeTracker(melee);
        PlayerMock attacker = player(0.5, 64.0, 0.5);
        PlayerMock victim = player(0.5, 64.0, 0.5);
        victim.teleport(new org.bukkit.Location(world, 60.5, 64.0, 0.5));
        Map<EntityDamageEvent.DamageModifier, Double> mods =
                new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        mods.put(EntityDamageEvent.DamageModifier.BASE, 1.0);
        Map<EntityDamageEvent.DamageModifier, com.google.common.base.Function<? super Double, Double>> funcs =
                new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        funcs.put(EntityDamageEvent.DamageModifier.BASE, d -> d);
        for (int i = 0; i < config.reachViolations() + 2; i++) {
            melee.noteAttack(attacker.getUniqueId(), System.currentTimeMillis());
            checker.onEntityDamageByEntity(new EntityDamageByEntityEvent(
                    attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, mods, funcs));
            Thread.sleep(70);
        }
        assertTrue(violations.count("REACH") > 0, "a swung hit is still judged");
    }

    @Test
    @DisplayName("The same swing delivered twice counts once")
    void duplicateDamageEventsAreDeduplicated() {
        // Item plugins re-deliver a hit: measured here, 163 of 327 hit groups arrived 2-4
        // times with identical geometry. Without this, one over-reach hit satisfies a
        // reach_violations of 3 on its own.
        java.util.UUID a = java.util.UUID.randomUUID();
        java.util.UUID v = java.util.UUID.randomUUID();
        long t = 1_000_000L;
        assertFalse(checker.isRepeatOfSameSwing(a, v, t), "the first delivery is the hit");
        assertTrue(checker.isRepeatOfSameSwing(a, v, t + 1), "same tick, same pair");
        assertTrue(checker.isRepeatOfSameSwing(a, v, t + 40), "still the same swing");
    }

    @Test
    @DisplayName("A genuine second hit is not swallowed")
    void laterHitStillCounts() {
        // Weapon cooldowns put real hits on one victim hundreds of milliseconds apart, so the
        // window must stay well under that or the dedup becomes a hiding place.
        java.util.UUID a = java.util.UUID.randomUUID();
        java.util.UUID v = java.util.UUID.randomUUID();
        long t = 1_000_000L;
        assertFalse(checker.isRepeatOfSameSwing(a, v, t));
        assertFalse(checker.isRepeatOfSameSwing(a, v, t + 300), "300ms later is a new swing");
    }

    @Test
    @DisplayName("A different victim in the same tick is a different hit")
    void differentVictimIsNotADuplicate() {
        // Multi-aura depends on this: hitting several targets in one moment is the signal.
        java.util.UUID a = java.util.UUID.randomUUID();
        long t = 1_000_000L;
        assertFalse(checker.isRepeatOfSameSwing(a, java.util.UUID.randomUUID(), t));
        assertFalse(checker.isRepeatOfSameSwing(a, java.util.UUID.randomUUID(), t + 1));
    }

    @Test
    @DisplayName("The grace does not last forever")
    void graceExpires() {
        // Stamped far enough in the past that the window has lapsed — otherwise the exemption
        // would be a permanent one for anyone who ever teleported.
        PlayerMock attacker = player(0.5, 64.0, 0.5);
        PlayerMock victim = player(0.5, 64.0, 0.5);
        checker.onTeleport(new PlayerTeleportEvent(attacker,
                new Location(world, 0.5, 64.0, 0.5), new Location(world, 0.5, 64.0, 0.5)));
        checker.expireGraceForTest(attacker.getUniqueId());
        checker.expireGraceForTest(victim.getUniqueId());
        hitFromAfar(attacker, victim);
        assertTrue(violations.count("REACH") > 0, "a lapsed grace must stop exempting");
    }
}
