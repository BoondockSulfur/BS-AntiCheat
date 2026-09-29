package dev.boondock.bsanticheat.config;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Import from PerformanceAnalyzer: only the legacy combined plugin's anticheat settings,
 * never a current PerformanceAnalyzer's own webhook, silent list or language.
 */
class ConfigMigratorTest {

    private JavaPlugin plugin;
    private File configFile;
    private File paConfig;

    /** A current PerformanceAnalyzer: no anticheat section, its own discord/alerts features. */
    private static final String MODERN_PA = """
            language: de
            discord:
              enabled: true
              webhook_url: "https://discord.com/api/webhooks/1/pa"
              alert_types:
                incident_opened: true
                incident_escalated: true
            alerts:
              silent_players:
                - "00000000-0000-0000-0000-000000000001"
            """;

    /** The legacy plugin that still contained the anticheat. */
    private static final String LEGACY_PA = """
            language: de
            anticheat:
              reach_distance: 5.5
              removed_legacy_setting: 7
              whitelist_players:
                - "00000000-0000-0000-0000-000000000002"
              xray_thresholds:
                diamond: 4
            discord:
              enabled: true
              webhook_url: "https://discord.com/api/webhooks/1/ac"
              alert_types:
                xray: false
                movement: true
                incident_opened: true
            alerts:
              silent_players:
                - "00000000-0000-0000-0000-000000000001"
            """;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("BSAntiCheat");
        plugin.getDataFolder().mkdirs();
        configFile = new File(plugin.getDataFolder(), "config.yml");
        File paDir = new File(plugin.getDataFolder().getParentFile(), "PerformanceAnalyzer");
        paDir.mkdirs();
        paConfig = new File(paDir, "config.yml");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
        paConfig.delete();
    }

    /** Fresh install: the shipped default written out, as saveDefaultConfig() would. */
    private void installDefaultConfig() throws Exception {
        try (InputStream is = plugin.getResource("config.yml")) {
            Files.write(configFile.toPath(), is.readAllBytes());
        }
        plugin.reloadConfig();
    }

    private String contents() throws Exception {
        return Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("A current PerformanceAnalyzer next to a fresh install imports nothing")
    void modernPerformanceAnalyzerIsIgnored() throws Exception {
        Files.writeString(paConfig.toPath(), MODERN_PA);
        installDefaultConfig();
        String before = contents();

        boolean migrated = new ConfigMigrator(plugin).migrateFromPerformanceAnalyzer(true);

        assertFalse(migrated);
        assertEquals(before, contents(), "the file must not be touched");
        PluginConfig config = new PluginConfig(plugin);
        assertEquals("", config.discordWebhookUrl(), "PerformanceAnalyzer's webhook must not be adopted");
        assertFalse(config.discordEnabled());
        assertTrue(config.silentPlayers().isEmpty(), "PerformanceAnalyzer's silenced players must not mute anticheat alerts");
        assertEquals("en", config.language());
    }

    @Test
    @DisplayName("A legacy config migrates its anticheat settings, and only those")
    void legacyAnticheatSectionIsMigrated() throws Exception {
        Files.writeString(paConfig.toPath(), LEGACY_PA);
        installDefaultConfig();

        assertTrue(new ConfigMigrator(plugin).migrateFromPerformanceAnalyzer(true));

        PluginConfig config = new PluginConfig(plugin);
        assertEquals(5.5, config.reachDistance());
        assertTrue(config.isWhitelistedPlayer(java.util.UUID.fromString("00000000-0000-0000-0000-000000000002")));
        assertEquals(4, config.xrayThreshold("DIAMOND_ORE"));
        assertEquals(10, config.xrayThreshold("GOLD_ORE"), "admin-defined section replaced as a whole: gold is gone");
        assertEquals("https://discord.com/api/webhooks/1/ac", config.discordWebhookUrl());
        assertFalse(config.discordAlertType("xray"));
        assertTrue(config.silentPlayers().isEmpty(), "silent players are never imported");
        assertEquals("de", config.language());
        String after = contents();
        assertFalse(after.contains("removed_legacy_setting"), "keys this plugin does not know stay behind");
        assertFalse(after.contains("incident_opened"), "PerformanceAnalyzer's own alert types stay behind");
        assertTrue(after.contains("config_version"), "the marker prevents a second run");

        // A second start must not import again over the admin's edits.
        plugin.getConfig().set("anticheat.reach_distance", 4.2);
        assertFalse(new ConfigMigrator(plugin).migrateFromPerformanceAnalyzer(true));
    }

    @Test
    @DisplayName("An existing install is never migrated")
    void existingInstallIsLeftAlone() throws Exception {
        Files.writeString(paConfig.toPath(), LEGACY_PA);
        installDefaultConfig();
        String before = contents();

        assertFalse(new ConfigMigrator(plugin).migrateFromPerformanceAnalyzer(false));
        assertEquals(before, contents());
    }

    @Test
    @DisplayName("Foreign keys from the old migration are removed, once")
    void foreignKeysAreCleanedUpIdempotently() throws Exception {
        installDefaultConfig();
        Files.writeString(configFile.toPath(), contents().replace(
                "    movement: true\n",
                "    movement: true\n    incident_opened: true\n    incident_resolved: false\n"));
        plugin.reloadConfig();
        assertTrue(contents().contains("incident_opened"), "fixture check");

        new PluginConfig(plugin);
        String cleaned = contents();
        assertFalse(cleaned.contains("incident_opened"));
        assertFalse(cleaned.contains("incident_resolved"));
        assertTrue(cleaned.contains("movement: true"), "the plugin's own alert types stay");

        plugin.reloadConfig();
        new PluginConfig(plugin);
        assertEquals(cleaned, contents(), "a second start finds nothing and writes nothing");
    }
}
