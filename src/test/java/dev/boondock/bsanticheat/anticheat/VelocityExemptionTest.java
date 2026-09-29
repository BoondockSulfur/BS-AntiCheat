package dev.boondock.bsanticheat.anticheat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Velocity check exemptions: blocks that eat a knockback, and relocations inside the window.
 */
class VelocityExemptionTest extends ScenarioBase {

    private VelocityChecker checker;

    @BeforeEach
    void setUpChecker() {
        checker = new VelocityChecker(plugin, config, null, lang);
        checker.setViolationManager(violations);
        floor(63, Material.STONE);
    }

    @Test
    @DisplayName("Cobweb, powder snow, berry bushes, honey and soul sand absorb knockback")
    void slowingBlocksAbsorb() {
        Location at = loc(0.5, 64.0, 0.5);
        assertFalse(VelocityChecker.isInSlowingBlock(at), "fixture check: plain stone floor");

        setBlock(0, 64, 0, Material.COBWEB);
        assertTrue(VelocityChecker.isInSlowingBlock(at));
        setBlock(0, 64, 0, Material.AIR);

        setBlock(0, 65, 0, Material.POWDER_SNOW); // head height
        assertTrue(VelocityChecker.isInSlowingBlock(at));
        setBlock(0, 65, 0, Material.AIR);

        setBlock(0, 64, 0, Material.SWEET_BERRY_BUSH);
        assertTrue(VelocityChecker.isInSlowingBlock(at));
        setBlock(0, 64, 0, Material.AIR);

        setBlock(0, 63, 0, Material.HONEY_BLOCK);
        assertTrue(VelocityChecker.isInSlowingBlock(at));
        setBlock(0, 63, 0, Material.SOUL_SAND);
        assertTrue(VelocityChecker.isInSlowingBlock(at));
        setBlock(0, 63, 0, Material.STONE);

        setBlock(1, 64, 0, Material.HONEY_BLOCK); // sliding down a honey wall
        assertTrue(VelocityChecker.isInSlowingBlock(at));
    }

    @Test
    @DisplayName("A player who did not move after a knockback is flagged")
    void ignoredKnockbackIsFlagged() {
        PlayerMock player = player(0.5, 64.0, 0.5);
        Location start = player.getLocation().clone();
        for (int i = 0; i < config.velocityViolations(); i++) {
            checker.evaluate(player.getUniqueId(), start, System.currentTimeMillis(), 0.4, 1.0, 0.0);
        }
        assertEquals(1, violations.count("VELOCITY"), "fixture check: the verdict itself works");
    }

    @Test
    @DisplayName("A teleport inside the window voids the verdict")
    void teleportVoidsVerdict() {
        PlayerMock player = player(0.5, 64.0, 0.5);
        Location start = player.getLocation().clone();
        for (int i = 0; i < config.velocityViolations(); i++) {
            long startMs = System.currentTimeMillis();
            checker.onTeleport(new PlayerTeleportEvent(player, start, start.clone()));
            checker.evaluate(player.getUniqueId(), start, startMs, 0.4, 1.0, 0.0);
        }
        assertEquals(0, violations.count("VELOCITY"));
    }
}
