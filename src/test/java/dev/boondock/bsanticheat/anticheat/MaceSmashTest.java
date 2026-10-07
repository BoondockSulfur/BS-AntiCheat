package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Mace smashes whose fall distance is far above the descent the server measured.
 */
class MaceSmashTest extends ScenarioBase {

    private CombatChecker checker;
    private FallTracker falls;
    private LungeTracker lunges;

    @BeforeEach
    void setUpChecker() {
        plugin.getConfig().set("anticheat.mace_detection", true);
        config = new PluginConfig(plugin);
        checker = new CombatChecker(plugin, config, null, lang);
        checker.setViolationManager(violations);
        falls = new FallTracker();
        lunges = new LungeTracker();
        checker.setFallTracker(falls);
        checker.setLungeTracker(lunges);
    }

    private void hit(PlayerMock attacker, Entity victim) {
        Map<EntityDamageEvent.DamageModifier, Double> mods = new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        mods.put(EntityDamageEvent.DamageModifier.BASE, 1.0);
        Map<EntityDamageEvent.DamageModifier, com.google.common.base.Function<? super Double, Double>> funcs =
                new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        funcs.put(EntityDamageEvent.DamageModifier.BASE, d -> d);
        checker.onEntityDamageByEntity(new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, mods, funcs));
    }

    private PlayerMock maceWielder() {
        PlayerMock attacker = player(0.5, 80.0, 0.5);
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.MACE));
        return attacker;
    }

    /** Three smashes, each after a landing and a measured descent of {@code measured}. */
    private void smashes(PlayerMock attacker, PlayerMock victim, float claimed, double measured,
                         Runnable before) throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            long now = System.currentTimeMillis();
            falls.land(attacker.getUniqueId(), now);
            falls.addDescent(attacker.getUniqueId(), measured, now);
            if (before != null) before.run();
            attacker.setFallDistance(claimed);
            hit(attacker, victim);
            Thread.sleep(70);
        }
    }

    @Test
    @DisplayName("Smashing with a fall distance the server never saw raises MACE")
    void inflatedSmashIsCaught() throws Exception {
        PlayerMock attacker = maceWielder();
        PlayerMock victim = player(1.5, 80.0, 0.5);
        smashes(attacker, victim, 20f, 1.0, null);
        assertEquals(1, violations.count("MACE"));
    }

    @Test
    @DisplayName("A smash matching the measured fall raises nothing")
    void honestSmashIsQuiet() throws Exception {
        PlayerMock attacker = maceWielder();
        PlayerMock victim = player(1.5, 80.0, 0.5);
        smashes(attacker, victim, 12f, 11.5, null);
        assertEquals(0, violations.count("MACE"));
    }

    @Test
    @DisplayName("A wind-charge boosted smash is not judged")
    void windChargeSmashIsQuiet() throws Exception {
        PlayerMock attacker = maceWielder();
        PlayerMock victim = player(1.5, 80.0, 0.5);
        // The knockback makes the measurement unreliable until the next landing.
        smashes(attacker, victim, 20f, 1.0,
                () -> falls.disturb(attacker.getUniqueId(), System.currentTimeMillis()));
        assertEquals(0, violations.count("MACE"));
    }

    @Test
    @DisplayName("A lunge-boosted smash is not judged")
    void lungeSmashIsQuiet() throws Exception {
        PlayerMock attacker = maceWielder();
        PlayerMock victim = player(1.5, 80.0, 0.5);
        smashes(attacker, victim, 20f, 1.0,
                () -> lunges.noteLunge(attacker.getUniqueId(), System.currentTimeMillis(), 2));
        assertEquals(0, violations.count("MACE"));
    }

    @Test
    @DisplayName("Without a mace nothing is judged")
    void otherWeaponIsQuiet() throws Exception {
        PlayerMock attacker = player(0.5, 80.0, 0.5);
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
        PlayerMock victim = player(1.5, 80.0, 0.5);
        smashes(attacker, victim, 20f, 1.0, null);
        assertEquals(0, violations.count("MACE"));
    }
}
