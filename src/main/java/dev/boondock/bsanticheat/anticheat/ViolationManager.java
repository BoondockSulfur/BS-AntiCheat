package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.api.ViolationEvent;
import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.lang.LanguageManager;
import dev.boondock.bsanticheat.util.Messages;
import dev.boondock.bsanticheat.util.Scheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central violation-level (VL) and punishment handler.
 *
 * <p>Every confirmed detection calls {@link #flag(Player, String)}, which raises the
 * player's VL for that check type. VL decays over time (see {@code decay_seconds}).
 * When a check's VL crosses a configured tier, the tier's console commands are run.
 *
 * <p>The whole system is a no-op unless {@code anticheat.punishments.enabled} is true,
 * so servers that only want alerts are unaffected.
 */
public class ViolationManager {

    /** Tier prefixes that mean "handle this ourselves" rather than "run this command". */
    private static final String INTERNAL_KICK = "@kick";
    private static final String INTERNAL_NOTIFY = "@notify";

    /** Minimum gap between two sweeps for fully decayed entries (see {@link #purgeDecayed}). */
    private static final long PURGE_INTERVAL_MS = 60_000L;

    private final Plugin plugin;
    private final PluginConfig config;
    /** Optional: without it the kick falls back to a plain built-in reason. */
    private LanguageManager lang;

    // playerId -> (checkType -> violation level state). Kept across quit so a relog does not
    // reset the level; entries leave once they have decayed to zero (see purgeDecayed).
    // Structural changes to a player's entry happen inside data.compute/computeIfPresent, so
    // the sweep can never drop a level that a concurrent flag is raising.
    private final Map<UUID, Map<String, Vl>> data = new ConcurrentHashMap<>();
    private final AtomicLong lastPurge = new AtomicLong();

    public void setLanguage(LanguageManager lang) {
        this.lang = lang;
    }

    public ViolationManager(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * Register a violation for a check type and run any punishment tiers that are crossed.
     *
     * @return the player's new VL for this check
     */
    public int flag(Player player, String checkType) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        int[] result = new int[2]; // [tiers served before, level after]

        data.compute(id, (k, perCheck) -> {
            if (perCheck == null) perCheck = new ConcurrentHashMap<>();
            Vl vl = perCheck.computeIfAbsent(checkType, c -> new Vl());
            synchronized (vl) {
                applyDecay(vl, now);
                vl.points += 1.0;
                vl.lastUpdate = now;
                result[1] = level(vl.points);
                // Which tiers have already been served, NOT simply the level before this
                // violation. Those differ: decay runs on every flag, so a VL sitting at exactly
                // 1.0 is 0.9999 a moment later, and comparing levels would let the next +1
                // "cross" 1 a second time and fire the tier twice.
                result[0] = vl.punishedUpTo;
                if (result[1] > vl.punishedUpTo) vl.punishedUpTo = result[1];
            }
            return perCheck;
        });
        int before = result[0];
        int after = result[1];

        // Everything this violation causes runs as one ordered chain: the API event first,
        // then every tier entry in configured order, each step on the thread it needs.
        List<Step> steps = new ArrayList<>();
        ViolationEvent event = new ViolationEvent(player, checkType, after);
        steps.add(new Step(true, () -> callEvent(event), () -> callEvent(event)));

        // Punishment tiers are opt-in.
        if (config.punishmentsEnabled() && after > before) {
            collectTiers(steps, player, checkType, before, after);
        } else if (config.debugMode() && config.punishmentsEnabled()) {
            plugin.getLogger().info("[PUNISH-DEBUG] " + player.getName() + " " + checkType
                    + " VL " + before + " -> " + after + ", no tier reached");
        }
        runChain(player, steps, 0);

        purgeDecayed(now, false);
        return after;
    }

    /**
     * Fire the API event. Guarded: it is information for other plugins and must never stop
     * the punishment steps queued behind it.
     */
    private void callEvent(ViolationEvent event) {
        try {
            fireEvent(event);
        } catch (Exception e) {
            plugin.getLogger().warning("[Punishment] Could not fire ViolationEvent for "
                    + event.getPlayer().getName() + "/" + event.getCheckType() + ": " + e);
        }
    }

    /** How the event reaches listeners. Overridden in tests. */
    void fireEvent(ViolationEvent event) {
        Bukkit.getPluginManager().callEvent(event);
    }

    /**
     * One unit of work caused by a violation.
     *
     * @param onPlayer run on the player's region thread (Paper: main thread) rather than on
     *                 the global region
     * @param action   what to run
     * @param ifGone   for player steps: what to run instead, on the global region, when the
     *                 player has already been removed; null to skip the step
     */
    private record Step(boolean onPlayer, Runnable action, Runnable ifGone) {}

    /**
     * Run {@code steps} from {@code index} on, strictly in order. Consecutive steps for the
     * same thread run in one task; a thread switch is scheduled from inside the task that
     * just finished, so a later step can never overtake an earlier one.
     */
    private void runChain(Player player, List<Step> steps, int index) {
        if (index >= steps.size()) return;
        boolean onPlayer = steps.get(index).onPlayer();
        int end = index;
        while (end < steps.size() && steps.get(end).onPlayer() == onPlayer) end++;
        final int from = index;
        final int to = end;
        try {
            if (onPlayer) {
                runForPlayerRegion(player,
                        () -> {
                            for (int i = from; i < to; i++) guard(steps.get(i).action());
                            runChain(player, steps, to);
                        },
                        // The player was removed before the task ran (a preceding kick, or a
                        // quit): the remaining steps still run, player-bound ones via their
                        // fallback.
                        () -> runOnGlobalRegion(() -> {
                            for (int i = from; i < to; i++) guard(steps.get(i).ifGone());
                            runChain(player, steps, to);
                        }));
            } else {
                runOnGlobalRegion(() -> {
                    for (int i = from; i < to; i++) guard(steps.get(i).action());
                    runChain(player, steps, to);
                });
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Punishment] Could not schedule punishment steps for "
                    + player.getName() + ": " + e);
        }
    }

    private void guard(Runnable r) {
        if (r == null) return;
        try {
            r.run();
        } catch (Exception e) {
            plugin.getLogger().warning("[Punishment] Step failed: " + e);
        }
    }

    /**
     * How work is handed to the global region. One place, so a test can drive the punishment
     * path without a region scheduler — MockBukkit implements none.
     */
    void runOnGlobalRegion(Runnable r) {
        Scheduler.runGlobal(plugin, r);
    }

    /**
     * Same, for work that must run on a specific player's region thread. {@code retired} runs
     * instead when the player is removed before the task runs — including when it already
     * was, where the entity scheduler refuses the task without calling the callback.
     */
    void runForPlayerRegion(Player player, Runnable r, Runnable retired) {
        if (player.getScheduler().run(plugin, task -> r.run(), retired) == null) {
            retired.run();
        }
    }

    /** How a punishment command reaches the server. Overridden in tests. */
    boolean runConsoleCommand(String command) {
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    /**
     * The plugin's own kick, as a step on the player's region thread.
     *
     * @param reason the tier's text, or empty to use {@code punishment.kick_default} from the
     *               language file — which is what makes the message translatable rather than
     *               hard-coded into every server's config
     */
    private Step kick(Player player, String reason, String checkType, int vl) {
        String text = reason;
        if (text.isEmpty()) {
            text = lang != null ? lang.get("punishment.kick_default")
                    : "You have been removed by the anticheat.";
        }
        Component message = Messages.component(fill(text, player, checkType, vl));
        String plain = PlainTextComponentSerializer.plainText().serialize(message);
        return new Step(true, () -> {
            plugin.getLogger().info("[Punishment] Kicking " + player.getName() + ": " + plain);
            try {
                doKick(player, message);
            } catch (Exception e) {
                plugin.getLogger().warning("[Punishment] Kick of " + player.getName()
                        + " failed: " + e.getMessage());
            }
        }, () -> plugin.getLogger().info("[Punishment] " + player.getName()
                + " has already left, kick skipped"));
    }

    /**
     * Put the plugin's own placeholders into a text, then hand the rest to PlaceholderAPI.
     *
     * <p>Used for the tier's own text and for the language-file default alike — the defaults
     * are read AFTER the tier text was filled in, so doing this only for the tier text left a
     * %vl% or %check% sitting unreplaced in every translated message.
     */
    private String fill(String text, Player player, String checkType, int vl) {
        String filled = text
                .replace("%player%", player.getName())
                .replace("%check%", checkType)
                .replace("%vl%", String.valueOf(vl));
        // Anything left over goes to PlaceholderAPI, so a tier can read %player_world% or a
        // rank in its kick message. A no-op without it.
        return Messages.placeholders(player, filled);
    }

    /** Tell the admins, and only the admins, as a step on the global region. */
    private Step notifyAdmins(Player suspect, String text, String checkType, int vl) {
        String raw = text;
        if (raw.isEmpty()) {
            raw = lang != null ? lang.get("punishment.notify_default")
                    : "&e[AC] &f%player% &7reached punishment level &f%vl% &7for &f%check%&7.";
        }
        Component message = Messages.component(fill(raw, suspect, checkType, vl));
        String name = suspect.getName();
        return new Step(false, () -> {
            plugin.getLogger().info("[Punishment] Notifying admins about " + name);
            for (Player admin : Bukkit.getOnlinePlayers()) {
                if (admin.hasPermission("bsanticheat.admin")) admin.sendMessage(message);
            }
        }, null);
    }

    /** The kick itself. Overridden in tests, where MockBukkit has no region to run it on. */
    void doKick(Player player, Component message) {
        player.kick(message);
    }

    /** Current (decayed) VL for a check type, without adding a violation. */
    public int getViolations(UUID playerId, String checkType) {
        Map<String, Vl> perCheck = data.get(playerId);
        if (perCheck == null) return 0;
        Vl vl = perCheck.get(checkType);
        if (vl == null) return 0;
        synchronized (vl) {
            applyDecay(vl, System.currentTimeMillis());
            return level(vl.points);
        }
    }

    /** Snapshot of all non-zero (decayed) VL for a player, keyed by check type. */
    public Map<String, Integer> getAllViolations(UUID playerId) {
        Map<String, Integer> out = new HashMap<>();
        Map<String, Vl> perCheck = data.get(playerId);
        if (perCheck == null) return out;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Vl> e : perCheck.entrySet()) {
            Vl vl = e.getValue();
            int points;
            synchronized (vl) {
                applyDecay(vl, now);
                points = level(vl.points);
            }
            if (points > 0) out.put(e.getKey(), points);
        }
        return out;
    }

    /**
     * Called on quit. The level is deliberately NOT discarded: it keeps decaying while the
     * player is offline, so relogging does not wipe a VL that is one step short of a tier.
     * Only checks that have decayed to zero are dropped, which is the same state as no entry.
     */
    public void cleanup(UUID playerId) {
        long now = System.currentTimeMillis();
        data.computeIfPresent(playerId, (k, perCheck) -> prune(perCheck, now));
        purgeDecayed(now, false);
    }

    /**
     * Drop every check whose level has decayed to zero, across all players, at most once per
     * {@link #PURGE_INTERVAL_MS} unless forced. This bounds memory now that quitting no
     * longer removes anything. With decay disabled nothing ever reaches zero, so levels are
     * kept for the server's uptime, bounded by the number of distinct players ever flagged.
     */
    void purgeDecayed(long now, boolean force) {
        long last = lastPurge.get();
        if (!force && now - last < PURGE_INTERVAL_MS) return;
        if (!lastPurge.compareAndSet(last, now)) return;
        for (UUID id : data.keySet()) {
            data.computeIfPresent(id, (k, perCheck) -> prune(perCheck, now));
        }
    }

    /** Remove decayed checks from one player's map; null (drop the player) when empty. */
    private Map<String, Vl> prune(Map<String, Vl> perCheck, long now) {
        perCheck.entrySet().removeIf(e -> {
            Vl vl = e.getValue();
            synchronized (vl) {
                applyDecay(vl, now);
                return vl.points <= 0.0;
            }
        });
        return perCheck.isEmpty() ? null : perCheck;
    }

    /** Whether any VL state is held for a player. For tests. */
    boolean isTracked(UUID playerId) {
        return data.containsKey(playerId);
    }

    /**
     * The violation level a point total represents.
     *
     * <p>Rounded, not truncated. Decay runs on every read and every flag and subtracts the
     * real time since the last one, so three violations a few seconds apart total 2.93 rather
     * than 3.00; truncating would make that a 2, firing a tier one violation late and reading
     * a fresh violation as zero. Rounding makes the Nth violation reach level N, while a
     * level that has genuinely decayed away (0.4) still reads as gone.
     */
    private static int level(double points) {
        return (int) Math.round(points);
    }

    private void applyDecay(Vl vl, long now) {
        int decaySeconds = config.punishmentsDecaySeconds();
        if (decaySeconds <= 0 || vl.lastUpdate == 0) {
            vl.lastUpdate = now;
            return;
        }
        double elapsedSeconds = (now - vl.lastUpdate) / 1000.0;
        double decayed = elapsedSeconds / decaySeconds; // 1 VL lost per decaySeconds
        if (decayed > 0) {
            vl.points = Math.max(0.0, vl.points - decayed);
            vl.lastUpdate = now;
            // Hysteresis: a tier is armed again once the level has fallen below HALF of it.
            // Tier T is armed iff T > punishedUpTo, so lowering the watermark to
            // floor(2 * points) re-arms exactly the tiers with points < T / 2. A notify at 12
            // fires again after the level dropped under 6 and climbed back, while the rounding
            // jitter of decay (1.0 -> 0.9999) can never re-arm the tier just served. At zero
            // every tier is armed again.
            int rearmBelow = (int) Math.floor(vl.points * 2.0);
            if (rearmBelow < vl.punishedUpTo) vl.punishedUpTo = rearmBelow;
        }
    }

    /**
     * Queue the entries of every tier whose threshold lies in (before, after], in ascending
     * tier order and, within a tier, in configured order.
     */
    private void collectTiers(List<Step> steps, Player player, String checkType, int before, int after) {
        Map<Integer, List<String>> tiers = config.punishmentTiers();
        if (tiers.isEmpty()) {
            plugin.getLogger().warning("[Punishment] " + player.getName() + " reached VL "
                    + after + " for " + checkType + " but no tiers are configured —"
                    + " anticheat.punishments.tiers is empty, so nothing can ever run.");
            return;
        }

        for (Map.Entry<Integer, List<String>> tier : tiers.entrySet()) {
            int threshold = tier.getKey();
            if (threshold > before && threshold <= after) {
                for (String raw : tier.getValue()) {
                    String command = fill(raw, player, checkType, after);
                    // Logged unconditionally, not behind debug_mode: a punishment is an
                    // administrative act and "did it run at all" has to be answerable from
                    // the log.
                    plugin.getLogger().info("[Punishment] " + player.getName() + " reached VL "
                            + after + " for " + checkType + " -> " + command);
                    Step step = dispatch(player, command, checkType, after);
                    if (step != null) steps.add(step);
                }
            }
        }
    }

    /**
     * Turn one tier entry into a step.
     *
     * <p>Console commands run on the GLOBAL region: on Folia the console sender belongs to
     * the global region, and commands that target a player (vanilla /kick, ban plugins)
     * resolve and schedule onto that player themselves. The global region also keeps a
     * command running after a preceding kick removed the player. On Paper every thread
     * involved is the main thread.
     */
    private Step dispatch(Player player, String command, String checkType, int vl) {
        if (command == null || command.isBlank()) return null;

        // The plugin's own kick, rather than borrowing somebody else's /kick: the reason keeps
        // every colour format, no /kick provider is needed, and Player.kick takes a Component.
        // Runs on the player's own thread, where their state may be touched.
        if (command.regionMatches(true, 0, INTERNAL_KICK, 0, INTERNAL_KICK.length())) {
            String reason = command.substring(INTERNAL_KICK.length()).trim();
            return kick(player, reason, checkType, vl);
        }

        // The quiet step of a staggered ladder. Deliberately not "say": that broadcasts to
        // everyone, including the suspect. This reaches holders of bsanticheat.admin only.
        if (command.regionMatches(true, 0, INTERNAL_NOTIFY, 0, INTERNAL_NOTIFY.length())) {
            return notifyAdmins(player, command.substring(INTERNAL_NOTIFY.length()).trim(), checkType, vl);
        }

        return new Step(false, () -> {
            try {
                // dispatchCommand reports false for a command the server does not know, which
                // is what a typo in a tier or a missing punishment plugin looks like from here.
                if (!runConsoleCommand(command)) {
                    plugin.getLogger().warning("[Punishment] The server rejected '" + command
                            + "' — unknown command, or the plugin providing it is not loaded.");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[Punishment] Failed to run command '" + command + "': " + e.getMessage());
            }
        }, null);
    }

    /** Per-check violation-level state. */
    private static final class Vl {
        double points;
        long lastUpdate;
        /** Tier watermark: tiers at or below it have been served (see applyDecay). */
        int punishedUpTo;
    }
}
