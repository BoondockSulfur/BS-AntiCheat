package dev.boondock.bsanticheat.anticheat;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.integration.GeyserHook;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.lang.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * Everything that touches the optional PacketEvents API, kept in ONE class.
 *
 * <p>This separation is load-bearing, not cosmetic. PacketEvents is a {@code softdepend}:
 * the plugin must keep working without it, with the packet-level checks simply switched
 * off. A runtime guard cannot achieve that on its own — if the main class so much as
 * names a PacketEvents type (a field of that type, or passing {@link PacketChecker} where
 * a {@code PacketListener} is expected), the JVM has to resolve it while *linking* the
 * main class, long before any guard in {@code onEnable} runs. The plugin then fails to
 * load at all with a {@code NoClassDefFoundError}.
 *
 * <p>By confining those references here, the main class links cleanly on a server without
 * PacketEvents. Loading THIS class is what fails there — and that happens inside the
 * caller's {@code try/catch(Throwable)} at runtime, which is exactly the degradation the
 * README promises. {@link #tryEnable} returns null in that case.
 */
public final class PacketIntegration {

    private final Plugin plugin;
    private final PacketChecker packetChecker;
    private final TransactionManager transactionManager;
    private PacketListenerCommon listener;

    private PacketIntegration(Plugin plugin, PacketChecker packetChecker, TransactionManager transactionManager) {
        this.plugin = plugin;
        this.packetChecker = packetChecker;
        this.transactionManager = transactionManager;
    }

    /**
     * Wire up the packet checks and the transaction-latency system.
     *
     * @return the live integration, or {@code null} when PacketEvents is absent or has not
     *         initialised — the caller then runs without packet-level checks.
     */
    public static PacketIntegration tryEnable(Plugin plugin, PluginConfig config, DatabaseManager database,
                                              LanguageManager lang, LuckPermsHook luckPerms, GeyserHook geyser,
                                              MovementAlertManager alerts, ViolationManager violations,
                                              MovementChecker movementChecker, VehicleChecker vehicleChecker,
                                              VelocityChecker velocityChecker, CombatChecker combatChecker) {
        if (PacketEvents.getAPI() == null || !PacketEvents.getAPI().isInitialized()) {
            return null;
        }

        PacketChecker checker = new PacketChecker(plugin, config, database, lang);
        checker.setLuckPerms(luckPerms);
        checker.setGeyser(geyser);
        checker.setAlertManager(alerts);
        checker.setViolationManager(violations);

        // Transaction/latency system — only meaningful with PacketEvents.
        TransactionManager transactions = new TransactionManager(plugin, config, database);
        checker.setTransactionManager(transactions);
        movementChecker.setTransactionManager(transactions);
        vehicleChecker.setTransactionManager(transactions);
        velocityChecker.setTransactionManager(transactions);
        combatChecker.setTransactionManager(transactions);

        PacketIntegration integration = new PacketIntegration(plugin, checker, transactions);

        // The checker is also a Bukkit listener: it needs teleport/join/world-change events
        // to know when a client is catching up and must not be judged.
        Bukkit.getPluginManager().registerEvents(checker, plugin);
        integration.listener = PacketEvents.getAPI().getEventManager()
                .registerListener(checker, PacketListenerPriority.NORMAL);
        transactions.start();
        return integration;
    }

    /** Drop per-player state on disconnect. */
    public void cleanup(UUID playerId) {
        packetChecker.cleanup(playerId);
        transactionManager.cleanup(playerId);
    }

    /** Stop the ping timer and detach from the packet pipeline. */
    public void shutdown() {
        transactionManager.stop();
        if (listener != null) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(listener);
            } catch (Throwable ignored) {
                // PacketEvents already tearing down — nothing left to detach from.
            }
        }
        plugin.getLogger().fine("[PacketEvents] detached.");
    }
}
