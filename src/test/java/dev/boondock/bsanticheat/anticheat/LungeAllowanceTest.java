package dev.boondock.bsanticheat.anticheat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spear lunges: the forward throw of a Lunge stab is displacement without a velocity packet,
 * so SPEED and the speed budget allow for it — once per stab, and only briefly.
 */
class LungeAllowanceTest extends ScenarioBase {

    private MovementChecker checker;
    private LungeTracker lunges;

    @BeforeEach
    void setUpChecker() {
        checker = new MovementChecker(plugin, config, null, lang);
        checker.setViolationManager(violations);
        checker.setPistonTracker(new PistonTracker());
        lunges = new LungeTracker();
        checker.setLungeTracker(lunges);
        floor(79, Material.STONE);
    }

    /** Move along +X at {@code perTick}, one event per real tick. */
    private void dash(PlayerMock player, double perTick, int ticks) throws InterruptedException {
        for (int i = 0; i < ticks; i++) {
            Location from = loc(0.5 + i * perTick, 80.0, 0.5);
            player.teleport(from);
            checker.onPlayerMove(new PlayerMoveEvent(player, from, loc(0.5 + (i + 1) * perTick, 80.0, 0.5)));
            Thread.sleep(50);
        }
    }

    @Test
    @DisplayName("A lunge's forward throw does not raise SPEED")
    void lungeIsQuiet() throws Exception {
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        dash(player, 0.2, 2); // baseline
        lunges.noteLunge(player.getUniqueId(), System.currentTimeMillis(), 3);
        dash(player, 1.3, 12);
        assertEquals(0, violations.count("SPEED"), "Lunge III throws the player 1.4+ blocks per tick");
    }

    @Test
    @DisplayName("The same movement without a lunge raises SPEED")
    void withoutLungeIsCaught() throws Exception {
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        dash(player, 0.2, 2);
        dash(player, 1.3, 12);
        assertTrue(violations.count("SPEED") > 0);
    }

    @Test
    @DisplayName("The allowance ends with the lunge window")
    void allowanceExpires() throws Exception {
        PlayerMock player = player(0.5, 80.0, 0.5);
        clearGrace();
        lunges.noteLunge(player.getUniqueId(), System.currentTimeMillis() - 5000, 3);
        dash(player, 0.2, 2);
        dash(player, 1.3, 12);
        assertTrue(violations.count("SPEED") > 0, "a lunge five seconds ago explains nothing now");
    }

    @Test
    @DisplayName("No allowance in water, where vanilla applies no lunge")
    void noLungeInWater() {
        PlayerMock player = player(0.5, 80.0, 0.5);
        long now = System.currentTimeMillis();
        lunges.noteLunge(player.getUniqueId(), now, 3);
        player.setInWater(true);
        assertEquals(0.0, checker.lungeAllowance(player, player.getUniqueId(), now));
        player.setInWater(false);
        assertTrue(checker.lungeAllowance(player, player.getUniqueId(), now) > 1.0);
    }

    @Test
    @DisplayName("A burst of stabs does not keep restarting the window")
    void stabSpamIsOneLunge() {
        PlayerMock player = player(0.5, 80.0, 0.5);
        long t = System.currentTimeMillis();
        lunges.noteLunge(player.getUniqueId(), t, 2);
        checker.lungeAllowance(player, player.getUniqueId(), t);
        // Stabs every 100 ms: inside the spacing, so they belong to the first lunge.
        for (int i = 1; i <= 9; i++) {
            lunges.noteLunge(player.getUniqueId(), t + i * 100L, 2);
            checker.lungeAllowance(player, player.getUniqueId(), t + i * 100L);
        }
        assertEquals(0.0, checker.lungeAllowance(player, player.getUniqueId(), t + 900),
                "the window counts from the first stab of the burst");
    }

    @Test
    @DisplayName("A stab after the spacing starts a new window")
    void separateStabsEachCount() {
        PlayerMock player = player(0.5, 80.0, 0.5);
        long t = System.currentTimeMillis();
        lunges.noteLunge(player.getUniqueId(), t, 2);
        checker.lungeAllowance(player, player.getUniqueId(), t);
        lunges.noteLunge(player.getUniqueId(), t + 600, 2);
        assertTrue(checker.lungeAllowance(player, player.getUniqueId(), t + 900) > 0.0);
    }
}
