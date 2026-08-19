package dev.boondock.bsanticheat.anticheat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rising is not hovering.
 *
 * <p>The hover check used to count every sample that was not FALLING, which made an ascent
 * indistinguishable from hanging in the air. Live data: four alerts fired while the player was
 * moving UP at 0.12-0.20 b/t, in a Trial Chamber — where a Breeze, a wind charge or a Wind
 * Burst mace throws players upwards for longer than the 2 s knockback grace lasts.
 *
 * <p>What separates the two is gravity: a thrown player sheds ~0.08 b/t of vertical speed every
 * tick and comes back down; a flight cheat holds the climb. The hover check now counts only
 * genuine hanging, and the opt-in sustained-ascent check watches the climb that does not decay.
 */
class AscentScenarioTest extends ScenarioBase {

    private MovementChecker checker;

    @BeforeEach
    void setUpChecker() {
        checker = new MovementChecker(plugin, config, null, lang);
        checker.setViolationManager(violations);
        checker.setPistonTracker(new PistonTracker());
        // Well below the player: every sample is "clearly airborne", which is what the
        // vertical checks require before they judge anything.
        floor(60, Material.STONE);
    }

    /** Turn the opt-in check on the way an admin would: edit the file, reload. */
    private void enableSustainedAscent() throws Exception {
        File file = new File(plugin.getDataFolder(), "config.yml");
        Files.writeString(file.toPath(), Files.readString(file.toPath())
                .replace("sustained_ascent_detection: false", "sustained_ascent_detection: true"));
        config.reload();
        assertTrue(config.sustainedAscentDetectionEnabled(), "fixture check: the check is on");
    }

    /**
     * A climb, one sample per move event.
     *
     * @param startDy vertical speed of the first sample, in blocks per tick
     * @param decay   how much of it is shed each tick — 0.08 is vanilla gravity, 0 is a climb
     *                nothing is pulling on
     */
    private void climb(PlayerMock player, double startDy, double decay, int samples) throws InterruptedException {
        double y = 100.0;
        double dy = startDy;
        double x = 0.5;
        Location previous = loc(x, y, 0.5);
        player.teleport(previous);
        for (int i = 0; i < samples; i++) {
            y += dy;
            // A hair of horizontal drift, as the existing hover fixtures use: a move event
            // whose from and to are identical is dropped before any check sees it, so a
            // perfectly motionless hover would never be sampled at all.
            x += 0.01;
            Location next = loc(x, y, 0.5);
            player.teleport(previous);
            checker.onPlayerMove(new PlayerMoveEvent(player, previous, next));
            previous = next;
            dy = Math.max(0.0, dy - decay);
            tick();
        }
    }

    @Test
    @DisplayName("Being thrown upwards does not raise FLY")
    void ballisticAscentIsNotHover() throws Exception {
        PlayerMock player = player(0.5, 100.0, 0.5);
        clearGrace();
        // A wind charge / Wind Burst launch: fast at first, slowing by one tick of gravity
        // each tick, exactly as the logged 0.20 → 0.12 → 0.07 tail did.
        climb(player, 1.0, 0.08, 14);
        assertEquals(0, violations.count("FLY"), "an arc that gravity explains is not flight");
    }

    @Test
    @DisplayName("Genuine hanging still raises FLY")
    void hoveringIsStillCaught() throws Exception {
        PlayerMock player = player(0.5, 100.0, 0.5);
        clearGrace();
        // Neither rising nor falling — the thing the check is actually named after.
        climb(player, 0.0, 0.0, 20);
        assertTrue(violations.count("FLY") > 0, "narrowing the band must not disarm the check");
    }

    @Test
    @DisplayName("A climb that never slows raises nothing while the check is off")
    void sustainedAscentIsOptIn() throws Exception {
        PlayerMock player = player(0.5, 100.0, 0.5);
        clearGrace();
        climb(player, 0.2, 0.0, 20);
        assertEquals(0, violations.count("FLY"), "the check ships off by default");
    }

    @Test
    @DisplayName("Once enabled, a climb that never slows raises FLY")
    void sustainedAscentIsCaughtWhenEnabled() throws Exception {
        enableSustainedAscent();
        PlayerMock player = player(0.5, 100.0, 0.5);
        clearGrace();
        climb(player, 0.2, 0.0, 20);
        assertTrue(violations.count("FLY") > 0, "nothing is pulling this player back down");
    }

    @Test
    @DisplayName("Even enabled, it stays quiet for an arc that decays")
    void sustainedAscentIgnoresBallisticRises() throws Exception {
        enableSustainedAscent();
        PlayerMock player = player(0.5, 100.0, 0.5);
        clearGrace();
        climb(player, 1.0, 0.08, 14);
        assertEquals(0, violations.count("FLY"), "the decay is the whole signal");
    }
}
