package dev.boondock.bsanticheat.integration;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.List;

/**
 * Integration with LuckPerms for group-based permissions and whitelisting.
 */
public class LuckPermsHook {

    private final Plugin plugin;
    private LuckPerms luckPerms;

    private LuckPermsHook(Plugin plugin, LuckPerms luckPerms) {
        this.plugin = plugin;
        this.luckPerms = luckPerms;
    }

    public static LuckPermsHook tryHook(Plugin plugin) {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            plugin.getLogger().info("LuckPerms nicht gefunden - Gruppen-Whitelist deaktiviert.");
            return null;
        }

        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider != null) {
            LuckPermsHook hook = new LuckPermsHook(plugin, provider.getProvider());
            plugin.getLogger().info("LuckPerms Hook aktiviert - Gruppen-Whitelist verfügbar.");
            return hook;
        }

        plugin.getLogger().warning("LuckPerms gefunden aber API nicht verfügbar!");
        return null;
    }

    /**
     * Check if a player is in any of the whitelisted groups.
     * @param player The player to check
     * @param whitelistGroups List of group names to check against
     * @return true if player is in any whitelisted group
     */
    public boolean isPlayerInWhitelistedGroup(Player player, List<String> whitelistGroups) {
        if (whitelistGroups == null || whitelistGroups.isEmpty()) {
            return false;
        }

        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return false;
        }

        // Get primary group
        String primaryGroup = user.getPrimaryGroup();
        if (whitelistGroups.contains(primaryGroup)) {
            return true;
        }

        // Check all groups (including inherited)
        for (String group : whitelistGroups) {
            if (user.getInheritedGroups(user.getQueryOptions()).stream()
                    .anyMatch(g -> g.getName().equalsIgnoreCase(group))) {
                return true;
            }
        }

        return false;
    }
}
