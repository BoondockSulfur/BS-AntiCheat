package dev.boondock.bsanticheat;

import dev.boondock.bsanticheat.alerts.AlertPreferenceManager;
import dev.boondock.bsanticheat.anticheat.*;
import dev.boondock.bsanticheat.commands.CommandRegistry;
import dev.boondock.bsanticheat.config.ConfigMigrator;
import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.anticheat.Exemptions;
import dev.boondock.bsanticheat.integration.BSACPlaceholders;
import dev.boondock.bsanticheat.integration.GeyserHook;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.integration.ViaVersionHook;
import dev.boondock.bsanticheat.lang.LanguageManager;
import dev.boondock.bsanticheat.util.Constants;
import dev.boondock.bsanticheat.util.UpdateChecker;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.UUID;

/**
 * BSAntiCheat - AntiCheat plugin for Paper 1.21.x
 * Movement and XRay detection with lag compensation.
 */
public class BSAntiCheat extends JavaPlugin implements Listener {

    private PluginConfig configAdapter;
    private LanguageManager lang;
    private DatabaseManager database;
    private AlertPreferenceManager alertPreferenceManager;
    private XRayAlertManager xrayAlertManager;
    private MovementAlertManager movementAlertManager;
    private MovementChecker movementChecker;
    private XRayDetector xrayDetector;
    private CombatChecker combatChecker;
    private WorldChecker worldChecker;
    private VehicleChecker vehicleChecker;
    private InventoryChecker inventoryChecker;
    private VelocityChecker velocityChecker;
    private PistonTracker pistonTracker;
    private CrystalChecker crystalChecker;
    // Spear jabs with Lunge, recorded from the packet layer for the movement checks. Created
    // here (plain Java, no PacketEvents types) so it exists with or without PacketEvents.
    private LungeTracker lungeTracker;
    private ViolationManager violationManager;
    // Held as PacketIntegration, never as a PacketEvents type: naming one here would
    // make the JVM resolve it while linking THIS class, so the plugin would fail to
    // load on a server without PacketEvents instead of degrading. Null when absent.
    private PacketIntegration packets;
    private LuckPermsHook luckPerms;
    private GeyserHook geyser;
    /** Newer version found by the update check, or null. Written async, read on join. */
    private volatile String availableVersion;

    @Override
    public void onEnable() {
        // Save default config. Whether it had to be created decides whether this is a
        // first run, which is the only time a legacy PerformanceAnalyzer config is imported.
        boolean freshInstall = !new File(getDataFolder(), "config.yml").exists();
        saveDefaultConfig();

        // Migrate from the legacy PerformanceAnalyzer before the config is read, so the
        // imported values are the ones every cache is built from.
        new ConfigMigrator(this).migrateFromPerformanceAnalyzer(freshInstall);

        // Config
        configAdapter = new PluginConfig(this);

        // Track server TPS for lag-aware checks
        ServerLoad.start(this, configAdapter);

        // Language
        lang = new LanguageManager(this, configAdapter.language());

        // Database
        database = new DatabaseManager(this, configAdapter);
        database.init();

        // Alert preferences (silent mode)
        alertPreferenceManager = new AlertPreferenceManager(this, configAdapter);

        // Core anticheat components
        xrayAlertManager = new XRayAlertManager(this, configAdapter, lang);
        movementAlertManager = new MovementAlertManager(this, configAdapter, lang);

        // Detectors
        movementChecker = new MovementChecker(this, configAdapter, database, lang);
        xrayDetector = new XRayDetector(this, configAdapter, database, lang);
        combatChecker = new CombatChecker(this, configAdapter, database, lang);
        worldChecker = new WorldChecker(this, configAdapter, database, lang);
        vehicleChecker = new VehicleChecker(this, configAdapter, database, lang);
        inventoryChecker = new InventoryChecker(this, configAdapter, database, lang);
        velocityChecker = new VelocityChecker(this, configAdapter, database, lang);
        crystalChecker = new CrystalChecker(this, configAdapter, database, lang);
        lungeTracker = new LungeTracker();

        // Pistons displace players without any velocity packet, so the movement and
        // inventory checks need to know where one just fired.
        pistonTracker = new PistonTracker();
        movementChecker.setPistonTracker(pistonTracker);

        // Lunges excuse speed in the movement checks and skip the mace check; the fall
        // measurement is shared so the mace check compares against the same descent.
        movementChecker.setLungeTracker(lungeTracker);
        combatChecker.setLungeTracker(lungeTracker);
        combatChecker.setFallTracker(movementChecker.fallTracker());
        inventoryChecker.setPistonTracker(pistonTracker);

        // Wire alert managers
        movementChecker.setAlertManager(movementAlertManager);
        xrayDetector.setAlertManager(xrayAlertManager);
        combatChecker.setAlertManager(movementAlertManager);
        worldChecker.setAlertManager(movementAlertManager);
        vehicleChecker.setAlertManager(movementAlertManager);
        inventoryChecker.setAlertManager(movementAlertManager);
        velocityChecker.setAlertManager(movementAlertManager);
        crystalChecker.setAlertManager(movementAlertManager);

        // Set alert preference managers
        movementAlertManager.setPreferenceManager(alertPreferenceManager);
        xrayAlertManager.setPreferenceManager(alertPreferenceManager);

        // Violation level / punishment handling
        violationManager = new ViolationManager(this, configAdapter);
        violationManager.setLanguage(lang);
        movementChecker.setViolationManager(violationManager);
        xrayDetector.setViolationManager(violationManager);
        combatChecker.setViolationManager(violationManager);
        worldChecker.setViolationManager(violationManager);
        vehicleChecker.setViolationManager(violationManager);
        inventoryChecker.setViolationManager(violationManager);
        velocityChecker.setViolationManager(violationManager);
        crystalChecker.setViolationManager(violationManager);

        // LuckPerms integration (optional)
        luckPerms = LuckPermsHook.tryHook(this);
        if (luckPerms != null) {
            movementChecker.setLuckPerms(luckPerms);
            xrayDetector.setLuckPerms(luckPerms);
            combatChecker.setLuckPerms(luckPerms);
            worldChecker.setLuckPerms(luckPerms);
            vehicleChecker.setLuckPerms(luckPerms);
            inventoryChecker.setLuckPerms(luckPerms);
            velocityChecker.setLuckPerms(luckPerms);
            crystalChecker.setLuckPerms(luckPerms);
        }

        // Geyser/Floodgate: exempt Bedrock players from checks (they use different physics)
        geyser = GeyserHook.tryHook(this);
        movementChecker.setGeyser(geyser);
        xrayDetector.setGeyser(geyser);
        combatChecker.setGeyser(geyser);
        worldChecker.setGeyser(geyser);
        vehicleChecker.setGeyser(geyser);
        inventoryChecker.setGeyser(geyser);
        velocityChecker.setGeyser(geyser);
        crystalChecker.setGeyser(geyser);

        // ViaVersion: optional legacy-client exemption (shared statically via Exemptions).
        Exemptions.setViaVersion(ViaVersionHook.tryHook(this));

        // Register event listeners
        Bukkit.getPluginManager().registerEvents(movementChecker, this);
        Bukkit.getPluginManager().registerEvents(xrayDetector, this);
        Bukkit.getPluginManager().registerEvents(combatChecker, this);
        Bukkit.getPluginManager().registerEvents(worldChecker, this);
        Bukkit.getPluginManager().registerEvents(vehicleChecker, this);
        Bukkit.getPluginManager().registerEvents(inventoryChecker, this);
        Bukkit.getPluginManager().registerEvents(velocityChecker, this);
        Bukkit.getPluginManager().registerEvents(crystalChecker, this);
        Bukkit.getPluginManager().registerEvents(pistonTracker, this);
        Bukkit.getPluginManager().registerEvents(this, this);

        // Packet-level checks — use the installed PacketEvents plugin (optional).
        // We do NOT bundle/init PacketEvents ourselves to avoid conflicting with a
        // standalone PacketEvents/ProtocolLib already hooking the netty pipeline.
        // The call is what loads PacketIntegration, and with it the PacketEvents classes.
        // On a server without PacketEvents that throws NoClassDefFoundError right here,
        // inside this catch — which is precisely the intended degradation.
        try {
            packets = PacketIntegration.tryEnable(this, configAdapter, database, lang, luckPerms, geyser,
                    movementAlertManager, violationManager,
                    movementChecker, vehicleChecker, velocityChecker, combatChecker,
                    crystalChecker, lungeTracker);
            if (packets != null) {
                getLogger().info("[PacketEvents] hooked - packet checks + transaction latency active.");
            } else {
                getLogger().info("[PacketEvents] not found - packet checks (AutoClicker, BadPackets, Timer) "
                        + "disabled. Install the PacketEvents plugin to enable them.");
            }
        } catch (Throwable t) {
            packets = null;
            getLogger().info("[PacketEvents] not available - packet-based checks disabled ("
                    + t.getClass().getSimpleName() + "). The rest of the anticheat runs normally.");
        }

        // Commands
        CommandRegistry commandRegistry = new CommandRegistry(this);
        commandRegistry.registerAll();

        // bStats metrics (optional, never fatal)
        try {
            new Metrics(this, Constants.BSTATS_PLUGIN_ID);
        } catch (Throwable t) {
            getLogger().fine("[bStats] Could not start metrics: " + t.getMessage());
        }

        // PlaceholderAPI integration (optional)
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new BSACPlaceholders(this).register();
                // Both directions: the expansion PUBLISHES our violation levels, and this
                // flag lets our own messages and punishment commands RESOLVE other plugins'
                // placeholders. Only the first half existed.
                dev.boondock.bsanticheat.util.Messages.setPlaceholderApiPresent(true);
                getLogger().info("PlaceholderAPI hooked - placeholders available (%bsanticheat_total%, %bsanticheat_vl_<check>%),"
                        + " and placeholders in alerts and punishment commands are resolved.");
            } catch (Throwable t) {
                getLogger().warning("PlaceholderAPI hook failed: " + t.getMessage());
            }
        }

        // Update checker (async, 3s delay)
        dev.boondock.bsanticheat.util.Scheduler.runAsyncLater(this, this::checkForUpdates,
                Constants.UPDATE_CHECKER_DELAY_TICKS / 20L, java.util.concurrent.TimeUnit.SECONDS);

        getLogger().info("BSAntiCheat v" + getDescription().getVersion() + " enabled!");
    }

    private void checkForUpdates() {
        new UpdateChecker(this).checkForUpdates().thenAccept(result -> {
            if (!result.isUpdateAvailable()) return;
            availableVersion = result.getLatestVersion();
            StringBuilder line = new StringBuilder("Update available: ")
                    .append(getDescription().getVersion()).append(" -> ").append(result.getLatestVersion())
                    .append("  |  Modrinth: ").append(Constants.URL_MODRINTH);
            if (hasCurseForgePage()) line.append("  |  CurseForge: ").append(Constants.URL_CURSEFORGE);
            getLogger().warning(line.toString());
        }).exceptionally(ex -> null);
    }

    private static boolean hasCurseForgePage() {
        return Constants.URL_CURSEFORGE != null && !Constants.URL_CURSEFORGE.isEmpty();
    }

    /**
     * Tells operators about an update once, when they join. The console line is easy to
     * miss among the startup output, and the download pages are only clickable in chat.
     * Operators only: nobody else can install it.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        // A previous session's cached answers must not carry over.
        if (luckPerms != null) luckPerms.invalidate(playerId);
        if (geyser != null) geyser.invalidate(playerId);

        String latest = availableVersion;
        if (latest == null || !event.getPlayer().isOp()) return;

        LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();
        Component message = legacy.deserialize(lang.get("update.available",
                        "%current%", getDescription().getVersion(),
                        "%latest%", latest))
                .append(Component.space())
                .append(downloadLink("update.link_modrinth", Constants.URL_MODRINTH, legacy));
        if (hasCurseForgePage()) {
            message = message.append(Component.space())
                    .append(downloadLink("update.link_curseforge", Constants.URL_CURSEFORGE, legacy));
        }
        event.getPlayer().sendMessage(message);
    }

    private Component downloadLink(String labelKey, String url, LegacyComponentSerializer legacy) {
        return legacy.deserialize(lang.get(labelKey))
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(legacy.deserialize(lang.get("update.link_hover", "%url%", url))));
    }

    @Override
    public void onDisable() {
        if (packets != null) {
            try { packets.shutdown(); } catch (Throwable ignored) {}
        }
        if (database != null) database.shutdown();
        if (configAdapter != null) configAdapter.saveSyncOnShutdown();
        getLogger().info("BSAntiCheat disabled.");
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (movementChecker != null) movementChecker.cleanup(playerId);
        if (xrayDetector != null) xrayDetector.cleanup(playerId);
        if (worldChecker != null) worldChecker.cleanup(playerId);
        if (combatChecker != null) combatChecker.cleanup(playerId);
        if (vehicleChecker != null) vehicleChecker.cleanup(playerId);
        if (inventoryChecker != null) inventoryChecker.cleanup(playerId);
        if (velocityChecker != null) velocityChecker.cleanup(playerId);
        if (crystalChecker != null) crystalChecker.cleanup(playerId);
        if (lungeTracker != null) lungeTracker.cleanup(playerId);
        if (packets != null) packets.cleanup(playerId);
        if (alertPreferenceManager != null) alertPreferenceManager.cleanup(playerId);
        if (violationManager != null) violationManager.cleanup(playerId);
        if (luckPerms != null) luckPerms.invalidate(playerId);
        if (geyser != null) geyser.invalidate(playerId);
    }

    public void reloadPlugin() {
        // configAdapter.reload() calls reloadConfig() itself — don't do it twice here.
        configAdapter.reload();
        // Reload the language in place so components holding a reference stay valid
        lang.setLanguage(configAdapter.language());
        if (xrayDetector != null) xrayDetector.reloadConfigCaches();
        // Reschedules the transaction ping task: its period is fixed when the task is
        // created, so reloading the config alone would keep the old interval.
        if (packets != null) packets.reload();
        getLogger().info("BSAntiCheat reloaded.");
    }

    // Getters for commands/GUI (match method names used by copied commands)
    public PluginConfig configAdapter() { return configAdapter; }
    public LanguageManager lang() { return lang; }
    public DatabaseManager database() { return database; }
    public XRayDetector xrayDetector() { return xrayDetector; }
    public ViolationManager violationManager() { return violationManager; }
    public XRayAlertManager xrayAlertManager() { return xrayAlertManager; }
    public MovementAlertManager movementAlertManager() { return movementAlertManager; }
    public AlertPreferenceManager alertPreferenceManager() { return alertPreferenceManager; }
    /** Spear-lunge record for the movement checks; empty while PacketEvents is absent. */
    public LungeTracker lungeTracker() { return lungeTracker; }
}
