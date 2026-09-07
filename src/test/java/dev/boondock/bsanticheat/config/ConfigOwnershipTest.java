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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @DisplayName("A merged key brings its explanation with it")
    void mergedKeysKeepTheirComments() throws Exception {
        // An admin's file from before the key existed: the line AND the comment block that
        // introduces it, which is what a file written by an older version actually looks
        // like. Removing only the line leaves the explanation behind and the assertion below
        // passes without the merge having done anything — this test failed that way first.
        java.util.List<String> kept = new java.util.ArrayList<>();
        java.util.List<String> pendingComments = new java.util.ArrayList<>();
        for (String line : contents().split("\n", -1)) {
            if (line.strip().startsWith("#")) {
                pendingComments.add(line);
                continue;
            }
            if (line.strip().startsWith("xray_max_count_per_vein:")) {
                pendingComments.clear();   // drop the key and the block above it
                continue;
            }
            kept.addAll(pendingComments);
            pendingComments.clear();
            kept.add(line);
        }
        kept.addAll(pendingComments);
        Files.write(configFile.toPath(), String.join("\n", kept).getBytes(StandardCharsets.UTF_8));
        plugin.reloadConfig();
        assertFalse(contents().contains("xray_max_count_per_vein"), "fixture check: the key is gone");
        assertFalse(contents().contains("How much a SINGLE deposit may contribute"),
                "fixture check: and so is its explanation");

        new PluginConfig(plugin);

        String after = contents();
        assertTrue(after.contains("xray_max_count_per_vein"), "the missing key has to be merged in");
        assertTrue(after.contains("How much a SINGLE deposit may contribute"),
                "and the shipped reasoning has to come with it");
    }

    @Test
    @DisplayName("An edited punishment tier is not restored beside the admin's own")
    void editedTiersAreLeftAlone() throws Exception {
        // The reported case: "kick messages can't be edited, the plugin sets them back".
        // The threshold is part of the KEY, so changing 40 to 10 looks to the merge like key
        // "40" was lost — and it put the shipped tier back, default kick message and all,
        // next to the admin's own. Everyone changes that threshold, because 40 is far out of
        // reach for a per-check VL that decays every 5 minutes.
        // The whole shipped tier block replaced by one of the admin's own, matched
        // structurally rather than by its text — the defaults are tuning values and change.
        Files.writeString(configFile.toPath(), contents().replaceAll(
                "(?m)^    tiers:\\n(?:      .*\\n)+",
                "    tiers:\n      \"10\":\n        - \"@kick Erwischt: %check%\"\n"));
        plugin.reloadConfig();

        PluginConfig config = new PluginConfig(plugin);

        assertEquals(java.util.Set.of(10), config.punishmentTiers().keySet(),
                "the admin's tier is the only one there is");
        // The list ENTRY, not the word — "@notify" also appears in the comment that
        // explains it, and that comment is supposed to stay.
        assertFalse(contents().contains("- \"@notify\""),
                "no shipped tier may come back into the file");
    }

    @Test
    @DisplayName("An ore removed from the X-Ray thresholds stays removed")
    void removedOreThresholdStaysRemoved() throws Exception {
        Files.writeString(configFile.toPath(),
                contents().replaceAll("(?m)^    coal: 30\\n", ""));
        plugin.reloadConfig();

        new PluginConfig(plugin);

        assertFalse(contents().contains("coal: 30"), "a deleted threshold is a decision, not a gap");
    }

    @Test
    @DisplayName("A section missing entirely is still filled in")
    void absentSectionIsStillMerged() throws Exception {
        // The other half: skipping admin-owned sections must not disable the merge for a
        // config that predates the section completely.
        Files.writeString(configFile.toPath(),
                contents().replaceAll("(?s)  # Punishment / Violation-Level handling.*?\n  # OP Bypass", "  # OP Bypass"));
        plugin.reloadConfig();
        assertFalse(contents().contains("punishments:"), "fixture check: the section is gone");

        PluginConfig config = new PluginConfig(plugin);

        assertFalse(config.punishmentTiers().isEmpty(),
                "a config from before the feature existed still gets the defaults");
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
