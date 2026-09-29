package dev.boondock.bsanticheat.integration;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects Bedrock players connected through Geyser/Floodgate so movement checks can exempt
 * them: the Bedrock client uses different movement physics (jump height, sprint, step) that
 * a Java-tuned anticheat would flag as violations.
 *
 * <p>Detection order:
 * <ol>
 *   <li>Floodgate API via reflection ({@code FloodgateApi#isFloodgatePlayer}) — authoritative,
 *       no compile-time dependency so the plugin still loads without Floodgate installed.</li>
 *   <li>UUID heuristic — Floodgate assigns Bedrock players UUIDs whose most-significant bits
 *       are 0. Only trusted when a Geyser/Floodgate plugin is actually present, so it can
 *       never misfire on a normal Java server.</li>
 * </ol>
 *
 * <p>The answer cannot change during a session, so it is cached per player and dropped on
 * join/quit ({@link #invalidate}); the reflective call otherwise ran on every movement.
 *
 * <p>Fails safe: if nothing indicates Bedrock, returns false (player is checked normally).
 */
public class GeyserHook {

    private final Plugin plugin;
    private final boolean platformPresent;
    private final Map<UUID, Boolean> cache = new ConcurrentHashMap<>();

    /** Resolved Floodgate API, or {@link #UNAVAILABLE}. Published once, read from any thread. */
    private volatile FloodgateApi floodgate;

    private record FloodgateApi(Object instance, Method isFloodgatePlayer) {}

    private static final FloodgateApi UNAVAILABLE = new FloodgateApi(null, null);

    private GeyserHook(Plugin plugin, boolean platformPresent) {
        this.plugin = plugin;
        this.platformPresent = platformPresent;
    }

    public static GeyserHook tryHook(Plugin plugin) {
        boolean present = Bukkit.getPluginManager().getPlugin("floodgate") != null
                || Bukkit.getPluginManager().getPlugin("Geyser-Spigot") != null
                || Bukkit.getPluginManager().getPlugin("Geyser") != null;
        if (present) {
            plugin.getLogger().info("Geyser/Floodgate erkannt - Bedrock-Spieler werden von Bewegungs-Checks ausgenommen.");
        } else {
            plugin.getLogger().info("Kein Geyser/Floodgate gefunden - Bedrock-Ausnahme inaktiv.");
        }
        return new GeyserHook(plugin, present);
    }

    /** True when the player connected through Geyser/Floodgate (Bedrock edition). */
    public boolean isBedrock(Player player) {
        if (!platformPresent) return false;
        UUID uuid = player.getUniqueId();
        Boolean cached = cache.get(uuid);
        if (cached != null) return cached;

        Boolean viaApi = viaFloodgateApi(uuid);
        // Heuristic fallback: Floodgate's default UUIDs have msb == 0.
        boolean bedrock = viaApi != null ? viaApi : uuid.getMostSignificantBits() == 0L;
        // Only cache players that are actually online; a lookup for a player already gone
        // would otherwise leave an entry no quit event removes.
        if (player.isOnline()) cache.put(uuid, bedrock);
        return bedrock;
    }

    /** Forget the cached answer for a player (join/quit). */
    public void invalidate(UUID uuid) {
        cache.remove(uuid);
    }

    /** Returns null if the Floodgate API is unavailable/errored (caller falls back). */
    private Boolean viaFloodgateApi(UUID uuid) {
        FloodgateApi api = floodgate;
        if (api == null) {
            synchronized (this) {
                api = floodgate;
                if (api == null) {
                    api = resolveFloodgate();
                    floodgate = api;
                }
            }
        }
        if (api == UNAVAILABLE) return null;
        try {
            return (Boolean) api.isFloodgatePlayer().invoke(api.instance(), uuid);
        } catch (Throwable t) {
            return null;
        }
    }

    private FloodgateApi resolveFloodgate() {
        try {
            // Through Floodgate's own class loader: ours only sees it when the plugin
            // loader chooses to share classes with us.
            Plugin fg = Bukkit.getPluginManager().getPlugin("floodgate");
            ClassLoader loader = fg != null ? fg.getClass().getClassLoader() : GeyserHook.class.getClassLoader();
            // Methods come from the public API interface, never from the implementation
            // class, which need not be accessible.
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true, loader);
            Object instance = api.getMethod("getInstance").invoke(null);
            Method isPlayer = api.getMethod("isFloodgatePlayer", UUID.class);
            return instance != null ? new FloodgateApi(instance, isPlayer) : UNAVAILABLE;
        } catch (Throwable t) {
            return UNAVAILABLE;
        }
    }
}
