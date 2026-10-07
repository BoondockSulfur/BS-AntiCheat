package dev.boondock.bsanticheat.anticheat;

import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packet types from newer protocol versions, resolved by name so an older PacketEvents on the
 * server cannot break the listener with a NoSuchFieldError.
 */
class PacketCompatTest {

    @Test
    @DisplayName("The 26.1 attack packet is recognised as an attack")
    void attackPacketResolves() {
        assertSame(PacketType.Play.Client.ATTACK, PacketCompat.ATTACK);
        assertTrue(PacketCompat.isAttack(PacketType.Play.Client.ATTACK));
        assertFalse(PacketCompat.isAttack(PacketType.Play.Client.INTERACT_ENTITY),
                "INTERACT_ENTITY is decoded separately, by its action");
    }

    @Test
    @DisplayName("Both the old arm swing and the 26.3 PUNCH count as a swing")
    void punchIsASwing() {
        assertSame(PacketType.Play.Client.PUNCH, PacketCompat.PUNCH);
        assertTrue(PacketCompat.isSwing(PacketType.Play.Client.ANIMATION));
        assertTrue(PacketCompat.isSwing(PacketType.Play.Client.PUNCH));
        assertFalse(PacketCompat.isSwing(PacketType.Play.Client.ATTACK), "an attack is not a swing packet");
        assertFalse(PacketCompat.isSwing(null));
    }

    @Test
    @DisplayName("The spear jab digging action is recognised")
    void stabResolves() {
        assertSame(DiggingAction.STAB, PacketCompat.STAB);
        assertTrue(PacketCompat.isStab(DiggingAction.STAB));
        assertFalse(PacketCompat.isStab(DiggingAction.START_DIGGING));
    }

    @Test
    @DisplayName("A constant this PacketEvents lacks resolves to null instead of throwing")
    void missingConstantsAreNull() {
        // What an older PacketEvents on the server looks like to this class.
        assertNull(PacketCompat.clientType("NOT_A_PACKET_TYPE"));
        assertNull(PacketCompat.diggingAction("NOT_AN_ACTION"));
    }
}
