package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.util.Constants;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reach and KillAura, driven through damage events.
 *
 * <p>Multi-target must not flag on a single burst, which a crowded fight produces without
 * anyone cheating — it needs a streak, like reach and aim angle.
 */
class CombatScenarioTest extends ScenarioBase {

    private CombatChecker checker;
    // Past the same-swing dedup (60ms), so each hit counts as its own swing.
    private static final long SAME_SWING_GAP_MS = 70L;

    @BeforeEach
    void setUpChecker() {
        checker = new CombatChecker(plugin, config, null, lang);
        checker.setViolationManager(violations);
    }

    private void hit(PlayerMock attacker, Entity victim) {
        Map<EntityDamageEvent.DamageModifier, Double> mods =
                new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        mods.put(EntityDamageEvent.DamageModifier.BASE, 1.0);
        Map<EntityDamageEvent.DamageModifier, com.google.common.base.Function<? super Double, Double>> funcs =
                new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        funcs.put(EntityDamageEvent.DamageModifier.BASE, d -> d);
        checker.onEntityDamageByEntity(new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, mods, funcs));
    }

    @Test
    @DisplayName("Hitting from far outside reach raises REACH")
    void reachIsCaught() throws InterruptedException {
        PlayerMock attacker = player(0.5, 80.0, 0.5);
        PlayerMock victim = player(20.5, 80.0, 0.5); // 20 blocks away
        // Spaced apart: the same attacker/victim pair inside one tick is treated as one
        // swing re-delivered by an item plugin, so a burst in a single millisecond collapses
        // to a single hit and never builds a streak. Real weapon cooldowns are far longer.
        for (int i = 0; i < config.reachViolations() + 2; i++) {
            hit(attacker, victim);
            Thread.sleep(70);
        }
        assertTrue(violations.count("REACH") > 0, "20 blocks is far past any reach attribute");
    }

    @Test
    @DisplayName("Hitting an adjacent target raises nothing")
    void normalReachIsQuiet() {
        PlayerMock attacker = player(0.5, 80.0, 0.5);
        PlayerMock victim = player(1.5, 80.0, 0.5);
        for (int i = 0; i < 10; i++) hit(attacker, victim);
        assertEquals(0, violations.count("REACH"));
    }

    @Test
    @DisplayName("One burst across several targets does not raise KILLAURA")
    void multiTargetBurstIsForgiven() {
        // A scramble: three players within reach inside the window, once.
        PlayerMock attacker = player(0.5, 80.0, 0.5);
        PlayerMock a = player(1.2, 80.0, 0.5);
        PlayerMock b = player(1.2, 80.0, 1.2);
        PlayerMock c = player(0.5, 80.0, 1.2);
        hit(attacker, a);
        hit(attacker, b);
        hit(attacker, c);
        assertEquals(0, violations.count("KILLAURA"), "one burst in a crowd is not evidence");
    }

    @Test
    @DisplayName("Repeated multi-target bursts do raise KILLAURA")
    void sustainedMultiTargetIsCaught() throws InterruptedException {
        PlayerMock attacker = player(0.5, 80.0, 0.5);
        PlayerMock a = player(1.2, 80.0, 0.5);
        PlayerMock b = player(1.2, 80.0, 1.2);
        PlayerMock c = player(0.5, 80.0, 1.2);
        // Bursts in separate windows: one burst counts once however many hits it holds.
        for (int round = 0; round < config.killAuraMultiViolations() + 1; round++) {
            hit(attacker, a);
            hit(attacker, b);
            hit(attacker, c);
            Thread.sleep(Constants.KILLAURA_MULTI_WINDOW_MS + 50);
        }
        assertTrue(violations.count("KILLAURA") > 0, "a held-up pattern must still be caught");
    }

    @Test
    @DisplayName("Four hits on three players in one burst are one streak step")
    void oneBurstCountsOnce() throws InterruptedException {
        // A butterfly-clicked scramble: the fourth hit must not bump the streak a second time
        // and reach the default of two from a single burst.
        PlayerMock attacker = player(0.5, 80.0, 0.5);
        PlayerMock a = player(1.2, 80.0, 0.5);
        PlayerMock b = player(1.2, 80.0, 1.2);
        PlayerMock c = player(0.5, 80.0, 1.2);
        hit(attacker, a);
        hit(attacker, b);
        hit(attacker, c);
        Thread.sleep(SAME_SWING_GAP_MS);
        hit(attacker, a);
        hit(attacker, b);
        hit(attacker, c);
        assertEquals(0, violations.count("KILLAURA"), "one scramble is not a sustained pattern");
    }

    @Test
    @DisplayName("A fast target is judged where the attacker could have seen it")
    void fastTargetUsesPositionHistory() throws InterruptedException {
        // An elytra or riptide target covers several blocks in one round trip plus the
        // client's interpolation. It is 12 blocks away now but was next to the attacker
        // within that window.
        PlayerMock attacker = player(0.5, 80.0, 0.5);
        PlayerMock victim = player(12.5, 80.0, 0.5);
        for (int i = 0; i < config.reachViolations() + 2; i++) {
            long now = System.currentTimeMillis();
            checker.recordPosition(victim.getUniqueId(), loc(1.5, 80.0, 0.5), now - 100);
            checker.recordPosition(victim.getUniqueId(), loc(12.5, 80.0, 0.5), now - 20);
            hit(attacker, victim);
            Thread.sleep(SAME_SWING_GAP_MS);
        }
        assertEquals(0, violations.count("REACH"), "it was within reach on the attacker's screen");
    }

    @Test
    @DisplayName("A target that was far away the whole window is still caught")
    void oldHistoryDoesNotExcuse() throws InterruptedException {
        PlayerMock attacker = player(0.5, 80.0, 0.5);
        PlayerMock victim = player(12.5, 80.0, 0.5);
        long start = System.currentTimeMillis();
        checker.recordPosition(victim.getUniqueId(), loc(1.5, 80.0, 0.5), start - 2000);
        checker.recordPosition(victim.getUniqueId(), loc(12.5, 80.0, 0.5), start - 1000);
        for (int i = 0; i < config.reachViolations() + 2; i++) {
            hit(attacker, victim);
            Thread.sleep(SAME_SWING_GAP_MS);
        }
        assertTrue(violations.count("REACH") > 0, "being close two seconds ago excuses nothing");
    }

    @Test
    @DisplayName("Aim angle is measured to the hitbox, not its centre")
    void angleToHitbox() {
        // Eye 1.5 blocks from a player's box, looking at its top edge: the centre is ~30
        // degrees off, the box itself is under the crosshair.
        Vector eye = new Vector(0.0, 1.62, 0.0);
        BoundingBox box = new BoundingBox(-0.3, 0.0, 1.2, 0.3, 1.8, 1.8);
        Vector look = new Vector(0.0, 0.1, 1.0);
        assertEquals(0.0, CombatChecker.angleToBox(eye, look, box), 0.5);
        // Looking straight away from it is still far off.
        assertTrue(CombatChecker.angleToBox(eye, new Vector(0.0, 0.0, -1.0), box) > 120.0);
        // Looking sideways: the angle is to the near edge, smaller than to the centre.
        Vector side = new Vector(1.0, 0.0, 0.0);
        double toCentre = Math.toDegrees(side.angle(box.getCenter().subtract(eye)));
        double toBox = CombatChecker.angleToBox(eye, side, box);
        assertTrue(toBox < toCentre, toBox + " should be below " + toCentre);
        assertTrue(toBox > 60.0, "but still clearly off: " + toBox);
    }

    @Test
    @DisplayName("Stale position histories are swept however few there are")
    void staleHistoriesAreSwept() {
        java.util.UUID vehicle = java.util.UUID.randomUUID();
        long start = System.currentTimeMillis();
        checker.recordPosition(vehicle, loc(1.5, 80.0, 0.5), start);
        assertTrue(checker.hasPositionHistory(vehicle));
        checker.sweepPositions(start + 60_000L);
        org.junit.jupiter.api.Assertions.assertFalse(checker.hasPositionHistory(vehicle));
    }
}
