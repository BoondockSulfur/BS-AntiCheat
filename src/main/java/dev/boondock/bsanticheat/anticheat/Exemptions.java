package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/**
 * Shared exemption logic used by the checks (UUID whitelist, OP bypass, bypass
 * permission, LuckPerms group whitelist, creative/spectator).
 */
final class Exemptions {

    private Exemptions() {}

    static boolean isExempt(Player p, PluginConfig config, LuckPermsHook luckPerms) {
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return true;
        if (config.anticheatWhitelistPlayers().contains(p.getUniqueId().toString())) return true;
        if (p.isOp() && config.opsBypass()) return true;
        if (!p.isOp() && p.hasPermission("bsanticheat.bypass")) return true;
        if (luckPerms != null && luckPerms.isPlayerInWhitelistedGroup(p, config.anticheatWhitelistGroups())) return true;
        return false;
    }
}
