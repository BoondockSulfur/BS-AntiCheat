package dev.boondock.bsanticheat.anticheat;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the reach check's ping ceiling may and may not switch off.
 *
 * <p>Above {@code reach_max_ping_ms} the server can no longer say where either player was, so
 * standing reach down is the honest answer. Standing down the REST of combat with it is not:
 * the aim angle and the multi-target count are questions about rotation and about time, and
 * neither gets less answerable as latency rises. The ceiling must not be a {@code return} out
 * of the event handler, which would take both with it — on a link with round trips above one
 * second that is KillAura switched off permanently, and a cheat can put itself there
 * deliberately by answering transaction pings late.
 */
class ReachPingStandDownTest extends ScenarioBase {

    private CombatChecker checker;

    @BeforeEach
    void setUpChecker() {
        checker = new CombatChecker(plugin, config, null, lang);
        checker.setViolationManager(violations);
        // Well past reach_max_ping_ms (400), and past what pingSlack would ever have allowed.
        checker.setTransactionManager(new TransactionManager(plugin, config, null) {
            @Override
            public double roundTripMs(UUID uuid) {
                return 900.0;
            }
        });
    }

    /**
     * Hits from 60 blocks away, aimed 90 degrees off the target: over the reach cap and over
     * the aim-angle cap at the same time, so one sequence exercises both verdicts.
     */
    private void hitFromAfarLookingAway(Player attacker, Player victim) throws InterruptedException {
        victim.teleport(new Location(world, 60.5, 64.0, 0.5)); // due +X; the attacker faces +Z
        Map<EntityDamageEvent.DamageModifier, Double> mods =
                new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        mods.put(EntityDamageEvent.DamageModifier.BASE, 1.0);
        Map<EntityDamageEvent.DamageModifier, com.google.common.base.Function<? super Double, Double>> funcs =
                new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        funcs.put(EntityDamageEvent.DamageModifier.BASE, d -> d);
        for (int i = 0; i < config.reachViolations() + 2; i++) {
            checker.onEntityDamageByEntity(new EntityDamageByEntityEvent(
                    attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, mods, funcs));
            Thread.sleep(70); // outside the same-swing window, or the streak never builds
        }
    }

    @Test
    @DisplayName("Reach stands down above the ping ceiling")
    void reachStandsDownOnUnusablePing() throws InterruptedException {
        PlayerMock attacker = player(0.5, 64.0, 0.5);
        PlayerMock victim = player(0.5, 64.0, 0.5);
        hitFromAfarLookingAway(attacker, victim);
        assertEquals(0, violations.count("REACH"),
                "at 900ms the measured distance is not evidence of anything");
    }

    @Test
    @DisplayName("KillAura is still judged above the ping ceiling")
    void killAuraSurvivesUnusablePing() throws InterruptedException {
        PlayerMock attacker = player(0.5, 64.0, 0.5);
        PlayerMock victim = player(0.5, 64.0, 0.5);
        hitFromAfarLookingAway(attacker, victim);
        assertTrue(violations.count("KILLAURA") > 0,
                "an aim 90 degrees off the target is off however long the packet took");
    }
}
