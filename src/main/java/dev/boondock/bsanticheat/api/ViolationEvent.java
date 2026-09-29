package dev.boondock.bsanticheat.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired whenever BSAntiCheat registers a violation for a player.
 *
 * <p>Fired on the thread owning the player: the main thread on Paper, the player's region
 * thread on Folia, so listeners may touch the player directly. If the player was removed
 * before the event could run there, it is fired on the global region instead.
 *
 * <p>It is fired before any punishment tier commands for the same violation run.
 * Informational and deliberately not cancellable — the detection has already happened and
 * the violation level is already counted; other plugins can listen to react (logging, custom
 * punishments, dashboards, etc.).
 */
public class ViolationEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String checkType;
    private final int violationLevel;

    public ViolationEvent(Player player, String checkType, int violationLevel) {
        this.player = player;
        this.checkType = checkType;
        this.violationLevel = violationLevel;
    }

    /** The flagged player. */
    public Player getPlayer() {
        return player;
    }

    /** Check identifier, e.g. SPEED, FLY, XRAY_THRESHOLD, AUTOCLICKER, REACH, NUKER. */
    public String getCheckType() {
        return checkType;
    }

    /** The player's current violation level for this check after this violation. */
    public int getViolationLevel() {
        return violationLevel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
