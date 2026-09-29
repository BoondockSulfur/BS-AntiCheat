package dev.boondock.bsanticheat.anticheat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Movement checks driven through real event sequences.
 *
 * <p>Every scenario here is either a false positive that must stay quiet or the detection it
 * must not give up in exchange. Pairs matter more than individual
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
        // Mineshaft web holding the player, open cave below. At foot level the
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
        // Building a tower: each jump
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
    @DisplayName("A piston elevator does not raise FLY")
    void pistonPushExempt() throws InterruptedException {
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        for (int i = 0; i < 40; i++) {
            // A piston under the player pushing upwards, as an elevator does.
            firePistonAt(0, 78, 0, org.bukkit.block.BlockFace.UP);
            Location from = loc(0.5 + i * 0.01, 80.0, 0.5);
            player.teleport(from);
            checker.onPlayerMove(new PlayerMoveEvent(player, from, loc(0.5 + (i + 1) * 0.01, 80.0, 0.5)));
            tick();
        }
        assertEquals(0, violations.count("FLY"), "a piston displaces without any velocity packet");
    }

    @Test
    @DisplayName("A sideways piston clock beside a hovering player does not excuse the hover")
    void sidewaysClockDoesNotExemptHover() throws InterruptedException {
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        for (int i = 0; i < 40; i++) {
            firePistonAt(2, 80, 0, org.bukkit.block.BlockFace.EAST);
            Location from = loc(0.5 + i * 0.01, 80.0, 0.5);
            player.teleport(from);
            checker.onPlayerMove(new PlayerMoveEvent(player, from, loc(0.5 + (i + 1) * 0.01, 80.0, 0.5)));
            tick();
        }
        assertTrue(violations.count("FLY") > 0, "a horizontal push cannot hold anyone in the air");
    }

    private void firePistonAt(int x, int y, int z, org.bukkit.block.BlockFace face) {
        var piston = world.getBlockAt(x, y, z);
        piston.setType(Material.PISTON);
        var pushed = piston.getRelative(face);
        pistons.onPistonExtend(new org.bukkit.event.block.BlockPistonExtendEvent(
                piston, java.util.List.of(pushed), face));
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

    /** Push the player along +X by {@code perTick} each tick, with a piston firing beside them. */
    private void shove(PlayerMock player, double perTick, int ticks) throws InterruptedException {
        for (int i = 0; i < ticks; i++) {
            Location from = loc(0.5 + i * perTick, 80.0, 0.5);
            // The piston travels with the player — a flying machine, or a bolt of pistons
            // firing in sequence. A single stationary piston would (rightly) stop covering
            // them after a few blocks, which is the exemption working as intended.
            firePistonAt((int) from.getX(), 79, 0, org.bukkit.block.BlockFace.EAST);
            player.teleport(from);
            checker.onPlayerMove(new PlayerMoveEvent(player, from, loc(0.5 + (i + 1) * perTick, 80.0, 0.5)));
            Thread.sleep(50); // real ticks: the speed budget is judged against the clock
        }
    }

    @Test
    @DisplayName("A piston push does not raise SPEED")
    void pistonPushIsNotSpeed() throws InterruptedException {
        floor(79, Material.STONE);
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        // Walking plus one push's worth per tick.
        shove(player, 1.2, 20);
        assertEquals(0, violations.count("SPEED"), "being shoved is not moving yourself");
    }

    @Test
    @DisplayName("A piston clock does not excuse speed beyond what a push can add")
    void pistonDoesNotExcuseAnySpeed() throws InterruptedException {
        floor(79, Material.STONE);
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        shove(player, 3.0, 20);
        assertTrue(violations.count("SPEED") > 0, "no piston moves anyone 3 blocks a tick");
    }

    // ==================== SPEED in water ====================

    /**
     * A water channel along +X, tall enough to submerge the player, inside loaded chunks.
     * MockBukkit's isInWater() is a settable flag rather than a block lookup, so the caller
     * sets it on the player as well; on a real server the server derives it from these blocks.
     */
    private void waterChannel() {
        for (int x = -2; x <= 31; x++) {
            for (int z = -2; z <= 2; z++) {
                setBlock(x, 78, z, Material.STONE);
                for (int y = 79; y <= 81; y++) setBlock(x, y, z, Material.WATER);
            }
        }
    }

    private void giveDepthStrider(PlayerMock player, int level) {
        ItemStack boots = new ItemStack(Material.DIAMOND_BOOTS);
        boots.addUnsafeEnchantment(Enchantment.DEPTH_STRIDER, level);
        player.getInventory().setBoots(boots);
    }

    @Test
    @DisplayName("Depth Strider III plus Dolphin's Grace does not raise SPEED")
    void fastSwimmingIsQuiet() throws InterruptedException {
        // The player is in water the whole time, but isSwimming() is only true in the
        // horizontal swim POSE, so the water bonuses must apply without it.
        waterChannel();
        PlayerMock player = player(0.5, 80.0, 0.5);
        player.setInWater(true);
        giveDepthStrider(player, 3);
        player.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, 600, 0));
        clearGrace();
        Location[] path = new Location[20];
        for (int i = 0; i < 20; i++) path[i] = loc(0.5 + i * 0.77, 80.0, 0.5); // peak speed of the scenario
        walk(player, path);
        assertEquals(0, violations.count("SPEED"), "water physics were never applied");
    }

    @Test
    @DisplayName("Wading through water at walking speed does not raise SPEED")
    void wadingIsQuiet() throws InterruptedException {
        // The other half of the fix: the swim cap is 0.8x walking, so routing every in-water
        // move through it would flag someone strolling through a shallow pond. The cap must
        // never drop below the walking cap.
        waterChannel();
        PlayerMock player = player(0.5, 80.0, 0.5);
        player.setInWater(true);
        clearGrace();
        Location[] path = new Location[20];
        for (int i = 0; i < 20; i++) path[i] = loc(0.5 + i * 0.35, 80.0, 0.5);
        walk(player, path);
        assertEquals(0, violations.count("SPEED"), "0.35 b/t is under the walking cap");
    }

    @Test
    @DisplayName("Genuine speed hacking in water is still caught")
    void speedHackInWaterIsCaught() throws InterruptedException {
        // The exemption is only worth having if it is not a hiding place: 3 b/t is far above
        // anything Depth Strider and Dolphin's Grace together allow.
        waterChannel();
        PlayerMock player = player(0.5, 80.0, 0.5);
        player.setInWater(true);
        giveDepthStrider(player, 3);
        player.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, 600, 0));
        clearGrace();
        Location[] path = new Location[10];
        for (int i = 0; i < 10; i++) path[i] = loc(0.5 + i * 3.0, 80.0, 0.5);
        walk(player, path);
        assertTrue(violations.count("SPEED") > 0, "3 b/t is over even the swim cap");
    }

    @Test
    @DisplayName("Being in water does not switch off the on-foot checks")
    void waterDoesNotDisarmVerticalChecks() throws InterruptedException {
        // The water speed bonus is applied to the CAP, not by reclassifying the movement as
        // SWIMMING. That distinction matters: Step, Spider, GroundSpoof and the hover checks
        // are all gated on an on-foot movement type, so reclassifying would have handed
        // anyone standing in a puddle immunity from them.
        PlayerMock player = player(0.5, 80.0, 0.5);
        player.setInWater(true);
        clearGrace();
        walk(player, hover(80.0, 40));
        assertTrue(violations.count("FLY") > 0, "water must not disable the hover check");
    }

    /**
     * Drive a vertical profile: one move event per given dy, at a fixed X so the horizontal
     * checks stay quiet.
     */
    private void fall(PlayerMock player, double startY, double... dys) throws InterruptedException {
        double y = startY;
        // One throwaway event first: the very first move of a session has no baseline to be
        // judged against, so without it the profile is one sample short of the threshold and
        // the scenario proves nothing either way.
        Location seed = loc(0.5, y, 0.5);
        player.teleport(seed);
        checker.onPlayerMove(new PlayerMoveEvent(player, seed, loc(0.5, y, 0.5).add(0.001, 0, 0)));
        tick();
        for (int i = 0; i < dys.length; i++) {
            Location from = loc(0.5 + i * 0.01, y, 0.5);
            y += dys[i];
            Location to = loc(0.5 + (i + 1) * 0.01, y, 0.5);
            player.teleport(from);
            checker.onPlayerMove(new PlayerMoveEvent(player, from, to));
            tick();
        }
    }

    @Test
    @DisplayName("An arc through the still band is not hovering")
    void ballisticArcIsNotHover() {
        // Every sample sits inside the +-0.08 still band, so a plain count would take all ten
        // as hovering — but the sequence decays monotonically from +0.067 to -0.051, which is what
        // gravity does to a thrown player and not what holding an altitude looks like.
        PlayerMock player = player(0.5, 80.0, 0.5);
        try {
            clearGrace();
            fall(player, 80.0, 0.067, 0.075, 0.053, 0.025, 0.013,
                    -0.005, -0.023, -0.021, -0.063, -0.051);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertEquals(0, violations.count("FLY"),
                "a decaying arc is falling, however slowly");
    }

    @Test
    @DisplayName("A held altitude is still hovering")
    void heldAltitudeStillFlags() throws InterruptedException {
        // The other half: the drop rule must not disarm the check. A cheat holding a player
        // up keeps the vertical speed where it is, so nothing decays away.
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        double[] flat = new double[40];
        for (int i = 0; i < flat.length; i++) flat[i] = (i % 2 == 0) ? 0.004 : -0.004;
        fall(player, 80.0, flat);
        assertTrue(violations.count("FLY") > 0, "a held altitude is exactly what this catches");
    }

    @Test
    @DisplayName("A hover entered from a rise is still caught")
    void hoverEnteredFromRiseStillFlags() throws InterruptedException {
        // The gap the drop rule opened: it measures against the FIRST sample of the run and
        // that reference never moves, so a run which once exceeded the limit stayed silent
        // for its whole length. Entering the still band from a rise (+0.06) and then holding
        // -0.01 keeps the player inside the +-0.08 band indefinitely with a permanent drop of
        // 0.07 — a hover that descends 0.2 blocks a second and could never be flagged.
        // A restart of the run is what closes it, and it costs the arc above nothing.
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        double[] entry = {0.06, 0.03};
        double[] held = new double[40];
        java.util.Arrays.fill(held, -0.01);
        double[] samples = new double[entry.length + held.length];
        System.arraycopy(entry, 0, samples, 0, entry.length);
        System.arraycopy(held, 0, samples, entry.length, held.length);
        fall(player, 80.0, samples);
        assertTrue(violations.count("FLY") > 0,
                "a held descent of 0.01 per tick is a hover, not a fall");
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
