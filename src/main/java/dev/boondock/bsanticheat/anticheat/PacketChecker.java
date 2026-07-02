package dev.boondock.bsanticheat.anticheat;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEditBook;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.integration.GeyserHook;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.lang.LanguageManager;
import dev.boondock.bsanticheat.util.Constants;
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
    private GeyserHook geyser;
    private MovementAlertManager alertManager;
    private ViolationManager violationManager;
    private TransactionManager transactionManager;

    private final Map<UUID, ConcurrentLinkedDeque<Long>> clicks = new ConcurrentHashMap<>();
    // Timer balance per player: [0]=balance(ms), [1]=last packet time(ms)
    private final Map<UUID, long[]> timerState = new ConcurrentHashMap<>();
    private static final long TICK_MS = 50L;
    // Held-button (mining/swinging) signature to exclude from AutoClicker: ~tick-rate, ultra-regular
    private static final int HELD_CPS_MIN = 18;
    private static final int HELD_CPS_MAX = 22;
    private static final double HELD_SD_MAX = 5.0;
    // KillAura rotation GCD analysis
    private final Map<UUID, Float> lastYaw = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> yawDeltas = new ConcurrentHashMap<>();
    private static final double ROT_EXPANDER = 131072.0;
    // AimSnap: last 3 look directions + recent snap timestamps
    private final Map<UUID, Deque<double[]>> recentDirs = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> snapTimes = new ConcurrentHashMap<>();
    // Packet flood: [0]=window start(ms), [1]=count in window
    private final Map<UUID, long[]> packetCounts = new ConcurrentHashMap<>();

    public PacketChecker(Plugin plugin, PluginConfig config, DatabaseManager database, LanguageManager lang) {
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

    public void setTransactionManager(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();

        // Transaction pongs feed the latency system and must run regardless of which
        // checks are enabled — they are latency infrastructure, not a detection.
        if (type == PacketType.Play.Client.PONG) {
            if (transactionManager != null) {
                User u = event.getUser();
                if (u != null && u.getUUID() != null) {
                    transactionManager.onPong(u.getUUID(), new WrapperPlayClientPong(event).getId(), u.getName());
                }
            }
            return;
        }

        if (!config.packetChecksEnabled()) return;

        // Crash protection & flood run even under lag — an attack CAUSES lag, so the
        // lag exemption must not disable exactly these checks.
        handleFlood(event);
        if (type == PacketType.Play.Client.EDIT_BOOK || type == PacketType.Play.Client.UPDATE_SIGN) {
            handleCrasher(event, type);
        }

        if (ServerLoad.isLagging(config)) return;
        if (type == PacketType.Play.Client.ANIMATION) {
            handleSwing(event);
        } else if (WrapperPlayClientPlayerFlying.isFlying(type)) {
            handleFlying(event);
            handleTimer(event.getUser());
        }
    }

    /**
     * Packet flood: raw packets per second per connection. A vanilla client peaks well
     * below 200/s even in hectic PvP; floods (crash/lag bots) send thousands.
     */
    private void handleFlood(PacketReceiveEvent event) {
        if (!config.packetFloodDetectionEnabled()) return;
        User user = event.getUser();
        if (user == null || user.getUUID() == null) return;
        UUID id = user.getUUID();

        long now = System.currentTimeMillis();
        long[] st = packetCounts.computeIfAbsent(id, k -> new long[]{now, 0L});
        synchronized (st) {
            if (now - st[0] >= WINDOW_MS) {
                st[0] = now;
                st[1] = 1;
                return;
            }
            st[1]++;
            if (st[1] > config.packetFloodMaxPerSecond()) {
                st[0] = now;
                st[1] = 0;
                long rate = config.packetFloodMaxPerSecond() + 1;
                String name = user.getName();
                flagSimple(id, name, "PACKETFLOOD", lang.format("alert.packetflood", rate), rate);
            }
        }
    }

    /**
     * Crash protection: oversized book/sign payloads (classic crasher exploits). The
     * malicious packet is cancelled so the server never processes it; the flag is
     * raised afterwards on the main thread.
     */
    private void handleCrasher(PacketReceiveEvent event, PacketTypeCommon type) {
        if (!config.crasherDetectionEnabled()) return;
        User user = event.getUser();
        if (user == null || user.getUUID() == null) return;
        UUID id = user.getUUID();

        if (type == PacketType.Play.Client.EDIT_BOOK) {
            WrapperPlayClientEditBook wrapper = new WrapperPlayClientEditBook(event);
            var pages = wrapper.getPages();
            int pageCount = pages != null ? pages.size() : 0;
            long totalChars = 0;
            boolean oversizedPage = false;
            if (pages != null) {
                for (String page : pages) {
                    if (page == null) continue;
                    totalChars += page.length();
                    if (page.length() > Constants.CRASHER_MAX_BOOK_PAGE_CHARS) oversizedPage = true;
                }
            }
            if (pageCount > Constants.CRASHER_MAX_BOOK_PAGES || oversizedPage
                    || totalChars > Constants.CRASHER_MAX_BOOK_TOTAL_CHARS) {
                event.setCancelled(true);
                int pc = pageCount;
                flagSimple(id, user.getName(), "CRASHER", lang.format("alert.crasher_book", pc), pc);
            }
        } else {
            WrapperPlayClientUpdateSign wrapper = new WrapperPlayClientUpdateSign(event);
            String[] lines = wrapper.getTextLines();
            if (lines != null) {
                for (String line : lines) {
                    if (line != null && line.length() > Constants.CRASHER_MAX_SIGN_LINE_CHARS) {
                        event.setCancelled(true);
                        flagSimple(id, user.getName(), "CRASHER", lang.get("alert.crasher_sign"), line.length());
                        return;
                    }
                }
            }
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
     * AutoClicker: clicks per second from arm-swing (ANIMATION) packets — covers clicking
     * on air, blocks or entities. Holding the button to mine/swing sends one swing per tick
     * (~20 CPS at near-zero jitter); that "held button" signature is excluded so normal
     * mining/holding doesn't false-flag.
     */
    private void handleSwing(PacketReceiveEvent event) {
        if (!config.autoClickerDetectionEnabled()) return;
        User user = event.getUser();
        if (user == null || user.getUUID() == null) return;
        UUID id = user.getUUID();

        long now = System.currentTimeMillis();
        ConcurrentLinkedDeque<Long> buf = clicks.computeIfAbsent(id, k -> new ConcurrentLinkedDeque<>());
        buf.addLast(now);
        long minTime = now - CONSISTENCY_WINDOW_MS;
        Long head;
        while ((head = buf.peekFirst()) != null && head < minTime) buf.pollFirst();
        while (buf.size() > SAMPLE_CAP) buf.pollFirst();

        Long[] times = buf.toArray(new Long[0]);
        int n = times.length;

        // CPS = swings within the last second
        int cps = 0;
        for (Long t : times) if (t >= now - WINDOW_MS) cps++;

        int maxCps = config.autoClickerMaxCps();

        // Standard deviation of recent intervals (for the held-button exclusion)
        double sd = -1;
        if (n >= 8) {
            double sum = 0;
            for (int i = 1; i < n; i++) sum += times[i] - times[i - 1];
            double mean = sum / (n - 1);
            double v = 0;
            for (int i = 1; i < n; i++) { double d = times[i] - times[i - 1] - mean; v += d * d; }
            sd = Math.sqrt(v / (n - 1));
        }
        // Held left-click (mining/swinging) sends ~one swing per tick: ~20 CPS, ultra-regular.
        boolean heldButton = cps >= HELD_CPS_MIN && cps <= HELD_CPS_MAX && sd >= 0 && sd < HELD_SD_MAX;

        if (config.debugMode()) {
            plugin.getLogger().info(String.format("[AC-DEBUG] %s cps=%d sd=%s held=%b (max %d)",
                    user.getName(), cps, sd < 0 ? "n/a" : String.format("%.1f", sd), heldButton, maxCps));
        }

        if (cps > maxCps && !heldButton) {
            buf.clear(); // reset so it must re-accumulate
            int cpsSnap = cps;
            Bukkit.getScheduler().runTask(plugin, () -> flagAutoClicker(id, cpsSnap, maxCps));
        }
    }

    /** Rotation packets: BadPackets (impossible pitch) and KillAura rotation GCD. */
    private void handleFlying(PacketReceiveEvent event) {
        boolean bp = config.badPacketsDetectionEnabled();
        boolean rot = config.killAuraRotationDetectionEnabled();
        boolean snap = config.aimSnapDetectionEnabled();
        if (!bp && !rot && !snap) return;

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

        // (c) AimSnap: a robotic rotation snaps to the target and back within ~1 tick
        // (look A -> far B -> back near A), which a mouse can't do. Catches rotation-
        // spoofing Scaffold/KillAura even when the per-frame angle to the target is small.
        if (snap && Float.isFinite(yaw) && Float.isFinite(pitch)) {
            Deque<double[]> dirs = recentDirs.computeIfAbsent(id, k -> new ArrayDeque<>());
            dirs.addLast(dirOf(yaw, pitch));
            while (dirs.size() > 3) dirs.pollFirst();
            if (dirs.size() == 3) {
                double[][] d = dirs.toArray(new double[0][]);
                double spikeIn = angleBetween(d[0], d[1]);
                double spikeOut = angleBetween(d[1], d[2]);
                double net = angleBetween(d[0], d[2]);
                if (config.debugMode()) {
                    plugin.getLogger().info(String.format("[SNAP-DEBUG] %s in=%.0f out=%.0f net=%.0f",
                            user.getName(), spikeIn, spikeOut, net));
                }
                if (spikeIn > config.aimSnapMinAngle() && spikeOut > config.aimSnapMinAngle()
                        && net < config.aimSnapReturnAngle()) {
                    long now = System.currentTimeMillis();
                    Deque<Long> st = snapTimes.computeIfAbsent(id, k -> new ArrayDeque<>());
                    st.addLast(now);
                    while (!st.isEmpty() && st.peekFirst() < now - config.aimSnapWindowMs()) st.pollFirst();
                    if (st.size() >= config.aimSnapThreshold()) {
                        st.clear();
                        double angle = spikeIn;
                        Bukkit.getScheduler().runTask(plugin, () -> flagAimSnap(id, angle));
                    }
                }
            }
        }
    }

    private static double[] dirOf(float yaw, float pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        double cp = Math.cos(p);
        return new double[]{ -cp * Math.sin(y), -Math.sin(p), cp * Math.cos(y) };
    }

    private static double angleBetween(double[] a, double[] b) {
        double dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        dot = Math.max(-1.0, Math.min(1.0, dot));
        return Math.toDegrees(Math.acos(dot));
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

    /**
     * Generic flag path for the simple packet checks (crasher/flood). Callable from the
     * Netty thread: the DB log goes through the thread-safe queue immediately with the
     * connection's name, so the record survives even when a crash/flood attempt drops the
     * connection before the next tick. Alert + VL need Bukkit API, so they hop to the main
     * thread and are best-effort (skipped if the player already disconnected).
     */
    private void flagSimple(UUID id, String name, String type, String details, double value) {
        if (database != null) {
            database.logAsync("anticheat_" + type.toLowerCase(), value, name + ": " + details);
        }
        Runnable main = () -> {
            Player player = Bukkit.getPlayer(id);
            if (player == null || Exemptions.isExempt(player, config, luckPerms, geyser)) return;
            if (alertManager != null) {
                alertManager.addAlert(player, type, details, value, player.getLocation());
            } else if (config.debugMode()) {
                plugin.getLogger().warning("[AntiCheat] " + player.getName() + " " + type + " - " + details);
            }
            if (violationManager != null) {
                violationManager.flag(player, type);
            }
        };
        if (Bukkit.isPrimaryThread()) main.run();
        else Bukkit.getScheduler().runTask(plugin, main);
    }

    /** Runs on the main thread. */
    private void flagAutoClicker(UUID id, int cps, int maxCps) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) return;
        if (Exemptions.isExempt(player, config, luckPerms, geyser)) {
            if (config.debugMode()) plugin.getLogger().info("[AC-DEBUG] flag skipped: " + player.getName() + " is exempt");
            return;
        }
        if (config.debugMode()) plugin.getLogger().info("[AC-DEBUG] FLAG " + player.getName() + " cps=" + cps);

        String details = lang.format("alert.autoclicker", cps, maxCps);
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
        if (Exemptions.isExempt(player, config, luckPerms, geyser)) return;

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
        if (Exemptions.isExempt(player, config, luckPerms, geyser)) return;

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
        if (Exemptions.isExempt(player, config, luckPerms, geyser)) return;

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

    /** Runs on the main thread. */
    private void flagAimSnap(UUID id, double angle) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) return;
        if (Exemptions.isExempt(player, config, luckPerms, geyser)) return;

        String details = lang.format("alert.aimsnap", angle);
        if (database != null) {
            database.logAsync("anticheat_aimsnap", angle, player.getName() + ": " + details);
        }
        if (alertManager != null) {
            alertManager.addAlert(player, "AIMSNAP", details, angle, player.getLocation());
        } else if (config.debugMode()) {
            plugin.getLogger().warning("[AntiCheat] " + player.getName() + " AIMSNAP - " + details);
        }
        if (violationManager != null) {
            violationManager.flag(player, "AIMSNAP");
        }
    }

    public void cleanup(UUID playerId) {
        clicks.remove(playerId);
        timerState.remove(playerId);
        packetCounts.remove(playerId);
        lastYaw.remove(playerId);
        yawDeltas.remove(playerId);
        recentDirs.remove(playerId);
        snapTimes.remove(playerId);
    }
}
