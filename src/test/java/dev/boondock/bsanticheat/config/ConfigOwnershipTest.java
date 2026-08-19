package dev.boondock.bsanticheat.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who owns config.yml.
 *
 * <p>The plugin used to save the file on every shutdown whether or not it had changed
 * anything, which made it the last writer of a file it had not edited. An admin editing a
 * threshold while the server runs, without a {@code /bsac reload}, had the edit overwritten
 * by the stale in-memory value at the next stop — on a server that auto-restarts twice a day,
 * within hours, and with nothing in the log to say so.
 *
 * <p>Bukkit's {@code saveConfig()} rewrites YAML in its own style (quotes dropped, comment
 * spacing collapsed), so "was the file written" is observable byte for byte: the fixture below
 * is the shipped default verbatim, which a save would reformat even with every value identical.
 */
class ConfigOwnershipTest {

    private JavaPlugin plugin;
    private File configFile;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("BSAntiCheat");
        // The shipped default, written out as an admin's file would be: complete, so nothing
        // is missing to merge, and in the source formatting rather than Bukkit's.
        try (InputStream is = plugin.getResource("config.yml")) {
            byte[] shipped = is.readAllBytes();
            plugin.getDataFolder().mkdirs();
            configFile = new File(plugin.getDataFolder(), "config.yml");
            Files.write(configFile.toPath(), shipped);
        }
        plugin.reloadConfig();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private String contents() throws Exception {
        return Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Shutting down without having changed anything leaves the file untouched")
    void shutdownDoesNotRewriteAnUntouchedFile() throws Exception {
        String before = contents();
        PluginConfig config = new PluginConfig(plugin);
        config.saveSyncOnShutdown();
        assertEquals(before, contents(), "the plugin changed nothing, so the file is still the admin's");
    }

    @Test
    @DisplayName("An admin's edit survives a shutdown")
    void adminEditSurvivesShutdown() throws Exception {
        PluginConfig config = new PluginConfig(plugin);
        assertEquals(25, config.autoClickerMaxCps(), "fixture check: the shipped default");

        // Edited in the file while the server runs, with no /bsac reload — so the value the
        // plugin holds in memory is still the old one.
        Files.writeString(configFile.toPath(),
                contents().replace("autoclicker_max_cps: 25", "autoclicker_max_cps: 18"));
        config.saveSyncOnShutdown();

        assertTrue(contents().contains("autoclicker_max_cps: 18"),
                "the edit must still be there — the plugin had nothing of its own to write");
    }

    @Test
    @DisplayName("A change the plugin made IS still written on shutdown")
    void pluginChangesAreStillPersisted() throws Exception {
        String before = contents();
        PluginConfig config = new PluginConfig(plugin);
        String uuid = UUID.randomUUID().toString();
        // The mutation also kicks off an async save, and MockBukkit has no Folia region
        // scheduler to run it on. That throw is beside the point here — the value is already
        // set and the change already recorded — and what this case is about is the SHUTDOWN
        // path still writing it.
        try {
            config.addWhitelistPlayer(uuid);
        } catch (org.mockbukkit.mockbukkit.exception.UnimplementedOperationException expected) {
            // no async scheduler under MockBukkit
        }
        config.saveSyncOnShutdown();

        assertNotEquals(before, contents(), "the whitelist addition has to reach disk");
        assertTrue(contents().contains(uuid), "the whitelisted player must be in the file");
    }
}
