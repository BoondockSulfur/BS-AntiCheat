package dev.boondock.bsanticheat.anticheat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InventoryMove: walking at full speed with a container GUI open, which a vanilla client
 * cannot do.
 *
 * <p>Speeds of 0.150 / 0.165 / 0.278 against a 0.15 threshold sit right on it and are all
 * explainable by momentum the player is not steering.
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
     * Steady walking at the given speed per move, on the stone floor.
     *
     * <p>Whether the player is airborne is decided from the blocks under them, so the
     * client's on-ground flag is set to "on ground" here only to show it does not matter;
     * see {@link #walkAt} for the cases where it disagrees with the world.
     */
    private void walk(PlayerMock player, double perStep, int steps) throws InterruptedException {
        walkAt(player, perStep, steps, 80.0, true);
    }

    private void walkAt(PlayerMock player, double perStep, int steps, double y, boolean clientOnGround)
            throws InterruptedException {
        for (int i = 0; i < steps; i++) {
            Location from = loc(0.5 + i * perStep, y, 0.5);
            Location to = loc(0.5 + (i + 1) * perStep, y, 0.5);
            player.teleport(from);
            player.setOnGround(clientOnGround); // teleporting clears it again
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
        // Friction needs several ticks to bring a sprint under the threshold,
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
    @DisplayName("Reporting on-ground false does not buy an exemption")
    void spoofedAirborneIsCaught() throws InterruptedException {
        // The client's flag is the player's own claim; the floor under them is not.
        PlayerMock player = player(0.5, 80.0, 0.5);
        openContainer(player);
        Thread.sleep(1100);
        walkAt(player, 0.25, config.inventoryMoveViolations() + 6, 80.0, false);
        assertTrue(violations.count("INVENTORYMOVE") > 0,
                "walking on a solid floor is walking, whatever the client reports");
    }

    @Test
    @DisplayName("Momentum through the air with nothing underfoot is not judged")
    void genuinelyAirborneIsQuiet() throws InterruptedException {
        // Five blocks above the floor: carried momentum, not steering.
        PlayerMock player = player(0.5, 85.0, 0.5);
        openContainer(player);
        Thread.sleep(1100);
        walkAt(player, 0.25, config.inventoryMoveViolations() + 6, 85.0, true);
        assertEquals(0, violations.count("INVENTORYMOVE"));
    }

    // ==================== ChestStealer ====================

    private long clock = 1_000_000L;

    /** A full single chest open for the player, ChestStealer enabled, stepped clock. */
    private InventoryView openFullChest(PlayerMock player) {
        plugin.getConfig().set("anticheat.cheststealer_detection", true);
        checker.clock = () -> clock;
        Inventory chest = server.createInventory(null, InventoryType.CHEST);
        for (int i = 0; i < chest.getSize(); i++) chest.setItem(i, new ItemStack(Material.COBBLESTONE, 64));
        return player.openInventory(chest);
    }

    private void shiftClick(InventoryView view, int slot, long afterMs) {
        clock += afterMs;
        checker.onInventoryClick(new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, slot,
                ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY));
    }

    @Test
    @DisplayName("A stealer walking the slots by index is caught")
    void indexOrderStealerIsCaught() {
        PlayerMock player = player(0.5, 80.0, 0.5);
        InventoryView view = openFullChest(player);
        for (int slot = 0; slot < 27; slot++) shiftClick(view, slot, 20);
        assertTrue(violations.count("CHESTSTEALER") > 0, "row wraps are something no drag does");
    }

    @Test
    @DisplayName("A Mouse Tweaks shift-drag across the chest is not flagged")
    void shiftDragIsQuiet() {
        // A zig-zag drag: along the first row, down, back along the second, and so on — every
        // step to a neighbouring slot, as fast as the mouse moves.
        PlayerMock player = player(0.5, 80.0, 0.5);
        InventoryView view = openFullChest(player);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int c = row % 2 == 0 ? col : 8 - col;
                shiftClick(view, row * 9 + c, 15);
            }
        }
        assertEquals(0, violations.count("CHESTSTEALER"), "a drag is a continuous path");
    }

    @Test
    @DisplayName("Drag steps: neighbours and a skipped slot, not a row wrap")
    void dragStepGeometry() {
        assertTrue(InventoryChecker.isDragStep(0, 1, 9));
        assertTrue(InventoryChecker.isDragStep(4, 13, 9), "straight down");
        assertTrue(InventoryChecker.isDragStep(0, 2, 9), "one slot skipped between frames");
        assertFalse(InventoryChecker.isDragStep(8, 9, 9), "end of a row to the start of the next");
        assertFalse(InventoryChecker.isDragStep(0, 26, 9));
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
