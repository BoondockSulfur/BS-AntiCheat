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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

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
    private ViolationManager violationManager;
    // Held as PacketIntegration, never as a PacketEvents type: naming one here would
    // make the JVM resolve it while linking THIS class, so the plugin would fail to
    // load on a server without PacketEvents instead of degrading. Null when absent.
    private PacketIntegration packets;
    private LuckPermsHook luckPerms;
    private GeyserHook geyser;

    @Override
    public void onEnable() {
        // Save default config
        saveDefaultConfig();

        // Config
        configAdapter = new PluginConfig(this);

        // Track server TPS for lag-aware checks
        ServerLoad.start(this);

        // Migrate from old PerformanceAnalyzer if applicable
        new ConfigMigrator(this).migrateFromPerformanceAnalyzer();

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

        // Pistons displace players without any velocity packet, so the movement and
        // inventory checks need to know where one just fired.
        pistonTracker = new PistonTracker();
        movementChecker.setPistonTracker(pistonTracker);
        inventoryChecker.setPistonTracker(pistonTracker);

        // Wire alert managers
        movementChecker.setAlertManager(movementAlertManager);
        xrayDetector.setAlertManager(xrayAlertManager);
        combatChecker.setAlertManager(movementAlertManager);
        worldChecker.setAlertManager(movementAlertManager);
        vehicleChecker.setAlertManager(movementAlertManager);
        inventoryChecker.setAlertManager(movementAlertManager);
        velocityChecker.setAlertManager(movementAlertManager);

        // Set alert preference managers
        movementAlertManager.setPreferenceManager(alertPreferenceManager);
        xrayAlertManager.setPreferenceManager(alertPreferenceManager);

        // Violation level / punishment handling
        violationManager = new ViolationManager(this, configAdapter);
        movementChecker.setViolationManager(violationManager);
        xrayDetector.setViolationManager(violationManager);
        combatChecker.setViolationManager(violationManager);
        worldChecker.setViolationManager(violationManager);
        vehicleChecker.setViolationManager(violationManager);
        inventoryChecker.setViolationManager(violationManager);
        velocityChecker.setViolationManager(violationManager);

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
                    movementChecker, vehicleChecker, velocityChecker, combatChecker);
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
                getLogger().info("PlaceholderAPI hooked - placeholders available (%bsanticheat_total%, %bsanticheat_vl_<check>%).");
            } catch (Throwable t) {
                getLogger().warning("PlaceholderAPI hook failed: " + t.getMessage());
            }
        }

        // Update checker (async, 3s delay)
        dev.boondock.bsanticheat.util.Scheduler.runAsyncLater(this, () -> {
            new UpdateChecker(this).checkForUpdates().thenAccept(result -> {
                if (result.isUpdateAvailable()) {
                    getLogger().warning("A new version is available: " + result.getLatestVersion()
                            + " (current: " + getDescription().getVersion() + "). " + result.getMessage());
                }
            });
        }, Constants.UPDATE_CHECKER_DELAY_TICKS / 20L, java.util.concurrent.TimeUnit.SECONDS);

        getLogger().info("BSAntiCheat v" + getDescription().getVersion() + " enabled!");
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
        if (packets != null) packets.cleanup(playerId);
        if (alertPreferenceManager != null) alertPreferenceManager.cleanup(playerId);
        if (violationManager != null) violationManager.cleanup(playerId);
    }

    public void reloadPlugin() {
        // configAdapter.reload() calls reloadConfig() itself — don't do it twice here.
        configAdapter.reload();
        // Reload the language in place so components holding a reference stay valid
        lang.setLanguage(configAdapter.language());
        if (xrayDetector != null) xrayDetector.reloadConfigCaches();
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
}
