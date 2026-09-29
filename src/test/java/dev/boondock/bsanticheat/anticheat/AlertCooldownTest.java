package dev.boondock.bsanticheat.anticheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Alert cooldowns are timestamps, not scheduled removals: a stale timer from an earlier alert
 * can then never end the cooldown of a newer one, and removing the entry (clearAlerts) is all
 * it takes to re-open notification.
 *
 * <p>The managers themselves cannot be built under MockBukkit (their constructors schedule on
 * the global region), so the cooldown rule is exercised in its static form.
 */
class AlertCooldownTest {

    private static final long COOLDOWN = 5 * 60 * 1000L;

    @Test
    @DisplayName("X-Ray: one summary per cooldown, re-opened once the entry is cleared")
    void xrayNotifyCooldown() {
        Map<UUID, Long> until = new ConcurrentHashMap<>();
        UUID id = UUID.randomUUID();
        long now = 1_000_000L;
        assertTrue(XRayAlertManager.tryStartCooldown(until, id, now, COOLDOWN), "first alert notifies");
        assertFalse(XRayAlertManager.tryStartCooldown(until, id, now + 1000, COOLDOWN),
                "inside the cooldown it stays quiet");
        assertTrue(XRayAlertManager.tryStartCooldown(until, id, now + COOLDOWN, COOLDOWN),
                "after it, notifies again");

        until.remove(id); // what clearAlerts does
        assertTrue(XRayAlertManager.tryStartCooldown(until, id, now + COOLDOWN + 1, COOLDOWN),
                "clearing re-opens notification at once");
        assertFalse(XRayAlertManager.tryStartCooldown(until, id, now + COOLDOWN + 2, COOLDOWN),
                "and the new cooldown then holds for its full length");
    }

    @Test
    @DisplayName("Movement: cooldown per type is checked and started in one step")
    void movementCooldown() {
        Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();
        UUID id = UUID.randomUUID();
        long now = 1_000_000L;
        assertTrue(MovementAlertManager.tryStartCooldown(cooldowns, id, "SPEED", now, COOLDOWN));
        assertFalse(MovementAlertManager.tryStartCooldown(cooldowns, id, "SPEED", now + 1, COOLDOWN),
                "same type inside the cooldown");
        assertTrue(MovementAlertManager.tryStartCooldown(cooldowns, id, "FLY", now + 1, COOLDOWN),
                "another type has its own cooldown");
        assertTrue(MovementAlertManager.tryStartCooldown(cooldowns, id, "SPEED", now + COOLDOWN, COOLDOWN),
                "after the cooldown the type alerts again");
    }
}
