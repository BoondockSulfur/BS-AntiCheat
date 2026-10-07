package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.util.Constants;
import dev.boondock.bsanticheat.util.GameCompat;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Horse;
import org.bukkit.entity.SkeletonHorse;
import org.bukkit.entity.ZombieHorse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Speed ceilings for ridden mounts, including the ones newer than the compile API.
 */
class MountSpeedTest extends ScenarioBase {

    private VehicleChecker checker;

    @BeforeEach
    void setUpChecker() {
        checker = new VehicleChecker(plugin, config, null, lang);
        checker.setViolationManager(violations);
    }

    @Test
    @DisplayName("Zombie and skeleton horses get the horse ceiling")
    void undeadHorses() {
        ZombieHorse zombie = world.spawn(loc(0.5, 80, 0.5), ZombieHorse.class);
        SkeletonHorse skeleton = world.spawn(loc(2.5, 80, 0.5), SkeletonHorse.class);
        assertEquals(Constants.HORSE_MAX_SPEED, checker.baseMaxSpeedFor(zombie, 1.0));
        assertEquals(Constants.HORSE_MAX_SPEED, checker.baseMaxSpeedFor(skeleton, 1.0));
    }

    @Test
    @DisplayName("The happy ghast gets its own flying ceiling")
    void happyGhast() {
        HappyGhast ghast = world.spawn(loc(0.5, 90, 0.5), HappyGhast.class);
        assertEquals(Constants.HAPPY_GHAST_MAX_SPEED, checker.baseMaxSpeedFor(ghast, 1.0));
        assertEquals(Constants.HAPPY_GHAST_MAX_SPEED, VehicleChecker.maxSpeedByTypeName(GameCompat.HAPPY_GHAST));
    }

    @Test
    @DisplayName("Nautilus and zombie nautilus are recognised by name")
    void nautilusByName() {
        assertEquals(Constants.NAUTILUS_MAX_SPEED, VehicleChecker.maxSpeedByTypeName(GameCompat.NAUTILUS));
        assertEquals(Constants.NAUTILUS_MAX_SPEED, VehicleChecker.maxSpeedByTypeName(GameCompat.ZOMBIE_NAUTILUS));
        assertEquals(0.0, VehicleChecker.maxSpeedByTypeName("PIG"));
    }

    @Test
    @DisplayName("A horse with a raised movement_speed gets a higher ceiling, never a lower one")
    void horseAttributeRaisesCeiling() {
        Horse horse = world.spawn(loc(0.5, 80, 0.5), Horse.class);
        var speed = horse.getAttribute(Attribute.MOVEMENT_SPEED);
        org.junit.jupiter.api.Assumptions.assumeTrue(speed != null, "attribute not modelled by the mock server");
        speed.setBaseValue(0.1);
        assertEquals(Constants.HORSE_MAX_SPEED, checker.maxSpeedFor(horse, 1.0), 1e-9,
                "a slow horse keeps the flat ceiling");
        speed.setBaseValue(0.5);
        double expected = 0.5 * Constants.MOUNT_BPS_PER_SPEED_UNIT * Constants.MOUNT_ATTRIBUTE_MARGIN;
        assertEquals(expected, checker.maxSpeedFor(horse, 1.0), 1e-9);
    }

    @Test
    @DisplayName("The nautilus dash credit covers one dash, then refills over two seconds")
    void dashCredit() {
        UUID id = UUID.randomUUID();
        long t = 1_000_000L;
        assertTrue(checker.drawDashCredit(id, 12.0, t), "one dash is covered");
        assertFalse(checker.drawDashCredit(id, 12.0, t + 100), "a second dash right after is not");
        assertTrue(checker.drawDashCredit(id, 12.0, t + 100 + Constants.NAUTILUS_DASH_INTERVAL_MS),
                "after the interval the credit is back");
    }
}
