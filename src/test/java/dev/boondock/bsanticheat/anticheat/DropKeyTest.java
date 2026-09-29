package dev.boondock.bsanticheat.anticheat;

import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The AutoClicker drop-key exclusion.
 *
 * <p>A player holding the drop key with a stack in hand would otherwise read as an
 * autoclicker at 26 CPS. Every drop rides on PLAYER_DIGGING and is accompanied by an arm
 * swing, so the swings arrive once per tick — a hand on a key, not a click rate. Both the
 * mining actions and the drop actions of that packet must be excluded.
 *
 * <p>The window is deliberately short. Mining gets a five-second safety cap because a break
 * legitimately lasts that long; a drop is instantaneous, and a five-second window would let
 * one dropped item buy a real clicker five seconds of cover.
 */
class DropKeyTest extends ScenarioBase {

    private PacketChecker checker;
    private UUID id;

    @BeforeEach
    void setUpChecker() {
        checker = new PacketChecker(plugin, config, null, lang);
        id = UUID.randomUUID();
    }

    @Test
    @DisplayName("Dropping an item suppresses the accompanying swing")
    void dropSuppressesSwing() {
        checker.noteDigging(id, DiggingAction.DROP_ITEM, System.currentTimeMillis());
        assertTrue(checker.isDropping(id), "the swing that rides on a drop is not a click");
    }

    @Test
    @DisplayName("Dropping a whole stack counts the same")
    void dropStackSuppressesSwing() {
        checker.noteDigging(id, DiggingAction.DROP_ITEM_STACK, System.currentTimeMillis());
        assertTrue(checker.isDropping(id));
    }

    @Test
    @DisplayName("The drop window expires quickly")
    void dropWindowIsShort() {
        // Stamped in the past: one drop must not cover a burst of clicks that follows it.
        checker.noteDigging(id, DiggingAction.DROP_ITEM, System.currentTimeMillis() - 1000L);
        assertFalse(checker.isDropping(id), "a single drop is not a one-second free pass");
    }

    @Test
    @DisplayName("A player who has dropped nothing is not covered")
    void noDropNoCover() {
        assertFalse(checker.isDropping(id));
    }

    @Test
    @DisplayName("Dropping does not mark the player as mining")
    void dropIsNotMining() {
        // The two windows are separate on purpose — a drop must not inherit mining's 5s cap.
        checker.noteDigging(id, DiggingAction.DROP_ITEM, System.currentTimeMillis());
        assertFalse(checker.isMining(id));
    }

    @Test
    @DisplayName("Mining does not mark the player as dropping")
    void miningIsNotDropping() {
        checker.noteDigging(id, DiggingAction.START_DIGGING, System.currentTimeMillis());
        assertTrue(checker.isMining(id));
        assertFalse(checker.isDropping(id));
    }
}
