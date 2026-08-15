package dev.boondock.bsanticheat.anticheat;

import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reach and KillAura, driven through damage events.
 *
 * <p>Multi-target used to flag on a single burst, which a crowded fight produces without
 * anyone cheating — it now needs a streak, like reach and aim angle always did.
 */
class CombatScenarioTest extends ScenarioBase {

    private CombatChecker checker;

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
    void reachIsCaught() {
        PlayerMock attacker = player(0.5, 80.0, 0.5);
        PlayerMock victim = player(20.5, 80.0, 0.5); // 20 blocks away
        for (int i = 0; i < config.reachViolations() + 2; i++) hit(attacker, victim);
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
    void sustainedMultiTargetIsCaught() {
        PlayerMock attacker = player(0.5, 80.0, 0.5);
        PlayerMock a = player(1.2, 80.0, 0.5);
        PlayerMock b = player(1.2, 80.0, 1.2);
        PlayerMock c = player(0.5, 80.0, 1.2);
        for (int round = 0; round < config.killAuraMultiViolations() + 2; round++) {
            hit(attacker, a);
            hit(attacker, b);
            hit(attacker, c);
        }
        assertTrue(violations.count("KILLAURA") > 0, "a held-up pattern must still be caught");
    }
}
