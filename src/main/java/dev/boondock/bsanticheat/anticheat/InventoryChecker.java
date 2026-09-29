package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.integration.GeyserHook;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.lang.LanguageManager;
import dev.boondock.bsanticheat.util.CheckMath;
import dev.boondock.bsanticheat.util.Constants;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
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
    // ChestStealer: consecutive container clicks with inhuman intervals.
    // lastContainerClick: {timeMs, slot}; streakHasJump: the current fast streak contains at
    // least one step a mouse drag cannot produce (see isDragStep).
    private final Map<UUID, long[]> lastContainerClick = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> fastClickStreak = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> streakHasJump = new ConcurrentHashMap<>();
    // Clock for ChestStealer intervals; replaceable so tests can step time.
    java.util.function.LongSupplier clock = System::currentTimeMillis;
    // FastUse: last consume timestamp + consecutive too-fast consumes
    private final Map<UUID, Long> lastConsume = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> consecutiveFastUse = new ConcurrentHashMap<>();
    // BowSpam: last full-charge shot timestamp + consecutive too-fast shots
    private final Map<UUID, Long> lastFullShot = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> consecutiveBowSpam = new ConcurrentHashMap<>();
    // AutoTotem: timestamp of the last totem pop
    private final Map<UUID, Long> lastTotemPop = new ConcurrentHashMap<>();
    // Knockback grace: any server-applied velocity moves a player who has a GUI open
    // without any key input (arrow/trident hits, wind charges, explosions, jump pads).
    private final Map<UUID, Long> recentKnockback = new ConcurrentHashMap<>();
    private static final long KNOCKBACK_GRACE_MS = 2000;
    // Momentum carries a player for a moment after the GUI opens: vanilla friction needs
    // several ticks to bring a sprint (0.28 blocks/tick) below the 0.15 threshold, and the
    // player is not steering during them, and that window alone could reach the violation
    // count with speeds just above the threshold.
    private static final long OPEN_GRACE_MS = 1000;
    private PistonTracker pistons;

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

    public void setPistonTracker(PistonTracker pistons) {
        this.pistons = pistons;
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
        streakHasJump.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerVelocity(org.bukkit.event.player.PlayerVelocityEvent event) {
        recentKnockback.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) return;
        if (!config.inventoryChecksEnabled() || !config.inventoryMoveDetectionEnabled()) return;

        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Long openedAt = containerOpenSince.get(id);
        if (openedAt == null) return;
        if (ServerLoad.isLagging(config, player)) return;
        // Let the momentum the player arrived with die down before judging them.
        if (System.currentTimeMillis() - openedAt < OPEN_GRACE_MS) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || !from.getWorld().equals(to.getWorld())) return;

        // Pushes (entities, water, pistons) can move a player with an open GUI —
        // exempt liquid/vehicle/glide states and require sustained walking speed.
        // isFlying covers survival flight granted by another plugin (EssentialsX /fly):
        // those players drift with a GUI open and are not in creative gamemode, so the
        // gamemode exemption further down would never catch them.
        if (player.isInsideVehicle() || player.isGliding() || player.isInWater()
                || player.isFlying() || player.getAllowFlight()) {
            consecutiveInvMove.remove(id);
            return;
        }
        // Airborne movement needs no key input either: a player who walks off a ledge or
        // jumps before opening the container keeps their horizontal momentum the whole way
        // down, and the vanilla client cannot steer it with a GUI open any more than it can
        // start it. Airborne is decided from the blocks under the player's bounding box, not
        // the client's on-ground flag, which a cheat can simply report as false. Such samples
        // are skipped without resetting the count, so hopping does not wipe the evidence
        // gathered on the ground in between.
        if (!hasSupportBelow(to)) return;

        Long kb = recentKnockback.get(id);
        if (kb != null && System.currentTimeMillis() - kb < KNOCKBACK_GRACE_MS) {
            consecutiveInvMove.remove(id);
            return;
        }

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < config.inventoryMoveMinSpeed()) {
            consecutiveInvMove.remove(id);
            return;
        }

        // Momentum and shoves need no key input: opening a chest while sprinting on ice
        // keeps the player sliding above the threshold for many ticks, and colliding
        // entities (mob crowds, villager halls) push a player with an open GUI around.
        // Checked only after the speed gate — the entity query is the expensive part.
        // A piston shoves a player without any velocity packet, so the knockback grace above
        // never sees it — piston doors and elevators move players with GUIs open routinely.
        if (isOnIce(player) || isBeingPushed(player)
                || (pistons != null && pistons.wasPushedRecently(to))) {
            consecutiveInvMove.remove(id);
            return;
        }

        if (Exemptions.isExempt(player, config, luckPerms, geyser)) return;

        int c = consecutiveInvMove.merge(id, 1, Integer::sum);
        if (c >= config.inventoryMoveViolations()) {
            handleViolation(player, "INVENTORYMOVE", lang.format("alert.inventorymove", c), horizontal, to);
            consecutiveInvMove.put(id, 0);
        }
    }

    /**
     * Server-side ground test: a supporting block just under any corner of the player's
     * footprint. Players standing on entities or fence tops read as airborne and are
     * skipped, which only costs detection, never a false flag.
     */
    private static boolean hasSupportBelow(Location to) {
        if (to.getWorld() == null) return true;
        double y = to.getY() - GROUND_PROBE;
        int by = (int) Math.floor(y);
        for (double ox : FOOT_OFFSETS) {
            for (double oz : FOOT_OFFSETS) {
                org.bukkit.block.Block b = to.getWorld().getBlockAt(
                        (int) Math.floor(to.getX() + ox), by, (int) Math.floor(to.getZ() + oz));
                if (isSupport(b)) return true;
            }
        }
        return false;
    }

    private static final double GROUND_PROBE = 0.05;
    // Corners of a player's 0.6-wide footprint.
    private static final double[] FOOT_OFFSETS = {-0.3, 0.3};

    private static boolean isSupport(org.bukkit.block.Block block) {
        Material m = block.getType();
        if (m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR) return false;
        if (m.isSolid()) return true;
        if (m == Material.WATER || m == Material.LAVA || m == Material.POWDER_SNOW
                || m == Material.SCAFFOLDING || m == Material.COBWEB) return true;
        return !block.isPassable();
    }

    /** True when ice below could carry sliding momentum (shared scan). */
    private boolean isOnIce(Player player) {
        return CheckMath.iceMultiplierBelow(player.getLocation(),
                Constants.ICE_SPEED_MULTIPLIER, Constants.BLUE_ICE_SPEED_MULTIPLIER) > 1.0;
    }

    /**
     * True when an entity close enough to shove the player is nearby. Only collidable
     * living entities and vehicles count — armor stands, markers and dropped items
     * cannot push and must not disable the check.
     */
    private boolean isBeingPushed(Player player) {
        for (Entity e : player.getNearbyEntities(1.0, 0.5, 1.0)) {
            if (e instanceof LivingEntity le && le.isCollidable()) return true;
            if (e instanceof Vehicle) return true;
        }
        return false;
    }

    // ==================== ChestStealer + AutoTotem (clicks) ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!config.inventoryChecksEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        // Queued clicks arriving in one tick after a lag spike look like 0ms intervals.
        if (ServerLoad.isLagging(config, player)) return;
        UUID id = player.getUniqueId();

        // --- AutoTotem: a totem lands in the offhand via inventory click within an
        // inhuman reaction time after a pop. (The F-key swap of a pre-held totem is a
        // legitimate technique and goes through PlayerSwapHandItemsEvent, not here.)
        if (config.autoTotemDetectionEnabled()) {
            Long pop = lastTotemPop.get(id);
            if (pop != null) {
                long sincePop = System.currentTimeMillis() - pop;
                if (sincePop <= config.autoTotemMaxReactionMs() && isTotemToOffhand(event)) {
                    lastTotemPop.remove(id);
                    if (!Exemptions.isExempt(player, config, luckPerms, geyser)) {
                        handleViolation(player, "AUTOTOTEM",
                                lang.format("alert.autototem", sincePop), sincePop, player.getLocation());
                    }
                } else if (sincePop > config.autoTotemMaxReactionMs()) {
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

        long now = clock.getAsLong();
        int slot = event.getSlot();
        long[] prev = lastContainerClick.put(id, new long[]{now, slot});
        if (prev == null) return;
        long interval = now - prev[0];
        boolean dragStep = isDragStep((int) prev[1], slot, gridWidth(top));

        // Debug output records interval, slot and click type, so an alert can be told apart
        // from network-bundled arrivals afterwards. Logged before the floor test below, so
        // the ignored pairs are visible too.
        if (config.debugMode()) {
            plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                    "[CHEST-DEBUG] %s interval=%dms click=%s slot=%d drag=%b streak=%d (floor %d, window %d, needs %d)",
                    player.getName(), interval, click.name(), event.getSlot(), dragStep,
                    fastClickStreak.getOrDefault(id, 0),
                    config.chestStealerMinIntervalMs(), config.chestStealerMaxIntervalMs(),
                    config.chestStealerMinClicks()));
        }
        // Below the physical floor the two clicks were delivered in one network bundle,
        // not actually made that fast — neither count nor reset, just ignore the pair.
        // A real ChestStealer clicks at 20-40ms, comfortably above the floor.
        //
        // The baseline above advances even for an ignored pair, and must: leaving it in place
        // would measure the next click that clears the floor against a much older one, so a
        // single bundle of N arrivals turns into a fan of intervals and the ones landing in
        // the detection window count as clicks that were never made. That trades a bypass for
        // a false-positive source, which is the wrong direction for this check.
        //
        // The cost is a real blind spot: a client clicking faster than the floor produces
        // nothing but sub-floor intervals and never starts a streak. Closing it needs the
        // bundling analysis the AutoClicker check does on arrival CADENCE (see
        // PacketChecker#isHeldButton), not a change to this floor.
        if (interval < config.chestStealerMinIntervalMs()) return;
        if (interval <= config.chestStealerMaxIntervalMs()) {
            int streak = fastClickStreak.merge(id, 1, Integer::sum);
            if (!dragStep) streakHasJump.put(id, Boolean.TRUE);
            // A mouse drag (Mouse Tweaks shift-/LMB-drag) clicks slot after slot along a
            // continuous path, at mouse speed — as fast as a stealer. What a drag cannot do
            // is jump: a stealer walking the slots by index wraps from the end of one row to
            // the start of the next. A streak made only of drag steps is not judged.
            if (streak >= config.chestStealerMinClicks() && streakHasJump.getOrDefault(id, false)
                    && !Exemptions.isExempt(player, config, luckPerms, geyser)) {
                handleViolation(player, "CHESTSTEALER",
                        lang.format("alert.cheststealer", streak + 1, interval), streak, player.getLocation());
                fastClickStreak.put(id, 0);
                streakHasJump.remove(id);
            }
        } else {
            fastClickStreak.remove(id);
            streakHasJump.remove(id);
        }
    }

    /**
     * Whether a mouse dragged across the slot grid can move from one slot to the next: the
     * neighbouring slot, or one further when a fast movement skipped a slot between two
     * frames. Package-private and free of server state for testing.
     */
    static boolean isDragStep(int fromSlot, int toSlot, int width) {
        if (width <= 0) return true;
        int dRow = Math.abs(fromSlot / width - toSlot / width);
        int dCol = Math.abs(fromSlot % width - toSlot % width);
        return Math.max(dRow, dCol) <= 2;
    }

    /** Slots per row of a container screen. */
    private static int gridWidth(InventoryType type) {
        return switch (type) {
            case DISPENSER, DROPPER, CRAFTER -> 3;
            case HOPPER -> 5;
            default -> 9;
        };
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
        Player player = event.getPlayer();
        if (ServerLoad.isLagging(config, player)) return;
        UUID id = player.getUniqueId();

        long now = System.currentTimeMillis();
        Long last = lastConsume.put(id, now);
        if (last == null) return;
        long interval = now - last;

        // The configured floor assumes vanilla eat times (dried kelp ~800ms, everything else
        // 1.6s+). Item plugins ship consumables that are legitimately quicker, and judging
        // those against a vanilla floor flags the player for using their own server's items.
        // So take whichever is lower: the configured floor, or what this item actually needs.
        long floor = config.fastUseMinIntervalMs();
        long itemMs = itemUseMs(event.getItem(), player);
        if (itemMs > 0) floor = Math.min(floor, itemMs);

        if (interval < floor) {
            int c = consecutiveFastUse.merge(id, 1, Integer::sum);
            if (c >= config.fastUseViolations() && !Exemptions.isExempt(player, config, luckPerms, geyser)) {
                handleViolation(player, "FASTUSE",
                        lang.format("alert.fastuse", interval, floor),
                        interval, player.getLocation());
                consecutiveFastUse.put(id, 0);
            }
        } else {
            consecutiveFastUse.remove(id);
        }
    }

    /**
     * How long this item genuinely takes to consume, in ms — read from the item itself, so a
     * custom consumable is judged against its own use time rather than a vanilla assumption.
     * Returns 0 when the server cannot say, in which case the configured floor stands.
     */
    private long itemUseMs(org.bukkit.inventory.ItemStack item, Player player) {
        if (item == null) return 0L;
        try {
            int ticks = item.getMaxItemUseDuration(player);
            return ticks > 0 ? ticks * 50L : 0L;
        } catch (Throwable t) {
            return 0L; // API not present on this server build — fall back to the config value
        }
    }

    // ==================== BowSpam ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (!config.inventoryChecksEnabled() || !config.bowSpamDetectionEnabled()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (ServerLoad.isLagging(config, player)) return;
        // Crossbows are pre-charged and legitimately fire instantly — bows only
        if (event.getBow() == null || event.getBow().getType() != Material.BOW) return;
        // Only full-charge shots: a full draw takes 1s, so full shots can't come faster
        if (event.getForce() < config.bowSpamMinForce()) return;

        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastFullShot.put(id, now);
        if (last == null) return;
        long interval = now - last;

        if (interval < config.bowSpamMinIntervalMs()) {
            int c = consecutiveBowSpam.merge(id, 1, Integer::sum);
            if (c >= config.bowSpamViolations() && !Exemptions.isExempt(player, config, luckPerms, geyser)) {
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
            database.logAsync(player.getUniqueId(), "anticheat_" + type.toLowerCase(), value,
                    player.getName() + ": " + details + " @ " + CheckMath.formatLocation(location));
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
        streakHasJump.remove(playerId);
        lastConsume.remove(playerId);
        consecutiveFastUse.remove(playerId);
        lastFullShot.remove(playerId);
        consecutiveBowSpam.remove(playerId);
        lastTotemPop.remove(playerId);
        recentKnockback.remove(playerId);
    }
}
