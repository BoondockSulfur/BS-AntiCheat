package dev.boondock.bsanticheat.anticheat;

import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The AutoClicker mining exclusion and the packet checks' teleport grace.
 *
 * <p>A START_DIGGING packet alone must not suppress swing counting for five seconds, or
 * resending it every few seconds would hide an autoclicker entirely. A START only opens a short
 * provisional window; the server's confirmation that a real block within reach is being
 * damaged extends it to that block's break time.
 */
class MiningWindowTest extends ScenarioBase {

    private PacketChecker checker;
    private UUID id;

    @BeforeEach
    void setUpChecker() {
        checker = new PacketChecker(plugin, config, null, lang);
        id = UUID.randomUUID();
    }

    @Test
    @DisplayName("An unconfirmed START only covers a moment")
    void provisionalWindowIsShort() {
        long now = System.currentTimeMillis();
        checker.noteDigging(id, DiggingAction.START_DIGGING, now - 1000L);
        assertFalse(checker.isMining(id), "a START one second ago without confirmation covers nothing");
        checker.noteDigging(id, DiggingAction.START_DIGGING, now);
        assertTrue(checker.isMining(id), "the swings right after a START are still covered");
    }

    @Test
    @DisplayName("Resending START without the server confirming it stops working")
    void unconfirmedStartsRunOut() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) checker.noteDigging(id, DiggingAction.START_DIGGING, now);
        assertFalse(checker.isMining(id), "STARTs at nothing must not hide clicks forever");
    }

    @Test
    @DisplayName("A confirmed dig covers the block's break time, and no more")
    void confirmedDigIsBounded() {
        long now = System.currentTimeMillis();
        checker.noteDigging(id, DiggingAction.START_DIGGING, now);
        checker.noteBlockDamage(id, 2000L, now);
        assertTrue(checker.isMining(id));

        UUID other = UUID.randomUUID();
        checker.noteDigging(other, DiggingAction.START_DIGGING, now - 4000L);
        checker.noteBlockDamage(other, 1000L, now - 4000L);
        assertFalse(checker.isMining(other), "a one-second block does not cover four seconds");
    }

    @Test
    @DisplayName("Confirmation resets the unconfirmed count")
    void confirmationRestoresProvisional() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) checker.noteDigging(id, DiggingAction.START_DIGGING, now);
        checker.noteBlockDamage(id, 0L, now);
        checker.noteDigging(id, DiggingAction.START_DIGGING, now);
        assertTrue(checker.isMining(id), "real mining keeps working after a confirmed block");
    }

    @Test
    @DisplayName("FINISH ends the window and a late confirmation does not reopen it")
    void finishEndsWindow() {
        long now = System.currentTimeMillis();
        checker.noteDigging(id, DiggingAction.START_DIGGING, now);
        checker.noteDigging(id, DiggingAction.FINISHED_DIGGING, now);
        checker.noteBlockDamage(id, 3000L, now);
        assertFalse(checker.isMining(id));
    }

    @Test
    @DisplayName("An entity attack ends the mining window")
    void attackEndsWindow() {
        // Vanilla aborts digging before attacking, so clicks on players are clicks.
        long now = System.currentTimeMillis();
        checker.noteDigging(id, DiggingAction.START_DIGGING, now);
        checker.noteBlockDamage(id, 3000L, now);
        checker.noteEntityAttack(id);
        assertFalse(checker.isMining(id));
    }

    @Test
    @DisplayName("Repeated STARTs confirmed by the block lookup keep covering a held dig")
    void lookupConfirmsRepeatedStarts() {
        // BlockDamageEvent does not fire for a refused dig; every START is confirmed by the
        // region-thread lookup instead, so the unconfirmed limit never kicks in.
        long now = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            long seq = checker.noteDigging(id, DiggingAction.START_DIGGING, now);
            checker.noteDigConfirmed(id, seq, 750L, now);
            checker.noteDigging(id, DiggingAction.FINISHED_DIGGING, now);
        }
        long seq = checker.noteDigging(id, DiggingAction.START_DIGGING, now);
        checker.noteDigConfirmed(id, seq, 750L, now);
        assertTrue(checker.isMining(id));
    }

    @Test
    @DisplayName("A lookup confirmation is bounded by the break time")
    void lookupConfirmationIsBounded() {
        long now = System.currentTimeMillis();
        long seq = checker.noteDigging(id, DiggingAction.START_DIGGING, now - 3000L);
        checker.noteDigConfirmed(id, seq, 1000L, now - 3000L);
        assertFalse(checker.isMining(id), "a one-second block does not cover three seconds");

        UUID slow = UUID.randomUUID();
        long s2 = checker.noteDigging(slow, DiggingAction.START_DIGGING, now - 5100L);
        checker.noteDigConfirmed(slow, s2, 60_000L, now - 5100L);
        assertFalse(checker.isMining(slow), "no single START covers more than the safety cap");
    }

    @Test
    @DisplayName("A stale lookup does not confirm a later START")
    void staleLookupIgnored() {
        long now = System.currentTimeMillis();
        long first = checker.noteDigging(id, DiggingAction.START_DIGGING, now - 1000L);
        for (int i = 0; i < 4; i++) checker.noteDigging(id, DiggingAction.START_DIGGING, now);
        checker.noteDigConfirmed(id, first, 3000L, now);
        assertFalse(checker.isMining(id));
    }

    @Test
    @DisplayName("Dig reach and break-time helpers")
    void digHelpers() {
        assertTrue(PacketChecker.withinDigReach(0.5, 65.62, 0.5, 3, 64, 0, 6.0));
        assertFalse(PacketChecker.withinDigReach(0.5, 65.62, 0.5, 10, 64, 0, 6.0));
        assertTrue(PacketChecker.expectedBreakMs(1.0f) == 0L, "instant break");
        assertTrue(PacketChecker.expectedBreakMs(0.0f) == 0L, "unbreakable");
        assertTrue(PacketChecker.expectedBreakMs(0.1f) == 500L);
    }

    // ==================== teleport grace ====================

    @Test
    @DisplayName("Short player-triggered teleports get no grace")
    void pearlGetsNoGrace() {
        assertFalse(PacketChecker.teleportNeedsGrace(TeleportCause.ENDER_PEARL, loc(0, 64, 0), loc(40, 64, 0)));
        assertFalse(PacketChecker.teleportNeedsGrace(TeleportCause.CONSUMABLE_EFFECT, loc(0, 64, 0), loc(8, 64, 0)));
        assertFalse(PacketChecker.teleportNeedsGrace(TeleportCause.DISMOUNT, loc(0, 64, 0), loc(1, 64, 0)));
    }

    @Test
    @DisplayName("Setbacks and other short plugin teleports get no grace")
    void shortPluginTeleportGetsNoGrace() {
        assertFalse(PacketChecker.teleportNeedsGrace(TeleportCause.PLUGIN, loc(0, 64, 0), loc(2, 64, 0)));
    }

    @Test
    @DisplayName("Long teleports and world changes keep their grace")
    void longTeleportKeepsGrace() {
        assertTrue(PacketChecker.teleportNeedsGrace(TeleportCause.COMMAND, loc(0, 64, 0), loc(500, 64, 0)));
        assertTrue(PacketChecker.teleportNeedsGrace(TeleportCause.ENDER_PEARL, loc(0, 64, 0), loc(300, 64, 0)),
                "a stasis-chamber pearl across the map loads chunks like any other long teleport");
        Location nether = new Location(server.addSimpleWorld("nether"), 0, 64, 0);
        assertTrue(PacketChecker.teleportNeedsGrace(TeleportCause.NETHER_PORTAL, loc(0, 64, 0), nether));
    }
}
