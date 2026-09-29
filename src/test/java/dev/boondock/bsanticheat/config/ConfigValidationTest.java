package dev.boondock.bsanticheat.config;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Out-of-range numbers are replaced by their default instead of silently breaking a check. */
class ConfigValidationTest {

    private JavaPlugin plugin;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("BSAntiCheat");
        try (InputStream is = plugin.getResource("config.yml")) {
            plugin.getDataFolder().mkdirs();
            Files.write(new File(plugin.getDataFolder(), "config.yml").toPath(), is.readAllBytes());
        }
        plugin.reloadConfig();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("Zero, negative and nonsense values fall back to the default")
    void invalidValuesAreRepaired() {
        plugin.getConfig().set("anticheat.reach_distance", 0.0);
        plugin.getConfig().set("anticheat.killaura_max_angle", -5.0);
        plugin.getConfig().set("anticheat.nuker_max_breaks_per_second", 0);
        plugin.getConfig().set("anticheat.thresholds.reach_violations", 0);
        plugin.getConfig().set("anticheat.punishments.decay_seconds", -1);
        plugin.getConfig().set("database.retention_days", -3);
        plugin.getConfig().set("database.pool.max_pool_size", 0);
        plugin.getConfig().set("anticheat.xray_thresholds.diamond", 0);
        plugin.getConfig().set("anticheat.aimsnap_window_ms", "soon");

        PluginConfig config = new PluginConfig(plugin);

        assertEquals(4.0, config.reachDistance());
        assertEquals(75.0, config.killAuraMaxAngle());
        assertEquals(25, config.nukerMaxBreaksPerSecond());
        assertEquals(3, config.reachViolations());
        assertEquals(300, config.punishmentsDecaySeconds());
        assertEquals(30, config.databaseRetentionDays());
        assertEquals(5, config.poolMax());
        assertEquals(10, config.xrayThreshold("DIAMOND_ORE"));
        assertEquals(3000L, config.aimSnapWindowMs());
    }

    @Test
    @DisplayName("Documented special values are kept")
    void specialValuesAreKept() {
        plugin.getConfig().set("database.retention_days", 0);        // keep forever
        plugin.getConfig().set("anticheat.timer_sustained_ms", 0);   // flag on first crossing
        plugin.getConfig().set("anticheat.punishments.decay_seconds", 0);

        PluginConfig config = new PluginConfig(plugin);

        assertEquals(0, config.databaseRetentionDays());
        assertEquals(0L, config.timerSustainedMs());
        assertEquals(0, config.punishmentsDecaySeconds());
    }

    @Test
    @DisplayName("The whitelist group list is cached and replaced only when it changes")
    void whitelistGroupsAreCached() {
        plugin.getConfig().set("anticheat.whitelist_groups", List.of("staff"));
        PluginConfig config = new PluginConfig(plugin);

        List<String> first = config.anticheatWhitelistGroups();
        assertSame(first, config.anticheatWhitelistGroups(), "no allocation per call");
        assertEquals(List.of("staff"), first);

        config.addWhitelistGroup("mods");
        assertEquals(List.of("staff", "mods"), config.anticheatWhitelistGroups());
        config.saveSyncOnShutdown();
    }
}
