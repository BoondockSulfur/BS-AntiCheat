package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CrystalAura and AnchorAura timing, rate and aim.
 */
class CrystalCheckerTest extends ScenarioBase {

    private CrystalChecker checker;

    @BeforeEach
    void setUpChecker() {
        plugin.getConfig().set("anticheat.crystalaura_detection", true);
        plugin.getConfig().set("anticheat.anchoraura_detection", true);
        config = new PluginConfig(plugin);
        checker = new CrystalChecker(plugin, config, null, lang);
        checker.setViolationManager(violations);
    }

    /** A player at the origin looking along +Z, and a crystal spawned in front of them. */
    private PlayerMock facingPlayer() throws InterruptedException {
        PlayerMock p = player(0.5, 64.0, 0.5);
        org.bukkit.Location l = p.getLocation();
        l.setYaw(0f);
        l.setPitch(0f);
        p.teleport(l);
        return p;
    }

    private EnderCrystal crystalAt(double x, double y, double z) {
        return world.spawn(loc(x, y, z), EnderCrystal.class);
    }

    @Test
    @DisplayName("Breaking crystals the moment they spawn, repeatedly, raises CRYSTALAURA")
    void instantBreaksAreCaught() throws InterruptedException {
        PlayerMock p = facingPlayer();
        long now = System.currentTimeMillis();
        for (int i = 0; i < config.crystalAuraViolations(); i++) {
            EnderCrystal c = crystalAt(0.5, 64.0, 3.5);
            checker.noteCrystalSpawn(c.getUniqueId(), now + i * 300L);
            checker.onCrystalBreak(p, c, now + i * 300L + 10);
        }
        assertEquals(1, violations.count("CRYSTALAURA"));
    }

    @Test
    @DisplayName("Breaking crystals at human speed in front of you raises nothing")
    void humanBreaksAreQuiet() throws InterruptedException {
        PlayerMock p = facingPlayer();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            EnderCrystal c = crystalAt(0.5, 64.0, 3.5);
            checker.noteCrystalSpawn(c.getUniqueId(), now + i * 400L);
            checker.onCrystalBreak(p, c, now + i * 400L + 250);
        }
        assertEquals(0, violations.count("CRYSTALAURA"));
        assertFalse(checker.remembersSpawn(UUID.randomUUID()));
    }

    @Test
    @DisplayName("Breaking crystals behind you, repeatedly, raises CRYSTALAURA")
    void offAimBreaksAreCaught() throws InterruptedException {
        PlayerMock p = facingPlayer();
        long now = System.currentTimeMillis();
        for (int i = 0; i < config.crystalAuraViolations(); i++) {
            EnderCrystal c = crystalAt(0.5, 64.0, -3.5);
            checker.noteCrystalSpawn(c.getUniqueId(), now + i * 400L);
            checker.onCrystalBreak(p, c, now + i * 400L + 250);
        }
        assertEquals(1, violations.count("CRYSTALAURA"));
    }

    @Test
    @DisplayName("Too fast to have seen the crystal: under the floor or under half the round trip")
    void impossibleBreakRule() {
        assertTrue(CrystalChecker.isImpossibleBreak(10, 50, -1));
        assertFalse(CrystalChecker.isImpossibleBreak(60, 50, -1));
        assertTrue(CrystalChecker.isImpossibleBreak(60, 50, 200), "the spawn cannot have reached the client");
        assertFalse(CrystalChecker.isImpossibleBreak(120, 50, 200));
        assertFalse(CrystalChecker.isImpossibleBreak(60, 50, 60), "a short round trip is timing noise");
        assertFalse(CrystalChecker.isImpossibleBreak(-1, 50, 200), "unknown spawn time judges nothing");
    }

    @Test
    @DisplayName("The break rate needs two consecutive windows over the limit")
    void rateNeedsTwoWindows() {
        UUID id = UUID.randomUUID();
        long t = 1_000_000L;
        int max = 10;
        int flagged = 0;
        for (int i = 0; i <= max; i++) flagged += checker.noteBreakRate(id, t + i * 10L, max);
        assertEquals(0, flagged, "one window over the limit is a burst");
        for (int i = 0; i <= max; i++) flagged += checker.noteBreakRate(id, t + 1000L + i * 10L, max);
        assertEquals(max + 1, flagged);
    }

    @Test
    @DisplayName("A quiet window in between resets the break rate")
    void rateResetsAfterQuietWindow() {
        UUID id = UUID.randomUUID();
        long t = 1_000_000L;
        int flagged = 0;
        for (int i = 0; i <= 10; i++) flagged += checker.noteBreakRate(id, t + i * 10L, 10);
        flagged += checker.noteBreakRate(id, t + 1000L, 10);
        for (int i = 0; i <= 10; i++) flagged += checker.noteBreakRate(id, t + 2000L + i * 10L, 10);
        assertEquals(0, flagged);
    }

    private Block anchor() {
        setBlock(2, 64, 2, Material.RESPAWN_ANCHOR);
        return world.getBlockAt(2, 64, 2);
    }

    @Test
    @DisplayName("Detonating an anchor within a tick of charging it, repeatedly, raises ANCHORAURA")
    void instantAnchorIsCaught() {
        PlayerMock p = player(0.5, 64.0, 0.5);
        Block block = anchor();
        long now = System.currentTimeMillis();
        for (int i = 0; i < config.anchorAuraViolations(); i++) {
            long t = now + i * 1000L;
            checker.onAnchorClick(p, block, 0, 4, new ItemStack(Material.GLOWSTONE), t);
            checker.onAnchorClick(p, block, 1, 4, new ItemStack(Material.TOTEM_OF_UNDYING), t + 10);
        }
        assertEquals(1, violations.count("ANCHORAURA"));
    }

    @Test
    @DisplayName("Charging, switching and clicking at human speed raises nothing")
    void humanAnchorIsQuiet() {
        PlayerMock p = player(0.5, 64.0, 0.5);
        Block block = anchor();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 6; i++) {
            long t = now + i * 1000L;
            checker.onAnchorClick(p, block, 0, 4, new ItemStack(Material.GLOWSTONE), t);
            checker.onAnchorClick(p, block, 1, 4, null, t + 250);
        }
        assertEquals(0, violations.count("ANCHORAURA"));
    }

    @Test
    @DisplayName("Anchor clicks are read from the interact event")
    void interactEventWiring() {
        PlayerMock p = player(0.5, 64.0, 0.5);
        Block block = anchor();
        for (int i = 0; i < config.anchorAuraViolations(); i++) {
            RespawnAnchor data = (RespawnAnchor) block.getBlockData();
            data.setCharges(0);
            block.setBlockData(data);
            checker.onInteract(new PlayerInteractEvent(p, Action.RIGHT_CLICK_BLOCK,
                    new ItemStack(Material.GLOWSTONE), block, BlockFace.UP, EquipmentSlot.HAND));
            data.setCharges(1);
            block.setBlockData(data);
            checker.onInteract(new PlayerInteractEvent(p, Action.RIGHT_CLICK_BLOCK,
                    null, block, BlockFace.UP, EquipmentSlot.HAND));
        }
        assertEquals(1, violations.count("ANCHORAURA"), "back-to-back events are well under one tick apart");
    }
}
