package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.integration.GeyserHook;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.lang.LanguageManager;
import dev.boondock.bsanticheat.util.Constants;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inventory- and item-timing checks:
 * <ul>
 *   <li>INVENTORYMOVE — walking at full speed with a container GUI open. The vanilla
 *       client cannot process movement keys while a GUI is open; cheats can.</li>
 *   <li>CHESTSTEALER — emptying a container with inhumanly fast click intervals.</li>
 *   <li>FASTUSE — consuming items faster than the vanilla use time allows.</li>
 *   <li>BOWSPAM — repeated full-charge bow shots faster than the 1s draw time.</li>
 *   <li>AUTOTOTEM — refilling the offhand totem via inventory click within an
 *       inhuman reaction time after a totem pop.</li>
 * </ul>
 *
 * All checks are event-based heuristics with deliberately generous thresholds;
 * thresholds live in {@link Constants}.
 */
public class InventoryChecker implements Listener {

    private final Plugin plugin;
    private final PluginConfig config;
    private final DatabaseManager database;
    private final LanguageManager lang;
    private LuckPermsHook luckPerms;
    private GeyserHook geyser;
    private MovementAlertManager alertManager;
    private ViolationManager violationManager;

    // InventoryMove: players with an open container GUI + consecutive fast moves
    private final Map<UUID, Long> containerOpenSince = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> consecutiveInvMove = new ConcurrentHashMap<>();
    // ChestStealer: consecutive container clicks with inhuman intervals
    private final Map<UUID, Long> lastContainerClick = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> fastClickStreak = new ConcurrentHashMap<>();
    // FastUse: last consume timestamp + consecutive too-fast consumes
    private final Map<UUID, Long> lastConsume = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> consecutiveFastUse = new ConcurrentHashMap<>();
    // BowSpam: last full-charge shot timestamp + consecutive too-fast shots
    private final Map<UUID, Long> lastFullShot = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> consecutiveBowSpam = new ConcurrentHashMap<>();
    // AutoTotem: timestamp of the last totem pop
    private final Map<UUID, Long> lastTotemPop = new ConcurrentHashMap<>();

    public InventoryChecker(Plugin plugin, PluginConfig config, DatabaseManager database, LanguageManager lang) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.lang = lang;
    }

    public void setLuckPerms(LuckPermsHook luckPerms) {
        this.luckPerms = luckPerms;
    }

    public void setGeyser(GeyserHook geyser) {
        this.geyser = geyser;
    }

    public void setAlertManager(MovementAlertManager alertManager) {
        this.alertManager = alertManager;
    }

    public void setViolationManager(ViolationManager violationManager) {
        this.violationManager = violationManager;
    }

    // ==================== InventoryMove ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        // Only real container GUIs — CRAFTING is the player's own inventory screen,
        // which the server cannot observe being open/closed reliably.
        InventoryType type = event.getInventory().getType();
        if (type == InventoryType.CRAFTING || type == InventoryType.PLAYER) return;
        containerOpenSince.put(player.getUniqueId(), System.currentTimeMillis());
        consecutiveInvMove.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        containerOpenSince.remove(id);
        consecutiveInvMove.remove(id);
        lastContainerClick.remove(id);
        fastClickStreak.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) return;
        if (!config.inventoryChecksEnabled() || !config.inventoryMoveDetectionEnabled()) return;

        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (!containerOpenSince.containsKey(id)) return;
        if (ServerLoad.isLagging(config)) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || !from.getWorld().equals(to.getWorld())) return;

        // Pushes (entities, water, pistons) can move a player with an open GUI —
        // exempt liquid/vehicle/glide states and require sustained walking speed.
        if (player.isInsideVehicle() || player.isGliding() || player.isInWater()) {
            consecutiveInvMove.remove(id);
            return;
        }

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < Constants.INVENTORYMOVE_MIN_SPEED) {
            consecutiveInvMove.remove(id);
            return;
        }

        if (Exemptions.isExempt(player, config, luckPerms, geyser)) return;

        int c = consecutiveInvMove.merge(id, 1, Integer::sum);
        if (c >= Constants.INVENTORYMOVE_VIOLATIONS) {
            handleViolation(player, "INVENTORYMOVE", lang.format("alert.inventorymove", c), horizontal, to);
            consecutiveInvMove.put(id, 0);
        }
    }

    // ==================== ChestStealer + AutoTotem (clicks) ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!config.inventoryChecksEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        UUID id = player.getUniqueId();

        // --- AutoTotem: a totem lands in the offhand via inventory click within an
        // inhuman reaction time after a pop. (The F-key swap of a pre-held totem is a
        // legitimate technique and goes through PlayerSwapHandItemsEvent, not here.)
        if (config.autoTotemDetectionEnabled()) {
            Long pop = lastTotemPop.get(id);
            if (pop != null) {
                long sincePop = System.currentTimeMillis() - pop;
                if (sincePop <= Constants.AUTOTOTEM_MAX_REACTION_MS && isTotemToOffhand(event)) {
                    lastTotemPop.remove(id);
                    if (!Exemptions.isExempt(player, config, luckPerms, geyser)) {
                        handleViolation(player, "AUTOTOTEM",
                                lang.format("alert.autototem", sincePop), sincePop, player.getLocation());
                    }
                } else if (sincePop > Constants.AUTOTOTEM_MAX_REACTION_MS) {
                    lastTotemPop.remove(id); // window expired
                }
            }
        }

        // --- ChestStealer: only manual pick-up clicks in the TOP (container) inventory
        if (!config.chestStealerDetectionEnabled()) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() instanceof PlayerInventory) return;
        InventoryType top = event.getView().getTopInventory().getType();
        if (top == InventoryType.CRAFTING || top == InventoryType.PLAYER) return;
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT && click != ClickType.SHIFT_LEFT) return;
        if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) return;

        long now = System.currentTimeMillis();
        Long last = lastContainerClick.put(id, now);
        if (last == null) return;
        long interval = now - last;
        if (interval <= Constants.CHESTSTEALER_MAX_INTERVAL_MS) {
            int streak = fastClickStreak.merge(id, 1, Integer::sum);
            if (streak >= Constants.CHESTSTEALER_MIN_CLICKS && !Exemptions.isExempt(player, config, luckPerms, geyser)) {
                handleViolation(player, "CHESTSTEALER",
                        lang.format("alert.cheststealer", streak + 1, interval), streak, player.getLocation());
                fastClickStreak.put(id, 0);
            }
        } else {
            fastClickStreak.remove(id);
        }
    }

    /** True when this click puts a totem into the offhand slot. */
    private boolean isTotemToOffhand(InventoryClickEvent event) {
        // F-key swap onto a hovered totem (moves it to the offhand)
        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            return event.getCurrentItem() != null
                    && event.getCurrentItem().getType() == Material.TOTEM_OF_UNDYING;
        }
        // Placing a carried totem into the offhand slot (slot 40 of the player inventory)
        return event.getClickedInventory() instanceof PlayerInventory
                && event.getSlot() == 40
                && event.getCursor() != null
                && event.getCursor().getType() == Material.TOTEM_OF_UNDYING;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityResurrect(EntityResurrectEvent event) {
        if (!config.inventoryChecksEnabled() || !config.autoTotemDetectionEnabled()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        lastTotemPop.put(player.getUniqueId(), System.currentTimeMillis());
    }

    // ==================== FastUse ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemConsume(PlayerItemConsumeEvent event) {
        if (!config.inventoryChecksEnabled() || !config.fastUseDetectionEnabled()) return;
        if (ServerLoad.isLagging(config)) return;
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();

        long now = System.currentTimeMillis();
        Long last = lastConsume.put(id, now);
        if (last == null) return;
        long interval = now - last;

        // Fastest legit consumable (dried kelp) takes ~800ms; everything else 1.6s+.
        if (interval < Constants.FASTUSE_MIN_INTERVAL_MS) {
            int c = consecutiveFastUse.merge(id, 1, Integer::sum);
            if (c >= Constants.FASTUSE_VIOLATIONS && !Exemptions.isExempt(player, config, luckPerms, geyser)) {
                handleViolation(player, "FASTUSE",
                        lang.format("alert.fastuse", interval, Constants.FASTUSE_MIN_INTERVAL_MS),
                        interval, player.getLocation());
                consecutiveFastUse.put(id, 0);
            }
        } else {
            consecutiveFastUse.remove(id);
        }
    }

    // ==================== BowSpam ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (!config.inventoryChecksEnabled() || !config.bowSpamDetectionEnabled()) return;
        if (ServerLoad.isLagging(config)) return;
        if (!(event.getEntity() instanceof Player player)) return;
        // Crossbows are pre-charged and legitimately fire instantly — bows only
        if (event.getBow() == null || event.getBow().getType() != Material.BOW) return;
        // Only full-charge shots: a full draw takes 1s, so full shots can't come faster
        if (event.getForce() < Constants.BOWSPAM_MIN_FORCE) return;

        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastFullShot.put(id, now);
        if (last == null) return;
        long interval = now - last;

        if (interval < Constants.BOWSPAM_MIN_INTERVAL_MS) {
            int c = consecutiveBowSpam.merge(id, 1, Integer::sum);
            if (c >= Constants.BOWSPAM_VIOLATIONS && !Exemptions.isExempt(player, config, luckPerms, geyser)) {
                handleViolation(player, "BOWSPAM",
                        lang.format("alert.bowspam", interval), interval, player.getLocation());
                consecutiveBowSpam.put(id, 0);
            }
        } else {
            consecutiveBowSpam.remove(id);
        }
    }

    private void handleViolation(Player player, String type, String details, double value, Location location) {
        if (database != null) {
            database.logAsync("anticheat_" + type.toLowerCase(), value, player.getName() + ": " + details);
        }
        if (alertManager != null) {
            alertManager.addAlert(player, type, details, value, location);
        } else if (config.debugMode()) {
            plugin.getLogger().warning("[AntiCheat] " + player.getName() + " " + type + " - " + details);
        }
        if (violationManager != null) {
            violationManager.flag(player, type);
        }
    }

    public void cleanup(UUID playerId) {
        containerOpenSince.remove(playerId);
        consecutiveInvMove.remove(playerId);
        lastContainerClick.remove(playerId);
        fastClickStreak.remove(playerId);
        lastConsume.remove(playerId);
        consecutiveFastUse.remove(playerId);
        lastFullShot.remove(playerId);
        consecutiveBowSpam.remove(playerId);
        lastTotemPop.remove(playerId);
    }
}
