package dev.boondock.bsanticheat.integration;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.event.group.GroupDataRecalculateEvent;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Integration with LuckPerms for group-based permissions and whitelisting.
 *
 * <p>The group check runs on every exemption test, i.e. per movement and per packet, so
 * results are cached per player. An entry is valid only for the exact whitelist-group list
 * it was computed against (the config hands out one immutable list per reload) and is
 * dropped when LuckPerms recalculates the user or any group, on join/quit, and after
 * {@link #CACHE_TTL_MS} as a backstop.
 */
public class LuckPermsHook {

    static final long CACHE_TTL_MS = 30_000L;

    private final Plugin plugin;
    private final LuckPerms luckPerms;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(List<String> groups, boolean result, long computedAt) {}

    private LuckPermsHook(Plugin plugin, LuckPerms luckPerms) {
        this.plugin = plugin;
        this.luckPerms = luckPerms;
    }

    public static LuckPermsHook tryHook(Plugin plugin) {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            plugin.getLogger().info("LuckPerms not found - group whitelist disabled.");
            return null;
        }

        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider != null) {
            LuckPermsHook hook = new LuckPermsHook(plugin, provider.getProvider());
            hook.subscribeInvalidation();
            plugin.getLogger().info("LuckPerms hooked - group whitelist available.");
            return hook;
        }

        plugin.getLogger().warning("LuckPerms found, but its API is not available.");
        return null;
    }

    private void subscribeInvalidation() {
        try {
            luckPerms.getEventBus().subscribe(plugin, UserDataRecalculateEvent.class,
                    e -> cache.remove(e.getUser().getUniqueId()));
            // Group changes (inheritance, renames) can affect any user.
            luckPerms.getEventBus().subscribe(plugin, GroupDataRecalculateEvent.class, e -> cache.clear());
        } catch (Throwable t) {
            plugin.getLogger().warning("LuckPerms events unavailable - group whitelist results are re-checked every "
                    + (CACHE_TTL_MS / 1000) + "s.");
        }
    }

    /** Forget the cached result for a player (join/quit). */
    public void invalidate(UUID uuid) {
        cache.remove(uuid);
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

        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Cached cached = cache.get(id);
        // Identity, not equals: a reload hands out a new list, which must not match.
        if (cached != null && cached.groups() == whitelistGroups && now - cached.computedAt() < CACHE_TTL_MS) {
            return cached.result();
        }

        User user = luckPerms.getUserManager().getUser(id);
        if (user == null) {
            // Not loaded (yet): not cached, so the next check asks again.
            return false;
        }
        boolean result = computeInWhitelistedGroup(user, whitelistGroups);
        if (player.isOnline()) cache.put(id, new Cached(whitelistGroups, result, now));
        return result;
    }

    private static boolean computeInWhitelistedGroup(User user, List<String> whitelistGroups) {
        // Get primary group
        String primaryGroup = user.getPrimaryGroup();
        if (whitelistGroups.contains(primaryGroup)) {
            return true;
        }

        // Check all groups (including inherited)
        for (net.luckperms.api.model.group.Group g : user.getInheritedGroups(user.getQueryOptions())) {
            for (String group : whitelistGroups) {
                if (g.getName().equalsIgnoreCase(group)) return true;
            }
        }
        return false;
    }
}
