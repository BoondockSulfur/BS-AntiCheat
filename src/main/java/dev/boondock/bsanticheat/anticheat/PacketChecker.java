package dev.boondock.bsanticheat.anticheat;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.lang.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Packet-level checks (via PacketEvents). Currently: AutoClicker — clicks per second
 * derived from arm-swing (ANIMATION) packets, which a client cannot hide from the server
 * the way it can with Bukkit events.
 *
 * <p>Runs on Netty threads, so all Bukkit access (alerts) is hopped back to the main
 * thread before use.
 */
public class PacketChecker implements PacketListener {

    private static final long WINDOW_MS = 1000L;

    private final Plugin plugin;
    private final PluginConfig config;
    private final DatabaseManager database;
    private final LanguageManager lang;
    private LuckPermsHook luckPerms;
    private MovementAlertManager alertManager;
    private ViolationManager violationManager;

    private final Map<UUID, ConcurrentLinkedDeque<Long>> clicks = new ConcurrentHashMap<>();

    public PacketChecker(Plugin plugin, PluginConfig config, DatabaseManager database, LanguageManager lang) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.lang = lang;
    }

    public void setLuckPerms(LuckPermsHook luckPerms) {
        this.luckPerms = luckPerms;
    }

    public void setAlertManager(MovementAlertManager alertManager) {
        this.alertManager = alertManager;
    }

    public void setViolationManager(ViolationManager violationManager) {
        this.violationManager = violationManager;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!config.packetChecksEnabled()) return;
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Client.ANIMATION) {
            handleAnimation(event);
        } else if (WrapperPlayClientPlayerFlying.isFlying(type)) {
            handleFlying(event);
        }
    }

    /** AutoClicker: clicks per second from arm-swing packets. */
    private void handleAnimation(PacketReceiveEvent event) {
        if (!config.autoClickerDetectionEnabled()) return;
        User user = event.getUser();
        if (user == null || user.getUUID() == null) return;
        UUID id = user.getUUID();

        int cps = recordClick(id);
        int max = config.autoClickerMaxCps();
        if (config.debugMode()) {
            plugin.getLogger().info("[AC-DEBUG] swing from " + user.getName() + " cps=" + cps + " (max " + max + ")");
        }
        if (cps > max) {
            ConcurrentLinkedDeque<Long> dq = clicks.get(id);
            if (dq != null) dq.clear(); // reset so it must re-accumulate
            Bukkit.getScheduler().runTask(plugin, () -> flagAutoClicker(id, cps, max));
        }
    }

    /** BadPackets: rotation values a vanilla client can never send (pitch out of range / non-finite). */
    private void handleFlying(PacketReceiveEvent event) {
        if (!config.badPacketsDetectionEnabled()) return;
        WrapperPlayClientPlayerFlying wrapper = new WrapperPlayClientPlayerFlying(event);
        if (!wrapper.hasRotationChanged()) return;

        Location loc = wrapper.getLocation();
        if (loc == null) return;
        float pitch = loc.getPitch();
        float yaw = loc.getYaw();

        boolean invalid = !Float.isFinite(pitch) || !Float.isFinite(yaw)
                || pitch < -90.0f || pitch > 90.0f;
        if (invalid) {
            User user = event.getUser();
            if (user == null || user.getUUID() == null) return;
            UUID id = user.getUUID();
            Bukkit.getScheduler().runTask(plugin, () -> flagBadPackets(id, pitch));
        }
    }

    /** Add a click timestamp, trim to the sliding window, return the current count. */
    private int recordClick(UUID id) {
        long now = System.currentTimeMillis();
        long cutoff = now - WINDOW_MS;
        ConcurrentLinkedDeque<Long> deque = clicks.computeIfAbsent(id, k -> new ConcurrentLinkedDeque<>());
        deque.addLast(now);
        Long head;
        while ((head = deque.peekFirst()) != null && head < cutoff) {
            deque.pollFirst();
        }
        return deque.size();
    }

    /** Runs on the main thread. */
    private void flagAutoClicker(UUID id, int cps, int max) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) {
            if (config.debugMode()) plugin.getLogger().info("[AC-DEBUG] flag skipped: player offline");
            return;
        }
        if (Exemptions.isExempt(player, config, luckPerms)) {
            if (config.debugMode()) plugin.getLogger().info("[AC-DEBUG] flag skipped: " + player.getName() + " is exempt (creative/whitelist/bypass)");
            return;
        }
        if (config.debugMode()) plugin.getLogger().info("[AC-DEBUG] FLAG " + player.getName() + " cps=" + cps);

        String details = lang.format("alert.autoclicker", cps, max);
        if (database != null) {
            database.logAsync("anticheat_autoclicker", cps, player.getName() + ": " + details);
        }
        if (alertManager != null) {
            alertManager.addAlert(player, "AUTOCLICKER", details, cps, player.getLocation());
        } else if (config.debugMode()) {
            plugin.getLogger().warning("[AntiCheat] " + player.getName() + " AUTOCLICKER - " + details);
        }
        if (violationManager != null) {
            violationManager.flag(player, "AUTOCLICKER");
        }
    }

    /** Runs on the main thread. */
    private void flagBadPackets(UUID id, float pitch) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) return;
        if (Exemptions.isExempt(player, config, luckPerms)) return;

        String details = lang.format("alert.badpackets", pitch);
        if (database != null) {
            database.logAsync("anticheat_badpackets", pitch, player.getName() + ": " + details);
        }
        if (alertManager != null) {
            alertManager.addAlert(player, "BADPACKETS", details, pitch, player.getLocation());
        } else if (config.debugMode()) {
            plugin.getLogger().warning("[AntiCheat] " + player.getName() + " BADPACKETS - " + details);
        }
        if (violationManager != null) {
            violationManager.flag(player, "BADPACKETS");
        }
    }

    public void cleanup(UUID playerId) {
        clicks.remove(playerId);
    }
}
