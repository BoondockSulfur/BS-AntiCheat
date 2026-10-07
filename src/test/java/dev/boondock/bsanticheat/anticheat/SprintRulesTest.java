package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprinting a 1.21.2+ client ends by itself (low food, Blindness), and omni-sprint.
 */
class SprintRulesTest extends ScenarioBase {

    private MovementChecker checker;

    @BeforeEach
    void setUpChecker() {
        plugin.getConfig().set("anticheat.sprint_omni_detection", true);
        config = new PluginConfig(plugin);
        checker = new MovementChecker(plugin, config, null, lang);
        checker.setViolationManager(violations);
        checker.setPistonTracker(new PistonTracker());
        floor(79, Material.STONE);
    }

    /**
     * Run along a direction for {@code ticks} real ticks, looking at {@code yaw}.
     * Direction is given as a unit vector on the XZ plane.
     */
    private void run(PlayerMock player, double dirX, double dirZ, float yaw, int ticks) throws InterruptedException {
        double step = 0.25;
        for (int i = 0; i < ticks; i++) {
            Location from = loc(0.5 + dirX * step * i, 80.0, 0.5 + dirZ * step * i);
            Location to = loc(0.5 + dirX * step * (i + 1), 80.0, 0.5 + dirZ * step * (i + 1));
            from.setYaw(yaw);
            to.setYaw(yaw);
            player.teleport(from);
            checker.onPlayerMove(new PlayerMoveEvent(player, from, to));
            Thread.sleep(50);
        }
    }

    private PlayerMock sprinter() throws InterruptedException {
        PlayerMock player = player(0.5, 80.0, 0.5);
        player.setSprinting(true);
        player.setOnGround(true);
        clearGrace();
        return player;
    }

    @Test
    @DisplayName("Sprinting on food level 6 for two seconds raises SPRINT")
    void hungrySprintIsCaught() throws Exception {
        PlayerMock player = sprinter();
        player.setFoodLevel(6);
        run(player, 0, 1, 0f, 40);
        assertTrue(violations.count("SPRINT") > 0);
    }

    @Test
    @DisplayName("Sprinting on a full stomach raises nothing")
    void fedSprintIsQuiet() throws Exception {
        PlayerMock player = sprinter();
        player.setFoodLevel(20);
        run(player, 0, 1, 0f, 40);
        assertEquals(0, violations.count("SPRINT"));
    }

    @Test
    @DisplayName("A sprint ending within the latency allowance after food drops raises nothing")
    void briefHungrySprintIsQuiet() throws Exception {
        PlayerMock player = sprinter();
        player.setFoodLevel(5);
        run(player, 0, 1, 0f, 16); // 0.8 s
        player.setSprinting(false);
        run(player, 0, 1, 0f, 20);
        assertEquals(0, violations.count("SPRINT"));
    }

    @Test
    @DisplayName("A player allowed to fly may sprint on low food")
    void mayFlyIsQuiet() throws Exception {
        PlayerMock player = sprinter();
        player.setFoodLevel(2);
        player.setAllowFlight(true);
        run(player, 0, 1, 0f, 40);
        assertEquals(0, violations.count("SPRINT"));
    }

    @Test
    @DisplayName("Sprinting under Blindness for two seconds raises SPRINT")
    void blindSprintIsCaught() throws Exception {
        PlayerMock player = sprinter();
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 600, 0));
        run(player, 0, 1, 0f, 40);
        assertTrue(violations.count("SPRINT") > 0);
    }

    @Test
    @DisplayName("Sprinting backwards on the ground raises SPRINT (omni-sprint)")
    void omniSprintIsCaught() throws Exception {
        PlayerMock player = sprinter();
        // Yaw 0 looks along +Z; running along -Z is 180 degrees off.
        run(player, 0, -1, 0f, 20);
        assertTrue(violations.count("SPRINT") > 0);
    }

    @Test
    @DisplayName("Sprinting forwards and diagonally raises nothing")
    void forwardSprintIsQuiet() throws Exception {
        PlayerMock player = sprinter();
        run(player, 0, 1, 0f, 20);
        run(player, Math.sqrt(0.5), Math.sqrt(0.5), 0f, 20); // 45 degrees, W+A
        assertEquals(0, violations.count("SPRINT"));
    }

    @Test
    @DisplayName("The view angle follows Minecraft's yaw convention")
    void angleFromView() {
        assertEquals(0.0, MovementChecker.angleFromView(new Vector(0, 0, 1), 0f), 1e-6);
        assertEquals(180.0, MovementChecker.angleFromView(new Vector(0, 0, -1), 0f), 1e-6);
        // Yaw 90 looks along -X.
        assertEquals(0.0, MovementChecker.angleFromView(new Vector(-1, 0, 0), 90f), 1e-6);
        assertEquals(90.0, MovementChecker.angleFromView(new Vector(0, 0, 1), 90f), 1e-6);
    }
}
