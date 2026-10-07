package dev.boondock.bsanticheat.integration;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Detects a player's client protocol version through ViaVersion, so servers that allow
 * legacy clients (e.g. 1.8 PvP clients on a 1.21 server) can optionally exempt them: their
 * movement/combat timing differs from the native version and a native-tuned anticheat would
 * flag it.
 *
 * <p>Uses the ViaVersion API by reflection ({@code Via.getAPI().getPlayerVersion(UUID)}), so
 * the plugin loads fine without ViaVersion installed. The method is looked up on the public
 * {@code ViaAPI} interface: the object {@code getAPI()} returns is an implementation class
 * that is not necessarily accessible, and a method taken from it fails on invoke. Fails
 * safe: returns -1 (unknown) when ViaVersion is absent or errors, and {@link #isLegacy}
 * then reports false.
 *
 * <p>Called from region and Netty threads; the API is resolved once and published through
 * a volatile field as one immutable holder.
 */
public class ViaVersionHook {

    private final Plugin plugin;
    private final boolean present;
    private volatile Api api;

    private record Api(Object instance, Method getPlayerVersion) {}

    private static final Api UNAVAILABLE = new Api(null, null);

    private ViaVersionHook(Plugin plugin, boolean present) {
        this.plugin = plugin;
        this.present = present;
    }

    public static ViaVersionHook tryHook(Plugin plugin) {
        boolean present = Bukkit.getPluginManager().getPlugin("ViaVersion") != null;
        if (present) {
            plugin.getLogger().info("ViaVersion found - client version detection available.");
        } else {
            plugin.getLogger().info("ViaVersion not found - legacy client exemption inactive.");
        }
        return new ViaVersionHook(plugin, present);
    }

    /** The player's protocol version number, or -1 if unknown/unavailable. */
    public int protocolVersion(Player player) {
        if (!present) return -1;
        Api resolved = api;
        if (resolved == null) {
            synchronized (this) {
                resolved = api;
                if (resolved == null) {
                    resolved = resolve();
                    api = resolved;
                }
            }
        }
        if (resolved == UNAVAILABLE) return -1;
        try {
            Object result = resolved.getPlayerVersion().invoke(resolved.instance(), player.getUniqueId());
            return result instanceof Integer i ? i : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private Api resolve() {
        try {
            Plugin via = Bukkit.getPluginManager().getPlugin("ViaVersion");
            ClassLoader loader = via != null ? via.getClass().getClassLoader() : ViaVersionHook.class.getClassLoader();
            Class<?> viaClass = Class.forName("com.viaversion.viaversion.api.Via", true, loader);
            Class<?> apiInterface = Class.forName("com.viaversion.viaversion.api.ViaAPI", true, loader);
            Object instance = viaClass.getMethod("getAPI").invoke(null);
            Method getPlayerVersion = apiInterface.getMethod("getPlayerVersion", UUID.class);
            if (instance == null || !apiInterface.isInstance(instance)) {
                plugin.getLogger().warning("ViaVersion API not available - legacy-client exemption inactive.");
                return UNAVAILABLE;
            }
            return new Api(instance, getPlayerVersion);
        } catch (Throwable t) {
            plugin.getLogger().warning("ViaVersion API could not be resolved (" + t.getClass().getSimpleName()
                    + ") - legacy-client exemption inactive.");
            return UNAVAILABLE;
        }
    }

    /**
     * True when the player connects with a protocol older than {@code threshold} (i.e. a
     * legacy client translated by ViaVersion). Unknown versions are never treated as legacy.
     */
    public boolean isLegacy(Player player, int threshold) {
        int v = protocolVersion(player);
        return v > 0 && v < threshold;
    }
}
