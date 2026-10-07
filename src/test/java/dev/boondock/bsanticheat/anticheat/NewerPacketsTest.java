package dev.boondock.bsanticheat.anticheat;

import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The attack record, hotbar bookkeeping and extended BadPackets rules, driven the way both
 * attack packet generations and the hotbar packet reach them.
 */
class NewerPacketsTest extends ScenarioBase {

    private static final long T = 5_000_000L;
    private PacketChecker checker;
    private MeleeTracker melee;
    private LungeTracker lunges;

    @BeforeEach
    void setUpChecker() {
        checker = new PacketChecker(plugin, config, null, lang);
        melee = new MeleeTracker();
        lunges = new LungeTracker();
        checker.setMeleeTracker(melee);
        checker.setLungeTracker(lunges);
        checker.setViolationManager(violations);
    }

    @Test
    @DisplayName("An attack packet vouches for the hit and ends the mining window")
    void attackFeedsMeleeAndEndsMining() {
        // Same entry point for INTERACT_ENTITY(ATTACK) and the 26.1 ATTACK packet.
        UUID id = UUID.randomUUID();
        checker.noteDigging(id, DiggingAction.START_DIGGING, System.currentTimeMillis());
        assertTrue(checker.isMining(id), "fixture check: digging");
        long now = System.currentTimeMillis();
        checker.onAttackPacket(id, "p", 42, 7, now);
        assertTrue(melee.isMeleeHit(id, 42, now + 10), "the hit is a melee hit");
        assertFalse(checker.isMining(id), "vanilla aborts a dig before attacking");
    }

    @Test
    @DisplayName("Attacking one's own entity raises BADPACKETS")
    void selfAttackIsCaught() {
        PlayerMock p = player(0.5, 64.0, 0.5);
        checker.onAttackPacket(p.getUniqueId(), p.getName(), 7, 7, System.currentTimeMillis());
        server.getScheduler().performTicks(2);
        assertEquals(1, violations.count("BADPACKETS"));
    }

    @Test
    @DisplayName("Attacking another entity raises nothing")
    void otherAttackIsQuiet() {
        PlayerMock p = player(0.5, 64.0, 0.5);
        checker.onAttackPacket(p.getUniqueId(), p.getName(), 8, 7, System.currentTimeMillis());
        server.getScheduler().performTicks(2);
        assertEquals(0, violations.count("BADPACKETS"));
    }

    @Test
    @DisplayName("A hotbar slot outside 0-8 is out of range")
    void slotOutOfRange() {
        UUID id = UUID.randomUUID();
        assertEquals(PacketChecker.SLOT_OUT_OF_RANGE, checker.noteHeldSlotPacket(id, 9, T));
        assertEquals(PacketChecker.SLOT_OUT_OF_RANGE, checker.noteHeldSlotPacket(id, -1, T));
        assertEquals(PacketChecker.SLOT_OK, checker.noteHeldSlotPacket(id, 8, T));
        assertEquals(8, checker.clientSlot(id), "a valid slot is remembered");
    }

    @Test
    @DisplayName("Ordinary hotbar scrolling never flags")
    void alternatingSlotsAreQuiet() {
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 50; i++) {
            assertEquals(PacketChecker.SLOT_OK, checker.noteHeldSlotPacket(id, i % 9, T + i));
        }
    }

    @Test
    @DisplayName("The same slot sent repeatedly in a short window is a duplicate streak")
    void duplicateStreak() {
        UUID id = UUID.randomUUID();
        assertEquals(PacketChecker.SLOT_OK, checker.noteHeldSlotPacket(id, 3, T));
        assertEquals(PacketChecker.SLOT_OK, checker.noteHeldSlotPacket(id, 3, T + 10));
        assertEquals(PacketChecker.SLOT_OK, checker.noteHeldSlotPacket(id, 3, T + 20));
        assertEquals(PacketChecker.SLOT_DUPLICATE_STREAK, checker.noteHeldSlotPacket(id, 3, T + 30));
    }

    @Test
    @DisplayName("Duplicates spread far apart do not add up")
    void spreadDuplicatesAreQuiet() {
        UUID id = UUID.randomUUID();
        checker.noteHeldSlotPacket(id, 3, T);
        for (int i = 1; i <= 6; i++) {
            assertEquals(PacketChecker.SLOT_OK, checker.noteHeldSlotPacket(id, 3, T + i * 3000L));
        }
    }

    @Test
    @DisplayName("A slot the server set may be sent by the client without counting as a repeat")
    void serverSetSlotResetsBaseline() {
        UUID id = UUID.randomUUID();
        checker.noteHeldSlotPacket(id, 3, T);
        checker.noteHeldSlotPacket(id, 3, T + 10);
        checker.noteHeldSlotPacket(id, 3, T + 20);
        checker.noteServerSlot(id, 3);
        assertEquals(PacketChecker.SLOT_OK, checker.noteHeldSlotPacket(id, 3, T + 30));
        assertEquals(3, checker.clientSlot(id));
    }

    @Test
    @DisplayName("Flight claims need several inside the window")
    void flightClaimsNeedARepeat() {
        UUID id = UUID.randomUUID();
        // A claim crossing a revocation in flight happens once; that must not flag.
        assertEquals(0, checker.noteFlightClaim(id, T));
        assertEquals(0, checker.noteFlightClaim(id, T + 20_000L), "the window has lapsed");
        assertEquals(0, checker.noteFlightClaim(id, T + 20_100L));
        assertEquals(3, checker.noteFlightClaim(id, T + 20_200L));
    }

    @Test
    @DisplayName("A flight claim is judged against the abilities last sent to the client")
    void flightClaimJudgedAgainstSentAbilities() {
        UUID id = UUID.randomUUID();
        assertEquals(PacketChecker.CLAIM_UNKNOWN, checker.judgeFlightClaim(id, T, 50));
        // Double-jump plugin: flight granted on landing, the client claims it...
        checker.noteAbilitiesSent(id, true, T);
        assertEquals(PacketChecker.CLAIM_ALLOWED, checker.judgeFlightClaim(id, T + 1000, 50));
        // ...the plugin cancels the toggle and revokes flight; a claim right after crossed it.
        checker.noteAbilitiesSent(id, false, T + 1010);
        assertEquals(PacketChecker.CLAIM_IN_FLIGHT, checker.judgeFlightClaim(id, T + 1020, 50));
        assertEquals(PacketChecker.CLAIM_IN_FLIGHT, checker.judgeFlightClaim(id, T + 1300, 50));
        assertEquals(PacketChecker.CLAIM_DISALLOWED, checker.judgeFlightClaim(id, T + 1400, 50));
        // The round trip counted into the grace is bounded.
        assertEquals(PacketChecker.CLAIM_DISALLOWED, checker.judgeFlightClaim(id, T + 1010
                + dev.boondock.bsanticheat.util.Constants.BADPACKETS_ABILITIES_MAX_RTT_MS
                + dev.boondock.bsanticheat.util.Constants.BADPACKETS_ABILITIES_MARGIN_MS + 1, 60_000));
    }

    /**
     * The claim path as the packet handler runs it: judged at arrival, confirmed later on the
     * player's thread (the mock server has no entity scheduler, so the confirmation runs here,
     * after the events the test simulates).
     */
    private void claim(PlayerMock p, long t, double rttMs) {
        if (PacketChecker.needsConfirmation(checker.judgeFlightClaim(p.getUniqueId(), t, rttMs))) {
            pendingClaims.add(t);
        }
    }

    private final java.util.List<Long> pendingClaims = new java.util.ArrayList<>();

    private void confirmClaims(PlayerMock p) {
        for (long t : pendingClaims) checker.confirmFlightClaim(p, t);
        pendingClaims.clear();
    }

    @Test
    @DisplayName("Double jumps (flight allowed, toggle cancelled, flight revoked) never raise BADPACKETS")
    void doubleJumpPluginIsQuiet() {
        PlayerMock p = player(0.5, 64.0, 0.5);
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            long t = now + i * 100L;
            checker.noteAbilitiesSent(id, true, t);        // plugin: setAllowFlight(true) on landing
            claim(p, t + 40, 50);                          // client double-taps jump
            checker.noteToggleFlight(id, t + 45);          // server fires the event, plugin cancels it
            p.setAllowFlight(false);                       // ...and revokes flight
            checker.noteAbilitiesSent(id, false, t + 46);
        }
        confirmClaims(p);
        assertEquals(0, violations.count("BADPACKETS"));
    }

    @Test
    @DisplayName("A claim matching a toggle event is ignored even without known abilities")
    void claimWithToggleEventIsQuiet() {
        PlayerMock p = player(0.5, 64.0, 0.5);
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 4; i++) {
            claim(p, now + i * 100L, 50);
            checker.noteToggleFlight(id, now + i * 100L + 5);
        }
        confirmClaims(p);
        assertEquals(0, violations.count("BADPACKETS"));
    }

    @Test
    @DisplayName("Repeated claims long after flight was revoked raise BADPACKETS")
    void claimsAfterRevocationAreCaught() {
        PlayerMock p = player(0.5, 64.0, 0.5);
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        checker.noteAbilitiesSent(id, false, now - 5000);
        for (int i = 0; i < 3; i++) claim(p, now + i * 100L, 50);
        confirmClaims(p);
        assertEquals(1, violations.count("BADPACKETS"));
    }

    @Test
    @DisplayName("A hotbar change in the tick after a jab is recorded for the movement side")
    void swapAfterStabIsRecorded() {
        UUID id = UUID.randomUUID();
        lunges.noteStab(id, T);
        checker.noteHeldSlotPacket(id, 2, T + 20);
        assertEquals(T + 20, lunges.lastStabSwap(id));
        assertEquals(1, lunges.stabSwapCount(id));
    }

    @Test
    @DisplayName("Without spear items on this server version a jab records no lunge")
    void noLungeWithoutSpear() {
        PlayerMock p = player(0.5, 64.0, 0.5);
        p.getInventory().setItem(0, new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND_SWORD));
        checker.resolveLunge(p, 0, T);
        assertNull(lunges.lastLunge(p.getUniqueId()));
    }
}
