package dev.boondock.bsanticheat.anticheat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-player transaction state must not outlive the player. On Folia the ping loop runs on
 * the global thread while quit cleanup runs on the player's region, so a ping can race the
 * cleanup.
 */
class TransactionCleanupTest extends ScenarioBase {

    private TransactionManager transactions;

    @BeforeEach
    void setUpManager() {
        transactions = new TransactionManager(plugin, config, null);
    }

    @Test
    @DisplayName("A ping for a player who has already quit creates no state")
    void noStateAfterQuit() {
        PlayerMock player = player(0.5, 64.0, 0.5);
        player.disconnect();
        transactions.cleanup(player.getUniqueId());
        transactions.sendPing(player);
        assertFalse(transactions.isTracked(player.getUniqueId()));
    }

    @Test
    @DisplayName("State left behind by a racing ping is swept on the next run")
    void leftoversAreSwept() {
        PlayerMock player = player(0.5, 64.0, 0.5);
        transactions.sendPing(player);
        assertTrue(transactions.isTracked(player.getUniqueId()), "fixture check: tracked while online");
        transactions.retainOnline(Set.of());
        assertFalse(transactions.isTracked(player.getUniqueId()));
    }
}
