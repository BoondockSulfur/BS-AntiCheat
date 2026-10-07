package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.util.GameCompat;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sources of movement the checks must not misread: knockback reported only through
 * EntityKnockbackEvent (wind charges), bounce blocks, and the 26.2 physics attributes.
 */
class NewPhysicsSourcesTest extends ScenarioBase {

    private MovementChecker checker;

    @BeforeEach
    void setUpChecker() {
        checker = new MovementChecker(plugin, config, null, lang);
        checker.setViolationManager(violations);
        checker.setPistonTracker(new PistonTracker());
    }

    @AfterEach
    void resetAttributes() {
        GameCompat.overridePhysicsAttributes(null);
    }

    private void walk(PlayerMock player, Location... path) throws InterruptedException {
        for (int i = 1; i < path.length; i++) {
            player.teleport(path[i - 1]);
            checker.onPlayerMove(new PlayerMoveEvent(player, path[i - 1], path[i]));
            tick();
        }
    }

    private Location[] hover(double y, int samples) {
        Location[] path = new Location[samples];
        for (int i = 0; i < samples; i++) path[i] = loc(0.5 + i * 0.01, y, 0.5);
        return path;
    }

    // ==================== Knockback event ====================

    @Test
    @DisplayName("An explosion knockback without damage or velocity event grants the knockback grace")
    void windChargeKnockbackGrantsGrace() throws Exception {
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        checker.onEntityKnockback(new EntityKnockbackEvent(player, EntityKnockbackEvent.Cause.EXPLOSION,
                new Vector(0, 1.2, 0)));
        walk(player, hover(80.0, 40));
        assertEquals(0, violations.count("FLY"), "a wind charge launch is not flight");
    }

    @Test
    @DisplayName("The velocity check ignores a window another push overlapped")
    void velocityWindowOverlap() {
        VelocityChecker velocity = new VelocityChecker(plugin, config, null, lang);
        UUID id = UUID.randomUUID();
        long start = 1_000_000L;
        assertFalse(velocity.pushedAgain(id, start));
        // The hit's own knockback event, same tick: not a second push.
        velocity.noteKnockback(id, false, start + 5);
        assertFalse(velocity.pushedAgain(id, start));
        // A later hit inside the window.
        velocity.noteKnockback(id, false, start + 80);
        assertTrue(velocity.pushedAgain(id, start));
        // A wind charge just before: its push reaches the client with the explosion.
        UUID other = UUID.randomUUID();
        velocity.noteKnockback(other, true, start - 60);
        assertTrue(velocity.pushedAgain(other, start));
    }

    // ==================== Bounce blocks ====================

    @Test
    @DisplayName("Every bed colour and slime count as bounce blocks, stone does not")
    void bounceBlocks() {
        assertTrue(GameCompat.isBounceBlock(Material.SLIME_BLOCK));
        assertTrue(GameCompat.isBounceBlock(Material.RED_BED));
        assertTrue(GameCompat.isBounceBlock(Material.WHITE_BED));
        assertTrue(GameCompat.isBounceBlock(Material.BLACK_BED));
        assertFalse(GameCompat.isBounceBlock(Material.STONE));
        assertFalse(GameCompat.isBounceBlock(Material.HONEY_BLOCK));
    }

    @Test
    @DisplayName("A bed in the feet block is seen: beds are lower than a full block")
    void bedAtFeet() {
        setBlock(0, 80, 0, Material.RED_BED);
        assertTrue(MovementChecker.isNearBedBounceBlock(loc(0.5, 80.5625, 0.5)));
        assertFalse(MovementChecker.isNearBedBounceBlock(loc(5.5, 80.0, 5.5)));
    }

    /** Bounce off whatever sits at y=78, climb to 90 and hang there. */
    private void bounceThenHang(PlayerMock player) throws InterruptedException {
        Location[] path = new Location[54];
        int i = 0;
        // Ground contact first: the bounce block must be seen at a judged position.
        for (int k = 0; k < 3; k++) path[i++] = loc(0.5 - (2 - k) * 0.005, 80.0, 0.5);
        for (int k = 1; k <= 10; k++) path[i++] = loc(0.5 + k * 0.01, 80.0 + k, 0.5);
        for (int k = 0; k < 41; k++) path[i++] = loc(0.6 + k * 0.01, 90.0, 0.5);
        walk(player, path);
    }

    /** Rise from a bed at y=79 to 90 and hang there; with {@code fall}, drop onto it first. */
    private void bedLaunchThenHang(PlayerMock player, boolean fall) throws InterruptedException {
        java.util.List<Location> path = new java.util.ArrayList<>();
        double top = 79.5625;
        if (fall) {
            for (int k = 0; k < 10; k++) path.add(loc(0.45 + k * 0.005, 95.0 - k * 1.5, 0.5));
        } else {
            for (int k = 0; k < 3; k++) path.add(loc(0.49 + k * 0.005, top, 0.5));
        }
        path.add(loc(0.5, top, 0.5));
        for (int k = 1; k <= 10; k++) path.add(loc(0.5 + k * 0.01, top + k * 1.05, 0.5));
        for (int k = 0; k < 41; k++) path.add(loc(0.6 + k * 0.01, 90.0, 0.5));
        walk(player, path.toArray(new Location[0]));
    }

    @Test
    @DisplayName("After falling onto a bed and bouncing off it the launch grace covers the flight")
    void bedBounceIsQuiet() throws Exception {
        setBlock(0, 79, 0, Material.RED_BED);
        PlayerMock player = player(0.45, 95.0, 0.5);
        clearGrace();
        bedLaunchThenHang(player, true);
        assertEquals(0, violations.count("FLY"));
    }

    @Test
    @DisplayName("Rising off a bed without a fall onto it first raises FLY")
    void bedWithoutFallIsCaught() throws Exception {
        setBlock(0, 79, 0, Material.RED_BED);
        PlayerMock player = player(0.49, 79.5625, 0.5);
        clearGrace();
        bedLaunchThenHang(player, false);
        assertTrue(violations.count("FLY") > 0, "standing on a bed is no bounce");
    }

    @Test
    @DisplayName("A floor of beds does not excuse horizontal speed")
    void bedFloorDoesNotExcuseSpeed() throws Exception {
        for (int x = -1; x <= 20; x++) for (int z = -1; z <= 1; z++) setBlock(x, 79, z, Material.RED_BED);
        PlayerMock player = player(0.5, 79.5625, 0.5);
        clearGrace();
        for (int i = 0; i < 14; i++) {
            Location from = loc(0.5 + i * 1.3, 79.5625, 0.5);
            player.teleport(from);
            checker.onPlayerMove(new PlayerMoveEvent(player, from, loc(0.5 + (i + 1) * 1.3, 79.5625, 0.5)));
            Thread.sleep(50);
        }
        assertTrue(violations.count("SPEED") > 0);
    }

    @Test
    @DisplayName("The same flight off a stone block raises FLY")
    void stoneLaunchIsCaught() throws Exception {
        setBlock(0, 78, 0, Material.STONE);
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        bounceThenHang(player);
        assertTrue(violations.count("FLY") > 0);
    }

    @Test
    @DisplayName("Without potent sulfur on this server no geyser is ever found")
    void geyserAbsentOnOldApi() {
        org.junit.jupiter.api.Assumptions.assumeTrue(GameCompat.POTENT_SULFUR == null);
        setBlock(0, 79, 0, Material.WATER);
        assertFalse(MovementChecker.isInGeyser(loc(0.5, 81.0, 0.5)));
    }

    // ==================== Physics attributes ====================

    @Test
    @DisplayName("A modified physics attribute exempts the gravity model and the speed caps")
    void modifiedPhysicsIsExempt() throws Exception {
        // Stand-in: the mock server predates the 26.2 attributes and models only a few, so
        // max_health plays their part through the same default comparison.
        GameCompat.overridePhysicsAttributes(List.of(Attribute.MAX_HEALTH));
        PlayerMock player = player(0.5, 80.0, 0.5);
        player.getAttribute(Attribute.MAX_HEALTH).setBaseValue(40.0);
        clearGrace();
        walk(player, hover(80.0, 40));
        assertEquals(0, violations.count("FLY"));
    }

    @Test
    @DisplayName("At their default the physics attributes change nothing")
    void defaultPhysicsIsJudged() throws Exception {
        GameCompat.overridePhysicsAttributes(List.of(Attribute.MAX_HEALTH));
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        walk(player, hover(80.0, 40));
        assertTrue(violations.count("FLY") > 0);
    }
}
