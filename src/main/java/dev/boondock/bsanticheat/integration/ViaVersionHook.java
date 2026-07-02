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
 * the plugin loads fine without ViaVersion installed. Fails safe: returns -1 (unknown) when
 * ViaVersion is absent or errors, and {@link #isLegacy} then reports false.
 */
public class ViaVersionHook {

    private final Plugin plugin;
    private final boolean present;
    private Object viaApi;         // ViaAPI instance
    private Method getPlayerVersion;
    private boolean resolved;

    private ViaVersionHook(Plugin plugin, boolean present) {
        this.plugin = plugin;
        this.present = present;
    }

    public static ViaVersionHook tryHook(Plugin plugin) {
        boolean present = Bukkit.getPluginManager().getPlugin("ViaVersion") != null;
        if (present) {
            plugin.getLogger().info("ViaVersion erkannt - Client-Versionserkennung verfügbar.");
        } else {
            plugin.getLogger().info("Kein ViaVersion gefunden - Legacy-Client-Ausnahme inaktiv.");
        }
        return new ViaVersionHook(plugin, present);
    }

    /** The player's protocol version number, or -1 if unknown/unavailable. */
    public int protocolVersion(Player player) {
        if (!present) return -1;
        if (!resolved) {
            resolved = true;
            try {
                Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
                viaApi = via.getMethod("getAPI").invoke(null);
                getPlayerVersion = viaApi.getClass().getMethod("getPlayerVersion", UUID.class);
            } catch (Throwable t) {
                viaApi = null;
                getPlayerVersion = null;
            }
        }
        if (viaApi == null || getPlayerVersion == null) return -1;
        try {
            Object result = getPlayerVersion.invoke(viaApi, player.getUniqueId());
            return result instanceof Integer ? (Integer) result : -1;
        } catch (Throwable t) {
            return -1;
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
