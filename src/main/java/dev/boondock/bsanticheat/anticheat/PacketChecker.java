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
 * Packet-level checks (via PacketEvents).
 * <ul>
 *   <li>AutoClicker — from arm-swing (ANIMATION) packets, using two signals: raw clicks
 *       per second, and click-interval consistency (low standard deviation = metronomic =
 *       autoclicker, which also catches slow-but-perfectly-regular clickers).</li>
 *   <li>BadPackets — impossible rotation values.</li>
 * </ul>
 *
 * <p>Runs on Netty threads, so all Bukkit access (alerts) is hopped back to the main
 * thread before use.
 */
public class PacketChecker implements PacketListener {

    private static final long WINDOW_MS = 1000L;
    // How many recent swing timestamps to keep per player for interval analysis.
    private static final int SAMPLE_CAP = 40;

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

    /** AutoClicker: raw CPS over the cap, OR click intervals that are too consistent. */
    private void handleAnimation(PacketReceiveEvent event) {
        if (!config.autoClickerDetectionEnabled()) return;
        User user = event.getUser();
        if (user == null || user.getUUID() == null) return;
        UUID id = user.getUUID();

        long now = System.currentTimeMillis();
        ConcurrentLinkedDeque<Long> buf = clicks.computeIfAbsent(id, k -> new ConcurrentLinkedDeque<>());
        buf.addLast(now);
        while (buf.size() > SAMPLE_CAP) buf.pollFirst();

        Long[] times = buf.toArray(new Long[0]);
        int n = times.length;

        // CPS = swings within the last second
        int cps = 0;
        for (Long t : times) if (t >= now - WINDOW_MS) cps++;

        // Click-interval pattern analysis. Humans jitter AND pause occasionally;
        // autoclickers (even "humanized") usually fail at least two of three markers:
        // low std-dev, low coefficient of variation, and near-zero long-pause outliers.
        double meanInterval = -1, sd = -1, cv = -1, outlierRatio = -1;
        int signals = 0;
        int minSamples = config.autoClickerMinSamples();
        if (config.autoClickerConsistencyEnabled() && n >= minSamples + 1) {
            int m = n - 1;
            double[] deltas = new double[m];
            double sum = 0;
            for (int i = 1; i < n; i++) { deltas[i - 1] = times[i] - times[i - 1]; sum += deltas[i - 1]; }
            meanInterval = sum / m;

            double v = 0;
            for (double d : deltas) { double e = d - meanInterval; v += e * e; }
            sd = Math.sqrt(v / m);
            cv = meanInterval > 0 ? sd / meanInterval : 0;

            double[] sorted = deltas.clone();
            java.util.Arrays.sort(sorted);
            double median = sorted[m / 2];
            int outliers = 0;
            for (double d : deltas) if (d > median * 1.5) outliers++;
            outlierRatio = (double) outliers / m;

            // Count how many "robotic" markers are present
            if (sd <= config.autoClickerMaxDeviationMs()) signals++;
            if (cv <= config.autoClickerMaxCv()) signals++;
            if (outlierRatio <= config.autoClickerMaxOutlierRatio()) signals++;
        }

        int maxCps = config.autoClickerMaxCps();
        double derivedCps = meanInterval > 0 ? 1000.0 / meanInterval : 0;
        boolean tooFast = cps > maxCps;
        boolean tooConsistent = meanInterval > 0
                && derivedCps >= config.autoClickerMinCps()
                && signals >= config.autoClickerMinSignals();

        if (config.debugMode()) {
            plugin.getLogger().info(String.format(
                    "[AC-DEBUG] %s cps=%d sd=%s cv=%s outliers=%s signals=%d/%d mean=%s",
                    user.getName(), cps,
                    sd < 0 ? "n/a" : String.format("%.1f", sd),
                    cv < 0 ? "n/a" : String.format("%.2f", cv),
                    outlierRatio < 0 ? "n/a" : String.format("%.2f", outlierRatio),
                    signals, config.autoClickerMinSignals(),
                    meanInterval < 0 ? "n/a" : String.format("%.1f", meanInterval)));
        }

        if (tooFast || tooConsistent) {
            buf.clear(); // reset so it must re-accumulate
            boolean pattern = !tooFast; // prefer the raw-CPS reason if both apply
            double sdSnap = sd;
            int cpsSnap = tooFast ? cps : (int) Math.round(derivedCps);
            Bukkit.getScheduler().runTask(plugin, () -> flagAutoClicker(id, cpsSnap, maxCps, pattern, sdSnap));
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

    /** Runs on the main thread. */
    private void flagAutoClicker(UUID id, int cps, int maxCps, boolean pattern, double sd) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) {
            if (config.debugMode()) plugin.getLogger().info("[AC-DEBUG] flag skipped: player offline");
            return;
        }
        if (Exemptions.isExempt(player, config, luckPerms)) {
            if (config.debugMode()) plugin.getLogger().info("[AC-DEBUG] flag skipped: " + player.getName() + " is exempt (creative/whitelist/bypass)");
            return;
        }
        if (config.debugMode()) {
            plugin.getLogger().info("[AC-DEBUG] FLAG " + player.getName()
                    + (pattern ? " pattern sd=" + String.format("%.1f", sd) + " cps=" + cps : " cps=" + cps));
        }

        String details = pattern
                ? lang.format("alert.autoclicker_pattern", sd, cps)
                : lang.format("alert.autoclicker", cps, maxCps);
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
