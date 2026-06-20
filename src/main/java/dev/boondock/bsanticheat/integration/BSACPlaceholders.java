package dev.boondock.bsanticheat.integration;

import dev.boondock.bsanticheat.BSAntiCheat;
import dev.boondock.bsanticheat.anticheat.ViolationManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.UUID;

/**
 * PlaceholderAPI expansion exposing violation levels.
 * <ul>
 *   <li>{@code %bsanticheat_total%} — sum of all violation levels for the player</li>
 *   <li>{@code %bsanticheat_vl_<CHECK>%} — VL for a check, e.g. %bsanticheat_vl_speed%</li>
 * </ul>
 */
public class BSACPlaceholders extends PlaceholderExpansion {

    private final BSAntiCheat plugin;

    public BSACPlaceholders(BSAntiCheat plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "bsanticheat";
    }

    @Override
    public String getAuthor() {
        return "BoondockSulfur";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // keep registered across PlaceholderAPI reloads
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) return "";
        ViolationManager vm = plugin.violationManager();
        if (vm == null) return "";
        UUID id = player.getUniqueId();

        if (params.equalsIgnoreCase("total")) {
            int sum = vm.getAllViolations(id).values().stream().mapToInt(Integer::intValue).sum();
            return String.valueOf(sum);
        }
        if (params.regionMatches(true, 0, "vl_", 0, 3)) {
            String check = params.substring(3).toUpperCase();
            return String.valueOf(vm.getViolations(id, check));
        }
        return null; // unknown placeholder
    }
}
