package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.lang.LanguageManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared rig for scenario tests: a mock server, a loaded world and the collaborators a
 * checker needs, assembled without booting the plugin.
 *
 * <p>The plugin cannot be loaded under MockBukkit — {@code onEnable} reaches for Folia's
 * region schedulers, which MockBukkit does not implement — so each test builds the checker it
 * needs directly. The violation manager is replaced by one that only counts, which keeps the
 * scheduler out of the picture and makes the outcome of a scenario directly observable.
 *
 * <p>A note on reading these tests: a scenario that passes proves nothing on its own. Each
 * one was verified by breaking the rule it covers and confirming it goes red — an exemption
 * modelled slightly wrong can be satisfied by an unrelated code path and stay green forever.
 */
abstract class ScenarioBase {

    protected ServerMock server;
    protected World world;
    protected JavaPlugin plugin;
    protected PluginConfig config;
    protected LanguageManager lang;
    protected CountingViolations violations;

    /** Counts flags per check instead of raising violation levels. */
    protected static class CountingViolations extends ViolationManager {
        final Map<String, Integer> flags = new HashMap<>();

        CountingViolations(JavaPlugin plugin, PluginConfig config) {
            super(plugin, config);
        }

        @Override
        public int flag(Player player, String checkType) {
            return flags.merge(checkType, 1, Integer::sum);
        }

        int count(String checkType) {
            return flags.getOrDefault(checkType, 0);
        }

        void reset() {
            flags.clear();
        }
    }

    @BeforeEach
    void baseSetUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("scenario");
        // Checks that scan blocks stand down in unloaded chunks, so the test area is loaded.
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) world.loadChunk(cx, cz);
        }
        plugin = MockBukkit.createMockPlugin("BSAntiCheat");
        config = new PluginConfig(plugin);
        lang = new LanguageManager(plugin, "en");
        violations = new CountingViolations(plugin, config);
    }

    @AfterEach
    void baseTearDown() {
        MockBukkit.unmock();
    }

    /** A survival player with no bypass, at the given position. */
    protected PlayerMock player(double x, double y, double z) {
        PlayerMock player = server.addPlayer();
        player.setGameMode(GameMode.SURVIVAL);
        player.setOp(false);
        player.teleport(new Location(world, x, y, z));
        return player;
    }

    protected Location loc(double x, double y, double z) {
        return new Location(world, x, y, z);
    }

    protected void setBlock(int x, int y, int z, Material material) {
        world.getBlockAt(x, y, z).setType(material);
    }

    /** A solid floor at the given Y across the test area. */
    protected void floor(int y, Material material) {
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) setBlock(x, y, z, material);
        }
    }

    /**
     * Wait past the join/teleport grace windows the checks apply, so a scenario is judged
     * rather than waved through.
     */
    protected void clearGrace() throws InterruptedException {
        Thread.sleep(1100);
    }

    /** A short pause so consecutive samples are far enough apart to be judged. */
    protected void tick() throws InterruptedException {
        Thread.sleep(12);
    }
}
