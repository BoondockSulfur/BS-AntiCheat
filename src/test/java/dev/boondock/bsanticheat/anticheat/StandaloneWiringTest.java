package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.lang.LanguageManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the assembly the movement tests depend on.
 *
 * <p>Loading the whole plugin does not work: onEnable reaches for Folia's global region
 * scheduler, which MockBukkit does not implement. So the question becomes whether the
 * collaborators a checker needs — config and language — can be built on their own against a
 * bare mock plugin, without the plugin's own startup.
 */
class StandaloneWiringTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("Config and language can be built against a bare mock plugin")
    void collaboratorsBuildStandalone() {
        JavaPlugin mockPlugin = MockBukkit.createMockPlugin("BSAntiCheat");

        PluginConfig config = new PluginConfig(mockPlugin);
        assertNotNull(config);
        // Defaults must be readable — this is what every check reads its thresholds from.
        assertTrue(config.speedThresholdWalk() > 0, "walk threshold should have a default");
        assertTrue(config.xrayMinVeins() >= 1);

        LanguageManager lang = new LanguageManager(mockPlugin, "en");
        assertNotNull(lang);

        // And the checker itself, with a null database (the code guards for it).
        MovementChecker checker = new MovementChecker(mockPlugin, config, null, lang);
        assertNotNull(checker);
    }
}
