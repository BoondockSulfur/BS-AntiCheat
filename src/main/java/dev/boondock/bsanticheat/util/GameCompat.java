package dev.boondock.bsanticheat.util;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Blocks, entities and attributes that only newer server versions have, looked up by name.
 *
 * <p>The plugin compiles against the oldest supported Paper API, where none of these exist.
 * Each is resolved once; on an older server the lookup finds nothing and the callers behave
 * as before. Entity types are compared by name, which works for both the enum of older APIs
 * and the registry-backed type of newer ones.
 */
public final class GameCompat {

    // Entity type names (MC 1.21.11 / 26.x).
    public static final String NAUTILUS = "NAUTILUS";
    public static final String ZOMBIE_NAUTILUS = "ZOMBIE_NAUTILUS";
    public static final String HAPPY_GHAST = "HAPPY_GHAST";
    public static final String CAMEL_HUSK = "CAMEL_HUSK";
    public static final String CUSHION = "CUSHION";
    public static final String SULFUR_CUBE = "SULFUR_CUBE";

    /** Bounces like a bed (26.3). Null on servers without it. */
    public static final Material SHELF_MUSHROOM = Material.getMaterial("SHELF_MUSHROOM");
    /** Under a water column it forms a geyser that pushes entities upwards (26.2). */
    public static final Material POTENT_SULFUR = Material.getMaterial("POTENT_SULFUR");

    private static volatile List<Attribute> physicsAttributes;

    private GameCompat() {}

    /** Name of the entity's type, or "" when it has none. */
    public static String typeName(Entity entity) {
        if (entity == null || entity.getType() == null) return "";
        return entity.getType().name();
    }

    public static boolean isType(Entity entity, String name) {
        return name.equals(typeName(entity));
    }

    /** Nautilus or zombie nautilus: an underwater mount with a dash. */
    public static boolean isNautilus(Entity entity) {
        String n = typeName(entity);
        return NAUTILUS.equals(n) || ZOMBIE_NAUTILUS.equals(n);
    }

    /**
     * Blocks that throw a landing player back up: slime, every bed colour (26.2: 75% of the
     * impact speed) and the shelf mushroom (26.3).
     */
    public static boolean isBounceBlock(Material m) {
        if (m == null) return false;
        if (m == Material.SLIME_BLOCK) return true;
        if (SHELF_MUSHROOM != null && m == SHELF_MUSHROOM) return true;
        return m.isBlock() && Tag.BEDS.isTagged(m);
    }

    /**
     * Bounce blocks other than slime: every bed colour and the shelf mushroom. They only throw
     * a player back up (no sideways launch), and only after a fall onto them.
     */
    public static boolean isBedLikeBounceBlock(Material m) {
        if (m == null) return false;
        if (SHELF_MUSHROOM != null && m == SHELF_MUSHROOM) return true;
        return m.isBlock() && Tag.BEDS.isTagged(m);
    }

    /**
     * The movement-physics attributes added in 26.2 (air drag, friction, bounciness), or an
     * empty list on servers without them.
     */
    public static List<Attribute> physicsAttributes() {
        List<Attribute> list = physicsAttributes;
        if (list == null) {
            List<Attribute> found = new ArrayList<>();
            for (String key : new String[]{"air_drag_modifier", "friction_modifier", "bounciness"}) {
                try {
                    Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
                    if (a != null) found.add(a);
                } catch (Throwable ignored) {
                    // registry unavailable in this environment
                }
            }
            list = Collections.unmodifiableList(found);
            physicsAttributes = list;
        }
        return list;
    }

    /** Replaces the resolved attribute list; null resolves again. For tests: the mock server predates them. */
    public static void overridePhysicsAttributes(List<Attribute> attributes) {
        physicsAttributes = attributes == null ? null : List.copyOf(attributes);
    }
}
