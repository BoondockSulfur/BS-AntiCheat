package dev.boondock.bsanticheat.anticheat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Movement checks driven through real event sequences.
 *
 * <p>Every scenario here corresponds to something that actually happened on the live server,
 * or to the detection it must not give up in exchange. Pairs matter more than individual
 * cases: an exemption that silences a false positive is only worth having if the matching
 * "…but this is still caught" case passes alongside it.
 */
class MovementScenarioTest extends ScenarioBase {

    private MovementChecker checker;
    private PistonTracker pistons;

    @BeforeEach
    void setUpChecker() {
        checker = new MovementChecker(plugin, config, null, lang);
        checker.setViolationManager(violations);
        pistons = new PistonTracker();
        checker.setPistonTracker(pistons);
    }

    /** Feed a path through the checker, one move event per step. */
    private void walk(PlayerMock player, Location... path) throws InterruptedException {
        for (int i = 1; i < path.length; i++) {
            player.teleport(path[i - 1]);
            checker.onPlayerMove(new PlayerMoveEvent(player, path[i - 1], path[i]));
            tick();
        }
    }

    /** Hovering in place at a fixed height, drifting imperceptibly so events fire. */
    private Location[] hover(double y, int samples) {
        Location[] path = new Location[samples];
        for (int i = 0; i < samples; i++) path[i] = loc(0.5 + i * 0.01, y, 0.5);
        return path;
    }

    // ==================== FLY / hover ====================

    @Test
    @DisplayName("Hovering over open air raises FLY")
    void hoverIsCaught() throws InterruptedException {
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        walk(player, hover(80.0, 40));
        assertTrue(violations.count("FLY") > 0, "sustained hover must be detected");
    }

    @Test
    @DisplayName("A cobweb at body height exempts the hover check")
    void cobwebExempts() throws InterruptedException {
        // Live case: mineshaft web holding the player, open cave below. At foot level the
        // web would count as footing via isSupportive and prove nothing, so it sits above.
        setBlock(0, 81, 0, Material.COBWEB);
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        walk(player, hover(80.0, 40));
        assertEquals(0, violations.count("FLY"), "cobwebs slow a fall below the falling rate");
    }

    @Test
    @DisplayName("A honey wall beside the player exempts the hover check")
    void honeyWallExempts() throws InterruptedException {
        setBlock(1, 80, 0, Material.HONEY_BLOCK);
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        walk(player, hover(80.0, 40));
        assertEquals(0, violations.count("FLY"), "sliding down honey is not hovering");
    }

    @Test
    @DisplayName("Towering up does not raise FLY")
    void pillaringIsExempt() throws InterruptedException {
        // The live case: 31 hover alerts while the player built a clay tower. Each jump
        // places a block underfoot which catches them before gravity shows, so the fall the
        // hover check waits for never arrives.
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        for (int i = 0; i < 40; i++) {
            Location from = loc(0.5, 80.0, 0.5);
            Location to = loc(0.5, 80.0, 0.5);
            player.teleport(from);
            // A block placed under the player's own feet, as pillaring does.
            setBlock(0, 79, 0, Material.CLAY);
            checker.onBlockPlace(new BlockPlaceEvent(
                    world.getBlockAt(0, 79, 0), world.getBlockAt(0, 79, 0).getState(),
                    world.getBlockAt(0, 78, 0), player.getInventory().getItemInMainHand(),
                    player, true, org.bukkit.inventory.EquipmentSlot.HAND));
            setBlock(0, 79, 0, Material.AIR); // keep the ground scan seeing air
            checker.onPlayerMove(new PlayerMoveEvent(player, from, to.clone().add(0.01 * i, 0, 0)));
            tick();
        }
        assertEquals(0, violations.count("FLY"), "placing your own footing is not flight");
    }

    @Test
    @DisplayName("A piston push does not raise FLY")
    void pistonPushExempt() throws InterruptedException {
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        for (int i = 0; i < 40; i++) {
            firePistonAt(0, 80, 0);
            Location from = loc(0.5 + i * 0.01, 80.0, 0.5);
            player.teleport(from);
            checker.onPlayerMove(new PlayerMoveEvent(player, from, loc(0.5 + (i + 1) * 0.01, 80.0, 0.5)));
            tick();
        }
        assertEquals(0, violations.count("FLY"), "a piston displaces without any velocity packet");
    }

    private void firePistonAt(int x, int y, int z) {
        var piston = world.getBlockAt(x, y, z);
        piston.setType(Material.PISTON);
        var pushed = world.getBlockAt(x + 1, y, z);
        pistons.onPistonExtend(new org.bukkit.event.block.BlockPistonExtendEvent(
                piston, java.util.List.of(pushed), org.bukkit.block.BlockFace.EAST));
        piston.setType(Material.AIR);
    }

    // ==================== SPEED ====================

    @Test
    @DisplayName("Running far faster than walking speed raises SPEED")
    void speedIsCaught() throws InterruptedException {
        floor(79, Material.STONE);
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        Location[] path = new Location[20];
        for (int i = 0; i < 20; i++) path[i] = loc(0.5 + i * 1.5, 80.0, 0.5); // 1.5 b/tick
        walk(player, path);
        assertTrue(violations.count("SPEED") > 0, "1.5 blocks per move is far over the walking cap");
    }

    @Test
    @DisplayName("Walking at normal speed raises nothing")
    void normalWalkingIsQuiet() throws InterruptedException {
        floor(79, Material.STONE);
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        Location[] path = new Location[20];
        for (int i = 0; i < 20; i++) path[i] = loc(0.5 + i * 0.2, 80.0, 0.5); // 0.2 b/tick
        walk(player, path);
        assertEquals(0, violations.count("SPEED"));
        assertEquals(0, violations.count("FLY"));
    }

    @Test
    @DisplayName("A piston push does not raise SPEED")
    void pistonPushIsNotSpeed() throws InterruptedException {
        floor(79, Material.STONE);
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        for (int i = 0; i < 20; i++) {
            Location from = loc(0.5 + i * 1.5, 80.0, 0.5);
            // The piston travels with the player — a flying machine, or a bolt of pistons
            // firing in sequence. A single stationary piston would (rightly) stop covering
            // them after a few blocks, which is the exemption working as intended.
            firePistonAt((int) from.getX(), 79, 0);
            player.teleport(from);
            checker.onPlayerMove(new PlayerMoveEvent(player, from, loc(0.5 + (i + 1) * 1.5, 80.0, 0.5)));
            tick();
        }
        assertEquals(0, violations.count("SPEED"), "being shoved is not moving yourself");
    }

    // ==================== TELEPORT ====================

    @Test
    @DisplayName("A huge single step raises TELEPORT")
    void teleportLikeMoveIsCaught() throws InterruptedException {
        floor(79, Material.STONE);
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        Location from = loc(0.5, 80.0, 0.5);
        Location to = loc(200.5, 80.0, 0.5); // far past the 15-block threshold
        player.teleport(from);
        checker.onPlayerMove(new PlayerMoveEvent(player, from, to));
        tick();
        checker.onPlayerMove(new PlayerMoveEvent(player, to, loc(400.5, 80.0, 0.5)));
        assertTrue(violations.count("TELEPORT") > 0, "a 200-block step is not movement");
    }

    // ==================== exemptions that must NOT swallow everything ====================

    @Test
    @DisplayName("The chunk guard does not silence loaded areas")
    void chunkGuardDoesNotSilenceLoadedChunks() throws InterruptedException {
        // The guard exists so unloaded chunks are not read as "nothing below the player".
        // It must not become a blanket exemption where the world IS loaded.
        assertTrue(world.isChunkLoaded(0, 0));
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        walk(player, hover(80.0, 40));
        assertTrue(violations.count("FLY") > 0);
    }

    // ==================== a move event is not one tick ====================

    /** Feed a path through the checker with a chosen pause between events. */
    private void walkPaced(PlayerMock player, long pauseMs, Location... path) throws InterruptedException {
        for (int i = 1; i < path.length; i++) {
            player.teleport(path[i - 1]);
            checker.onPlayerMove(new PlayerMoveEvent(player, path[i - 1], path[i]));
            Thread.sleep(pauseMs);
        }
    }

    @Test
    @DisplayName("Move events arriving slowly are judged per tick, not per event")
    void slowlyArrivingPacketsAreNotSpeed() throws InterruptedException {
        // The same 1.5 blocks per event that speedIsCaught flags — but spread over four
        // ticks of real time each, which is what a client on a poor connection delivers.
        // Per tick that is 0.36 blocks, comfortably inside the walking cap: the player
        // covered the ground at walking pace, the packets simply arrived in fewer pieces.
        // Judged per event it reads as 1.5 b/t and flags, which is the bug this holds shut.
        floor(79, Material.STONE);
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        Location[] path = new Location[10];
        for (int i = 0; i < 10; i++) path[i] = loc(0.5 + i * 1.5, 80.0, 0.5);
        walkPaced(player, 210, path); // ~4.2 ticks per event
        assertEquals(0, violations.count("SPEED"), "slow packets are not a fast player");
    }

    @Test
    @DisplayName("The catch-up move after a packet gap is not judged at all")
    void packetGapIsNotTeleport() throws InterruptedException {
        // A stalled connection sends nothing for a while and then flushes its backlog. The
        // resulting single event carries everything the player did meanwhile — far enough
        // to cross the teleport threshold, which no per-tick scaling can rescue. It must be
        // skipped outright. The same step without the gap does flag: teleportLikeMoveIsCaught.
        floor(79, Material.STONE);
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        Location start = loc(0.5, 80.0, 0.5);
        Location afterStall = loc(20.5, 80.0, 0.5);
        player.teleport(start);
        checker.onPlayerMove(new PlayerMoveEvent(player, start, loc(0.7, 80.0, 0.5)));
        Thread.sleep(600); // longer than Constants.MOVEMENT_MAX_GAP_MS
        checker.onPlayerMove(new PlayerMoveEvent(player, loc(0.7, 80.0, 0.5), afterStall));
        assertEquals(0, violations.count("TELEPORT"), "a backlog flush is not a teleport");
        assertEquals(0, violations.count("SPEED"));
    }
}
