package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.util.Constants;
import dev.boondock.bsanticheat.util.ItemCompat;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reach limits from a weapon's attack_range component (the spear, MC 1.21.11+).
 */
class WeaponReachTest extends ScenarioBase {

    @Test
    @DisplayName("A spear's reach and hitbox margin raise the limit")
    void spearRaisesLimit() {
        // Vanilla spear: max reach 4.5, hitbox margin 0.125.
        double limit = CombatChecker.itemReachLimit(4.0, new ItemCompat.AttackRange(4.5, 0.125));
        assertEquals(4.5 + 0.125 + Constants.REACH_ATTRIBUTE_SLACK, limit, 1e-9);
        assertTrue(limit > 4.625, "a hit at the spear's full reach must stay under the limit");
    }

    @Test
    @DisplayName("A weapon with shorter reach never lowers the configured limit")
    void shortWeaponKeepsLimit() {
        assertEquals(4.0, CombatChecker.itemReachLimit(4.0, new ItemCompat.AttackRange(2.0, 0.0)), 1e-9);
    }

    @Test
    @DisplayName("Without the component the limit is unchanged")
    void noComponentNoChange() {
        assertEquals(4.0, CombatChecker.itemReachLimit(4.0, null), 1e-9);
    }

    @Test
    @DisplayName("On a server without attack_range the lookup reports absent instead of failing")
    void oldApiDegrades() {
        // The build compiles against 1.21.10, which predates the component.
        assertFalse(ItemCompat.attackRangeSupported());
        assertNull(ItemCompat.attackRange(new ItemStack(Material.DIAMOND_SWORD)));
        assertNull(ItemCompat.attackRange(null));
    }
}
