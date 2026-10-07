package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.util.ItemCompat;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The lunge record the movement checks read. */
class LungeTrackerTest {

    private static final long T = 5_000_000L;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("A lunge is remembered with its time and level")
    void lungeIsRecorded() {
        LungeTracker t = new LungeTracker();
        UUID id = UUID.randomUUID();
        t.noteLunge(id, T, 2);
        assertEquals(new LungeTracker.Lunge(T, 2), t.lastLunge(id));
    }

    @Test
    @DisplayName("An older lunge arriving late does not replace a newer one")
    void newestWins() {
        // Region-thread lookups can finish out of order.
        LungeTracker t = new LungeTracker();
        UUID id = UUID.randomUUID();
        t.noteLunge(id, T + 100, 3);
        t.noteLunge(id, T, 1);
        assertEquals(T + 100, t.lastLunge(id).timeMs());
    }

    @Test
    @DisplayName("Level 0 is not a lunge")
    void levelZeroIgnored() {
        LungeTracker t = new LungeTracker();
        UUID id = UUID.randomUUID();
        t.noteLunge(id, T, 0);
        assertNull(t.lastLunge(id));
    }

    @Test
    @DisplayName("A hotbar change later than one tick after the jab is not recorded")
    void lateSwapIgnored() {
        LungeTracker t = new LungeTracker();
        UUID id = UUID.randomUUID();
        t.noteStab(id, T);
        t.noteHeldSlotChange(id, T + LungeTracker.SAME_TICK_MS + 1);
        assertEquals(-1L, t.lastStabSwap(id));
        t.noteHeldSlotChange(UUID.randomUUID(), T); // nobody jabbed
        assertEquals(0L, t.stabSwapCount(id));
    }

    @Test
    @DisplayName("Cleanup forgets the player")
    void cleanupForgets() {
        LungeTracker t = new LungeTracker();
        UUID id = UUID.randomUUID();
        t.noteStab(id, T);
        t.noteLunge(id, T, 1);
        t.noteHeldSlotChange(id, T + 10);
        t.cleanup(id);
        assertNull(t.lastLunge(id));
        assertEquals(-1L, t.lastStab(id));
        assertEquals(-1L, t.lastStabSwap(id));
    }

    @Test
    @DisplayName("On a server without Lunge or spears the item lookups report absent")
    void itemLookupsDegrade() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        assertEquals(0, ItemCompat.lungeLevel(sword));
        assertFalse(ItemCompat.isSpear(sword));
        assertEquals(0, ItemCompat.lungeLevel(null));
    }

    @Test
    @DisplayName("A jab without the server's exhaustion evidence is no lunge")
    void stabWithoutEvidenceIsNoLunge() {
        LungeTracker t = new LungeTracker();
        UUID id = UUID.randomUUID();
        // STAB spam every 250 ms: the client can send it at will.
        for (int i = 0; i < 20; i++) t.noteLungeCandidate(id, T + i * 250L, 3, 0, T + i * 250L + 20);
        assertNull(t.lastLunge(id));
    }

    @Test
    @DisplayName("Jab and matching exhaustion confirm the lunge, in either order")
    void evidenceConfirms() {
        LungeTracker t = new LungeTracker();
        UUID a = UUID.randomUUID();
        t.noteLungeCandidate(a, T, 2, 0, T + 20);
        t.noteExhaustion(a, 8.0f, T + 60);
        assertEquals(new LungeTracker.Lunge(T, 2), t.lastLunge(a));

        UUID b = UUID.randomUUID();
        t.noteExhaustion(b, 12.0f, T + 10);
        t.noteLungeCandidate(b, T, 3, 0, T + 40);
        assertEquals(new LungeTracker.Lunge(T, 3), t.lastLunge(b));
    }

    @Test
    @DisplayName("Exhaustion of another size or too far away confirms nothing")
    void mismatchedEvidenceIsIgnored() {
        LungeTracker t = new LungeTracker();
        UUID id = UUID.randomUUID();
        t.noteLungeCandidate(id, T, 2, 0, T);
        t.noteExhaustion(id, 0.1f, T + 10);  // an attack
        t.noteExhaustion(id, 4.0f, T + 20);  // level 1, but the spear has Lunge II
        t.noteExhaustion(id, 8.0f, T + LungeTracker.EVIDENCE_WINDOW_MS + 50);
        assertNull(t.lastLunge(id));
    }

    @Test
    @DisplayName("Confirmed lunges are limited to one per attack cooldown")
    void lungesAreRateLimited() {
        LungeTracker t = new LungeTracker();
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 8; i++) {
            long stab = T + i * 250L;
            t.noteLungeCandidate(id, stab, 1, 0, stab);
            t.noteExhaustion(id, 4.0f, stab + 10);
        }
        // Unknown cooldown: at most one per second, so the stabs at T and T+1000 count.
        assertEquals(T + 1000L, t.lastLunge(id).timeMs());

        UUID fast = UUID.randomUUID();
        t.noteLungeCandidate(fast, T, 1, 650, T);
        t.noteExhaustion(fast, 4.0f, T + 10);
        t.noteLungeCandidate(fast, T + 400, 1, 650, T + 400);
        t.noteExhaustion(fast, 4.0f, T + 410);
        assertEquals(T, t.lastLunge(fast).timeMs(), "inside the spear's cooldown");
        t.noteLungeCandidate(fast, T + 700, 1, 650, T + 700);
        t.noteExhaustion(fast, 4.0f, T + 710);
        assertEquals(T + 700, t.lastLunge(fast).timeMs(), "a full charge later");
    }
}
