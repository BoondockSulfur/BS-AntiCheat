package dev.boondock.bsanticheat.integration;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

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
 * <p>Fails safe: if nothing indicates Bedrock, returns false (player is checked normally).
 */
public class GeyserHook {

    private final Plugin plugin;
    private final boolean platformPresent;
    private Method floodgateIsPlayer;   // FloodgateApi#isFloodgatePlayer(UUID)
    private Object floodgateInstance;    // FloodgateApi#getInstance()
    private boolean floodgateResolved;

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

        Boolean viaApi = viaFloodgateApi(uuid);
        if (viaApi != null) return viaApi;

        // Heuristic fallback: Floodgate's default UUIDs have msb == 0.
        return uuid.getMostSignificantBits() == 0L;
    }

    /** Returns null if the Floodgate API is unavailable/errored (caller falls back). */
    private Boolean viaFloodgateApi(UUID uuid) {
        if (!floodgateResolved) {
            floodgateResolved = true;
            try {
                Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                floodgateInstance = api.getMethod("getInstance").invoke(null);
                floodgateIsPlayer = api.getMethod("isFloodgatePlayer", UUID.class);
            } catch (Throwable t) {
                floodgateInstance = null;
                floodgateIsPlayer = null;
            }
        }
        if (floodgateInstance == null || floodgateIsPlayer == null) return null;
        try {
            return (Boolean) floodgateIsPlayer.invoke(floodgateInstance, uuid);
        } catch (Throwable t) {
            return null;
        }
    }
}
