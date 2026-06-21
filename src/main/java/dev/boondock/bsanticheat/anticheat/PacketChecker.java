package dev.boondock.bsanticheat.anticheat;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.lang.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.Deque;
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
    // Recent clicking window analysed for consistency. Wide enough that even slow
    // (~2 CPS) clickers accumulate enough samples; pauses still raise the outlier signal.
    private static final long CONSISTENCY_WINDOW_MS = 8000L;

    private final Plugin plugin;
    private final PluginConfig config;
    private final DatabaseManager database;
    private final LanguageManager lang;
    private LuckPermsHook luckPerms;
    private MovementAlertManager alertManager;
    private ViolationManager violationManager;

    private final Map<UUID, ConcurrentLinkedDeque<Long>> clicks = new ConcurrentHashMap<>();
    // Timer balance per player: [0]=balance(ms), [1]=last packet time(ms)
    private final Map<UUID, long[]> timerState = new ConcurrentHashMap<>();
    private static final long TICK_MS = 50L;
    // KillAura rotation GCD analysis
    private final Map<UUID, Float> lastYaw = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> yawDeltas = new ConcurrentHashMap<>();
    private static final double ROT_EXPANDER = 131072.0;

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
        if (ServerLoad.isLagging(config)) return;
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            handleAttack(event);
        } else if (WrapperPlayClientPlayerFlying.isFlying(type)) {
            handleFlying(event);
            handleTimer(event.getUser());
        }
    }

    /**
     * Timer: each movement (flying) packet claims one tick (50ms) of game time. A balance
     * accumulates 50ms per packet minus the real time elapsed; if the client sends packets
     * faster than real time (game-speed/timer hack), the balance grows past the limit.
     */
    private void handleTimer(User user) {
        if (!config.timerDetectionEnabled()) return;
        if (user == null || user.getUUID() == null) return;
        UUID id = user.getUUID();
        long now = System.currentTimeMillis();

        long[] st = timerState.computeIfAbsent(id, k -> new long[]{0L, now});
        long balance = st[0] + TICK_MS - (now - st[1]);
        if (balance < -1000L) balance = -1000L; // floor: don't bank unlimited credit while idle
        st[1] = now;

        if (balance > config.timerMaxBalanceMs()) {
            st[0] = 0L; // reset after flagging
            long bal = balance;
            Bukkit.getScheduler().runTask(plugin, () -> flagTimer(id, bal));
        } else {
            st[0] = balance;
        }
    }

    /**
     * AutoClicker: counts ATTACK packets (not arm-swing animations). Mining/holding the
     * button sends swings every tick but NO attack packets, so this no longer false-flags
     * normal mining. Raw CPS over the cap, optionally plus interval-consistency.
     */
    private void handleAttack(PacketReceiveEvent event) {
        if (!config.autoClickerDetectionEnabled()) return;
        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
        if (wrapper.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) return;
        User user = event.getUser();
        if (user == null || user.getUUID() == null) return;
        UUID id = user.getUUID();

        long now = System.currentTimeMillis();
        ConcurrentLinkedDeque<Long> buf = clicks.computeIfAbsent(id, k -> new ConcurrentLinkedDeque<>());
        buf.addLast(now);
        // Drop samples older than the analysis window (pauses must not pollute interval
        // stats), then cap the count.
        long minTime = now - CONSISTENCY_WINDOW_MS;
        Long head;
        while ((head = buf.peekFirst()) != null && head < minTime) buf.pollFirst();
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

    /** Rotation packets: BadPackets (impossible pitch) and KillAura rotation GCD. */
    private void handleFlying(PacketReceiveEvent event) {
        boolean bp = config.badPacketsDetectionEnabled();
        boolean rot = config.killAuraRotationDetectionEnabled();
        if (!bp && !rot) return;

        WrapperPlayClientPlayerFlying wrapper = new WrapperPlayClientPlayerFlying(event);
        if (!wrapper.hasRotationChanged()) return;
        Location loc = wrapper.getLocation();
        if (loc == null) return;
        User user = event.getUser();
        if (user == null || user.getUUID() == null) return;
        UUID id = user.getUUID();
        float pitch = loc.getPitch();
        float yaw = loc.getYaw();

        // BadPackets: impossible rotation values
        if (bp && (!Float.isFinite(pitch) || !Float.isFinite(yaw) || pitch < -90.0f || pitch > 90.0f)) {
            Bukkit.getScheduler().runTask(plugin, () -> flagBadPackets(id, pitch));
        }

        // KillAura rotation GCD: human mouse input is quantised (yaw deltas share a common
        // divisor); programmatic aim collapses the GCD toward 1. Experimental — off by
        // default; calibrate killaura_rotation_min_gcd via debug_mode (logs measured gcd).
        if (rot && Float.isFinite(yaw)) {
            Float last = lastYaw.put(id, yaw);
            if (last != null) {
                double dyaw = Math.abs(wrapAngle(yaw - last));
                if (dyaw > 0.05 && dyaw < 30.0) { // ignore idle noise and big snaps/spins
                    long scaled = Math.round(dyaw * ROT_EXPANDER);
                    Deque<Long> dq = yawDeltas.computeIfAbsent(id, k -> new ArrayDeque<>());
                    dq.addLast(scaled);
                    int samples = config.killAuraRotationSamples();
                    while (dq.size() > samples) dq.pollFirst();
                    if (dq.size() >= samples) {
                        long g = 0;
                        for (long v : dq) g = gcd(g, v);
                        if (config.debugMode()) {
                            plugin.getLogger().info("[ROT-DEBUG] " + user.getName() + " gcd=" + g);
                        }
                        if (g > 0 && g < config.killAuraRotationMinGcd()) {
                            dq.clear();
                            long flagged = g;
                            Bukkit.getScheduler().runTask(plugin, () -> flagRotation(id, flagged));
                        }
                    }
                }
            }
        }
    }

    private static double wrapAngle(double a) {
        a %= 360.0;
        if (a >= 180.0) a -= 360.0;
        if (a < -180.0) a += 360.0;
        return a;
    }

    private static long gcd(long a, long b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while (b != 0) {
            long t = b;
            b = a % b;
            a = t;
        }
        return a;
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

    /** Runs on the main thread. */
    private void flagTimer(UUID id, long balance) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) return;
        if (Exemptions.isExempt(player, config, luckPerms)) return;

        String details = lang.format("alert.timer", balance);
        if (database != null) {
            database.logAsync("anticheat_timer", balance, player.getName() + ": " + details);
        }
        if (alertManager != null) {
            alertManager.addAlert(player, "TIMER", details, balance, player.getLocation());
        } else if (config.debugMode()) {
            plugin.getLogger().warning("[AntiCheat] " + player.getName() + " TIMER - " + details);
        }
        if (violationManager != null) {
            violationManager.flag(player, "TIMER");
        }
    }

    /** Runs on the main thread. */
    private void flagRotation(UUID id, long measuredGcd) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) return;
        if (Exemptions.isExempt(player, config, luckPerms)) return;

        String details = lang.format("alert.killaura_rotation", measuredGcd);
        if (database != null) {
            database.logAsync("anticheat_killaura", measuredGcd, player.getName() + ": " + details);
        }
        if (alertManager != null) {
            alertManager.addAlert(player, "KILLAURA", details, measuredGcd, player.getLocation());
        } else if (config.debugMode()) {
            plugin.getLogger().warning("[AntiCheat] " + player.getName() + " KILLAURA - " + details);
        }
        if (violationManager != null) {
            violationManager.flag(player, "KILLAURA");
        }
    }

    public void cleanup(UUID playerId) {
        clicks.remove(playerId);
        timerState.remove(playerId);
        lastYaw.remove(playerId);
        yawDeltas.remove(playerId);
    }
}
