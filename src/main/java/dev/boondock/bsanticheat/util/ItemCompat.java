package dev.boondock.bsanticheat.util;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;

/**
 * Item data that only newer server versions expose, read without linking against it.
 *
 * <p>The plugin compiles against the oldest supported Paper API. The spear (MC 1.21.11) brought
 * the {@code attack_range} item component and the Lunge enchantment, neither of which exists
 * there, so both are resolved once by name. On an older server every lookup reports "absent"
 * and callers keep their previous behaviour.
 */
public final class ItemCompat {

    /** Reach data from an item's {@code attack_range} component. */
    public record AttackRange(double maxReach, double hitboxMargin) {}

    private static final Object ATTACK_RANGE_TYPE;
    private static final Method GET_DATA;
    private static final Method MAX_REACH;
    private static final Method HITBOX_MARGIN;
    private static volatile Enchantment lunge;
    private static volatile boolean lungeResolved;

    static {
        Object type = null;
        Method getData = null;
        Method maxReach = null;
        Method margin = null;
        try {
            Class<?> types = Class.forName("io.papermc.paper.datacomponent.DataComponentTypes");
            type = types.getField("ATTACK_RANGE").get(null);
            Class<?> valued = Class.forName("io.papermc.paper.datacomponent.DataComponentType$Valued");
            getData = ItemStack.class.getMethod("getData", valued);
            Class<?> range = Class.forName("io.papermc.paper.datacomponent.item.AttackRange");
            maxReach = range.getMethod("maxReach");
            margin = range.getMethod("hitboxMargin");
        } catch (Throwable t) {
            type = null;
        }
        ATTACK_RANGE_TYPE = type;
        GET_DATA = type != null ? getData : null;
        MAX_REACH = type != null ? maxReach : null;
        HITBOX_MARGIN = type != null ? margin : null;
    }

    private ItemCompat() {}

    /** Whether this server exposes the {@code attack_range} component at all. */
    public static boolean attackRangeSupported() {
        return ATTACK_RANGE_TYPE != null;
    }

    /**
     * The item's effective {@code attack_range} (prototype or patched), or null when the item
     * has none or the server predates the component.
     */
    public static AttackRange attackRange(ItemStack item) {
        if (ATTACK_RANGE_TYPE == null || item == null || item.getType().isAir()) return null;
        try {
            Object data = GET_DATA.invoke(item, ATTACK_RANGE_TYPE);
            if (data == null) return null;
            double max = ((Number) MAX_REACH.invoke(data)).doubleValue();
            double margin = ((Number) HITBOX_MARGIN.invoke(data)).doubleValue();
            if (!Double.isFinite(max) || !Double.isFinite(margin)) return null;
            return new AttackRange(max, Math.max(0.0, margin));
        } catch (Throwable t) {
            return null;
        }
    }

    /** The Lunge enchantment, or null on servers that predate it. Looked up once. */
    public static Enchantment lungeEnchantment() {
        if (!lungeResolved) {
            Enchantment found = null;
            try {
                found = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("lunge"));
            } catch (Throwable ignored) {
                // registry not available in this environment
            }
            lunge = found;
            lungeResolved = true;
        }
        return lunge;
    }

    /** Lunge level on the item, 0 when absent or unsupported. */
    public static int lungeLevel(ItemStack item) {
        Enchantment e = lungeEnchantment();
        if (e == null || item == null || item.getType().isAir()) return 0;
        try {
            return item.getEnchantmentLevel(e);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Whether the item is a spear, by material name (the materials are newer than the API). */
    public static boolean isSpear(ItemStack item) {
        return item != null && item.getType().name().endsWith("_SPEAR");
    }
}
