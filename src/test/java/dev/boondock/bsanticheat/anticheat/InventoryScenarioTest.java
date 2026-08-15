package dev.boondock.bsanticheat.anticheat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InventoryMove: walking at full speed with a container GUI open, which a vanilla client
 * cannot do.
 *
 * <p>The live false positives measured 0.150 / 0.165 / 0.278 against a 0.15 threshold —
 * sitting right on it, and all explainable by momentum the player was not steering.
 */
class InventoryScenarioTest extends ScenarioBase {

    private InventoryChecker checker;

    @BeforeEach
    void setUpChecker() {
        checker = new InventoryChecker(plugin, config, null, lang);
        checker.setViolationManager(violations);
        checker.setPistonTracker(new PistonTracker());
        floor(79, Material.STONE);
    }

    /** Open a chest GUI for this player, as the check listens for. */
    private void openContainer(PlayerMock player) {
        Inventory chest = server.createInventory(null, org.bukkit.event.inventory.InventoryType.CHEST);
        checker.onInventoryOpen(new InventoryOpenEvent(player.openInventory(chest)));
    }

    /**
     * Steady walking at the given speed per move, on the ground.
     *
     * <p>The on-ground flag has to be set explicitly: the check skips airborne players
     * (momentum they are not steering), and a mock player defaults to not being on the
     * ground — which would silently disable the whole check and leave every "raises
     * nothing" case passing for the wrong reason.
     */
    private void walk(PlayerMock player, double perStep, int steps) throws InterruptedException {
        player.setOnGround(true);
        for (int i = 0; i < steps; i++) {
            Location from = loc(0.5 + i * perStep, 80.0, 0.5);
            Location to = loc(0.5 + (i + 1) * perStep, 80.0, 0.5);
            player.teleport(from);
            player.setOnGround(true); // teleporting clears it again
            checker.onPlayerMove(new PlayerMoveEvent(player, from, to));
            tick();
        }
    }

    @Test
    @DisplayName("Walking with a container open, after the momentum has died, is caught")
    void walkingWithGuiOpenIsCaught() throws InterruptedException {
        PlayerMock player = player(0.5, 80.0, 0.5);
        openContainer(player);
        // Past the opening grace, so what follows is steering, not carry-over.
        Thread.sleep(1100);
        walk(player, 0.25, config.inventoryMoveViolations() + 6);
        assertTrue(violations.count("INVENTORYMOVE") > 0,
                "sustained walking with a GUI open is what the check is for");
    }

    @Test
    @DisplayName("The moment right after opening is not judged")
    void momentumAfterOpeningIsForgiven() throws InterruptedException {
        // The live case: friction needs several ticks to bring a sprint under the threshold,
        // and the player steers none of them.
        PlayerMock player = player(0.5, 80.0, 0.5);
        openContainer(player);
        walk(player, 0.25, config.inventoryMoveViolations() + 6);
        assertEquals(0, violations.count("INVENTORYMOVE"),
                "momentum carried into the GUI is not input");
    }

    @Test
    @DisplayName("Drifting slower than the threshold is not judged")
    void slowDriftIsQuiet() throws InterruptedException {
        PlayerMock player = player(0.5, 80.0, 0.5);
        openContainer(player);
        Thread.sleep(1100);
        walk(player, 0.05, config.inventoryMoveViolations() + 6);
        assertEquals(0, violations.count("INVENTORYMOVE"));
    }

    @Test
    @DisplayName("With no container open nothing is judged at all")
    void noContainerNoCheck() throws InterruptedException {
        PlayerMock player = player(0.5, 80.0, 0.5);
        Thread.sleep(1100);
        walk(player, 0.25, 20);
        assertEquals(0, violations.count("INVENTORYMOVE"));
    }
}
