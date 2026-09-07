package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.alerts.AlertPreferenceManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Muted alert categories must survive a disconnect.
 *
 * <p>Persistent preferences are read from the config exactly once, at startup. Anything
 * dropped from memory on disconnect is therefore gone for the rest of the server's uptime —
 * the player quietly starts receiving alerts they had switched off, and only a restart
 * brings the setting back. Both storage shapes have to be recognised as persistent:
 * {@code "uuid"} for everything muted, and {@code "uuid:CATEGORY"} for individual ones. The
 * second form was matched with an exact-string test and therefore never recognised.
 *
 * <p>The stored entries are written straight into the config rather than through
 * {@code toggleCategory}: persisting goes via AsyncConfigSaver, and MockBukkit's ServerMock
 * has no global region scheduler. Reading is the half this test is about anyway.
 */
class AlertPreferencePersistenceTest extends ScenarioBase {

    private AlertPreferenceManager withStored(String... entries) {
        plugin.getConfig().set("alerts.silent_players", List.of(entries));
        return new AlertPreferenceManager(plugin, config);
    }

    @Test
    @DisplayName("A stored per-category mute survives a disconnect")
    void categoryMuteSurvivesQuit() {
        UUID id = UUID.randomUUID();
        AlertPreferenceManager manager = withStored(id + ":MOVEMENT");

        assertFalse(manager.shouldReceive(id, AlertPreferenceManager.AlertCategory.MOVEMENT),
                "the stored entry should have been loaded as muted");

        manager.cleanup(id);
        assertFalse(manager.shouldReceive(id, AlertPreferenceManager.AlertCategory.MOVEMENT),
                "a stored 'uuid:CATEGORY' entry is persistent and must not be dropped on quit");
    }

    @Test
    @DisplayName("A stored mute-everything survives a disconnect")
    void muteAllSurvivesQuit() {
        UUID id = UUID.randomUUID();
        AlertPreferenceManager manager = withStored(id.toString());

        manager.cleanup(id);
        assertFalse(manager.shouldReceive(id, AlertPreferenceManager.AlertCategory.XRAY));
    }

    @Test
    @DisplayName("Several stored categories all survive")
    void multipleCategoriesSurviveQuit() {
        UUID id = UUID.randomUUID();
        AlertPreferenceManager manager = withStored(id + ":MOVEMENT,XRAY");

        manager.cleanup(id);
        assertFalse(manager.shouldReceive(id, AlertPreferenceManager.AlertCategory.MOVEMENT));
        assertFalse(manager.shouldReceive(id, AlertPreferenceManager.AlertCategory.XRAY));
    }

    @Test
    @DisplayName("A player who stored nothing is released on disconnect")
    void unmutedPlayerIsCleanedUp() {
        // The other half: cleanup still has to release players who left no preference,
        // otherwise the map grows for every player who ever joined.
        UUID stored = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        AlertPreferenceManager manager = withStored(stored + ":MOVEMENT");

        manager.cleanup(other);
        assertTrue(manager.shouldReceive(other, AlertPreferenceManager.AlertCategory.MOVEMENT));
        assertTrue(manager.getMutedCategories(other).isEmpty());
    }
}
