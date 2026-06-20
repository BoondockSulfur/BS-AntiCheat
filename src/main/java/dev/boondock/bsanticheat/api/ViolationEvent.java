package dev.boondock.bsanticheat.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired (on the main thread) whenever BSAntiCheat registers a violation for a player.
 * Informational — the detection has already happened; other plugins can listen to react
 * (logging, custom punishments, dashboards, etc.).
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
