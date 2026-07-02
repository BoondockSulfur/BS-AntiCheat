package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.integration.GeyserHook;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.integration.ViaVersionHook;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/**
 * Shared exemption logic used by the checks (UUID whitelist, OP bypass, bypass
 * permission, LuckPerms group whitelist, creative/spectator, Bedrock/Geyser, legacy client).
 */
public final class Exemptions {

    // Set once at enable. Static because it is read-only after startup and shared by every
    // check, so it needn't be threaded through each checker like the per-instance hooks.
    private static ViaVersionHook via;

    private Exemptions() {}

    public static void setViaVersion(ViaVersionHook hook) {
        via = hook;
    }

    static boolean isExempt(Player p, PluginConfig config, LuckPermsHook luckPerms, GeyserHook geyser) {
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return true;
        if (isBedrockExempt(p, config, geyser)) return true;
        if (isLegacyExempt(p, config)) return true;
        if (config.anticheatWhitelistPlayers().contains(p.getUniqueId().toString())) return true;
        if (p.isOp() && config.opsBypass()) return true;
        if (!p.isOp() && p.hasPermission("bsanticheat.bypass")) return true;
        if (luckPerms != null && luckPerms.isPlayerInWhitelistedGroup(p, config.anticheatWhitelistGroups())) return true;
        return false;
    }

    /** True when the player is a Bedrock (Geyser/Floodgate) client and the exemption is on. */
    static boolean isBedrockExempt(Player p, PluginConfig config, GeyserHook geyser) {
        return geyser != null && config.exemptBedrockPlayers() && geyser.isBedrock(p);
    }

    /** True when the player is on a legacy client (via ViaVersion) and the exemption is on. */
    static boolean isLegacyExempt(Player p, PluginConfig config) {
        return via != null && config.exemptLegacyClients() && via.isLegacy(p, config.legacyProtocolThreshold());
    }
}
