package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.lang.LanguageManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Movement checks driven through a real sequence of move events, rather than by calling a
 * helper directly. This is the level at which the hover false positives actually happened:
 * no single sample is wrong, the counter just never resets.
 *
 * <p>The plugin itself cannot be booted under MockBukkit — onEnable reaches for Folia's
 * region schedulers, which it does not implement — so the checker is assembled directly and
 * given a violation manager that only counts, which keeps the scheduler out of the picture.
 */
class MovementSequenceTest {

    private ServerMock server;
    private World world;
    private MovementChecker checker;
    private CountingViolations violations;

    /** Records flags instead of raising violation levels, so no scheduler is involved. */
    private static class CountingViolations extends ViolationManager {
        final Map<String, Integer> flags = new HashMap<>();

        CountingViolations(JavaPlugin plugin, PluginConfig config) {
            super(plugin, config);
        }

        @Override
        public int flag(Player player, String checkType) {
            return flags.merge(checkType, 1, Integer::sum);
        }
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("moves");
        // The vertical checks stand down in unloaded chunks (a scan there would find no
        // blocks and read as "airborne"), so the test area has to be loaded — and the
        // neighbouring chunks with it, because the scans reach one block sideways and the
        // paths below run along x=0/z=0, right on a chunk border. Same 3x3 as ScenarioBase.
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) world.loadChunk(cx, cz);
        }
        JavaPlugin plugin = MockBukkit.createMockPlugin("BSAntiCheat");
        PluginConfig config = new PluginConfig(plugin);
        LanguageManager lang = new LanguageManager(plugin, "en");
        checker = new MovementChecker(plugin, config, null, lang);
        violations = new CountingViolations(plugin, config);
        checker.setViolationManager(violations);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /**
     * Walk a player through a series of positions, one move event each, with a small pause so
     * the checker's minimum sample interval is satisfied.
     */
    private void move(Player player, Location[] path) throws InterruptedException {
        for (int i = 1; i < path.length; i++) {
            player.teleport(path[i - 1]);
            checker.onPlayerMove(new PlayerMoveEvent(player, path[i - 1], path[i]));
            Thread.sleep(15);
        }
    }

    /** Hovering in place: same spot, no descent, nothing underneath. */
    private Location[] hoverPath(int samples) {
        Location[] path = new Location[samples];
        for (int i = 0; i < samples; i++) {
            path[i] = new Location(world, 0.5 + i * 0.01, 80.0, 0.5);
        }
        return path;
    }

    @Test
    @DisplayName("Hanging in mid-air over a void raises FLY")
    void sustainedHoverIsFlagged() throws InterruptedException {
        PlayerMock player = server.addPlayer();
        player.setGameMode(org.bukkit.GameMode.SURVIVAL);
        player.setOp(false);
        Location[] path = hoverPath(40);
        player.teleport(path[0]);
        // The checker ignores the first move after a teleport, so the run is long enough
        // to clear the grace window and still accumulate the hover counter.
        Thread.sleep(1100);
        move(player, path);

        assertTrue(violations.flags.getOrDefault("FLY", 0) > 0,
                "a player hanging in the air with nothing below them should raise FLY");
    }

    @Test
    @DisplayName("The same sequence inside a cobweb raises nothing")
    void hoverInsideCobwebIsExempt() throws InterruptedException {
        PlayerMock player = server.addPlayer();
        player.setGameMode(org.bukkit.GameMode.SURVIVAL);
        player.setOp(false);
        // The live false positive, modelled exactly: the web sits at BODY height with open
        // cave below, so the downward support scan finds nothing and the player reads as
        // airborne — while the web slows their descent below the rate that counts as
        // falling. Putting the web at foot level instead would prove nothing: it would
        // register as footing via isSupportive and the exemption would never be consulted.
        world.getBlockAt(0, 81, 0).setType(Material.COBWEB);
        Location[] path = hoverPath(40);
        player.teleport(path[0]);
        Thread.sleep(1100);
        move(player, path);

        assertEquals(0, violations.flags.getOrDefault("FLY", 0),
                "cobwebs must exempt the hover check");
    }
}
