package dev.boondock.bsanticheat.anticheat;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;

/**
 * Packet types and enum constants that only exist in newer PacketEvents releases.
 *
 * <p>The plugin compiles against a recent PacketEvents, but the server may run an older one.
 * A direct reference such as {@code PacketType.Play.Client.PUNCH} is linked on first execution
 * and throws {@link NoSuchFieldError} there, taking the whole packet listener down with it. So
 * each such constant is looked up once by name; on an older PacketEvents it resolves to null,
 * and the comparisons below simply never match — which is correct, because such a
 * PacketEvents cannot decode those packets either.
 *
 * <ul>
 *   <li>{@code ATTACK} (PacketEvents 2.12+): MC 26.1+ announces melee attacks with a
 *       dedicated packet instead of INTERACT_ENTITY with action ATTACK.</li>
 *   <li>{@code PUNCH} (PacketEvents 2.14+): MC 26.3+ replaced the serverbound arm swing
 *       (ANIMATION) with this field-less packet.</li>
 *   <li>{@code STAB} (PacketEvents 2.12+): player-digging action for the spear jab
 *       (MC 1.21.11+).</li>
 * </ul>
 */
final class PacketCompat {

    static final PacketTypeCommon ATTACK = clientType("ATTACK");
    static final PacketTypeCommon PUNCH = clientType("PUNCH");
    static final DiggingAction STAB = diggingAction("STAB");

    private PacketCompat() {}

    /** A serverbound play packet type by its PacketEvents name, or null if this PacketEvents lacks it. */
    static PacketTypeCommon clientType(String name) {
        try {
            Object value = PacketType.Play.Client.class.getField(name).get(null);
            return value instanceof PacketTypeCommon t ? t : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** A digging action by name, or null if this PacketEvents lacks it. */
    static DiggingAction diggingAction(String name) {
        try {
            return DiggingAction.valueOf(name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The 26.1+ attack packet. */
    static boolean isAttack(PacketTypeCommon type) {
        return ATTACK != null && type == ATTACK;
    }

    /** An arm swing: ANIMATION up to MC 26.2, PUNCH from 26.3 (always the main hand). */
    static boolean isSwing(PacketTypeCommon type) {
        return type == PacketType.Play.Client.ANIMATION || (PUNCH != null && type == PUNCH);
    }

    static boolean isStab(DiggingAction action) {
        return STAB != null && action == STAB;
    }

    /**
     * Target entity id of an ATTACK packet, or -1 if it cannot be read. The wrapper class is
     * touched only inside {@link AttackReader}, so a PacketEvents without it fails here, inside
     * the catch, instead of while linking the caller.
     */
    static int attackTarget(PacketReceiveEvent event) {
        try {
            return AttackReader.entityId(event);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static final class AttackReader {
        static int entityId(PacketReceiveEvent event) {
            return new com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack(event)
                    .getEntityId();
        }
    }
}
