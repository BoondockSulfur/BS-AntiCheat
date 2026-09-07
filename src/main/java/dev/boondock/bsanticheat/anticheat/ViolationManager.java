package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.api.ViolationEvent;
import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.lang.LanguageManager;
import dev.boondock.bsanticheat.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import dev.boondock.bsanticheat.util.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    private final Plugin plugin;
    private final PluginConfig config;
    /** Optional: without it the kick falls back to a plain built-in reason. */
    private LanguageManager lang;

    // playerId -> (checkType -> violation level state)
    private final Map<UUID, Map<String, Vl>> data = new ConcurrentHashMap<>();

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
     * @return the player's new VL for this check (0 if punishments are disabled)
     */
    public int flag(Player player, String checkType) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Map<String, Vl> perCheck = data.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        Vl vl = perCheck.computeIfAbsent(checkType, k -> new Vl());

        int before, after;
        synchronized (vl) {
            applyDecay(vl, now);
            vl.points += 1.0;
            vl.lastUpdate = now;
            after = level(vl.points);
            // Which tiers have already been served, NOT simply the level before this
            // violation. Those differ, and using the level fired a tier twice: decay runs on
            // every flag, so a VL sitting at exactly 1.0 is 0.9999 a moment later, floors
            // back to 0, and the +1 "crosses" 1 a second time. With a kick that is a
            // duplicate; with a ban or a tempban in the tier it is a second punishment for
            // a violation already punished.
            before = vl.punishedUpTo;
            if (after > vl.punishedUpTo) vl.punishedUpTo = after;
        }

        // VL is always tracked (for /bsac info, placeholders, the API event).
        //
        // Guarded: the API event is information for other plugins and must never be able to
        // stop a punishment. It used to sit on the punishment's critical path unprotected, so
        // anything that made the scheduling throw — a plugin disabled mid-shutdown, a server
        // implementation without the region scheduler — silently swallowed the tier below it
        // as well, with the violation still counted and nothing in the log to say why nobody
        // was punished.
        try {
            fireViolationEvent(player, checkType, after);
        } catch (Exception e) {
            plugin.getLogger().warning("[Punishment] Could not fire ViolationEvent for "
                    + player.getName() + "/" + checkType + ": " + e);
        }

        // Punishment tiers are opt-in.
        if (config.punishmentsEnabled() && after > before) {
            runTiers(player, checkType, before, after);
        } else if (config.debugMode() && config.punishmentsEnabled()) {
            plugin.getLogger().info("[PUNISH-DEBUG] " + player.getName() + " " + checkType
                    + " VL " + before + " -> " + after + ", keine Stufe erreicht");
        }
        return after;
    }

    private void fireViolationEvent(Player player, String checkType, int vl) {
        ViolationEvent event = new ViolationEvent(player, checkType, vl);
        // Fire on the global region (Folia-safe); on Paper this is the main thread.
        runOnGlobalRegion(() -> Bukkit.getPluginManager().callEvent(event));
    }

    /**
     * How work is handed to the global region. One place, so a test can drive the punishment
     * path without a region scheduler — MockBukkit implements none, which is why this whole
     * class went untested while every check around it had scenarios.
     */
    void runOnGlobalRegion(Runnable r) {
        Scheduler.runGlobal(plugin, r);
    }

    /** Same, for work that must run on a specific player's region thread. */
    void runForPlayerRegion(Player player, Runnable r) {
        Scheduler.runForEntity(plugin, player, r);
    }

    /** How a punishment command reaches the server. Overridden in tests. */
    boolean runConsoleCommand(String command) {
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    /**
     * Kick the player ourselves, on their own region thread.
     *
     * @param reason the tier's text, or empty to use {@code punishment.kick_default} from the
     *               language file — which is what makes the message translatable rather than
     *               hard-coded into every server's config
     */
    private void kick(Player player, String reason, String checkType, int vl) {
        String text = reason;
        if (text.isEmpty()) {
            text = lang != null ? lang.get("punishment.kick_default")
                    : "You have been removed by the anticheat.";
        }
        Component message = Messages.component(fill(text, player, checkType, vl));
        plugin.getLogger().info("[Punishment] Kicking " + player.getName() + ": "
                + PlainTextComponentSerializer.plainText().serialize(message));
        runForPlayerRegion(player, () -> {
            try {
                doKick(player, message);
            } catch (Exception e) {
                plugin.getLogger().warning("[Punishment] Kick of " + player.getName()
                        + " failed: " + e.getMessage());
            }
        });
    }

    /**
     * Put the plugin's own placeholders into a text, then hand the rest to PlaceholderAPI.
     *
     * <p>Used for the tier's own text and for the language-file default alike — the defaults
     * are read AFTER the tier text was filled in, so doing this only in runTiers left a
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

    /** Tell the admins, and only the admins. */
    private void notifyAdmins(Player suspect, String text, String checkType, int vl) {
        String raw = text;
        if (raw.isEmpty()) {
            raw = lang != null ? lang.get("punishment.notify_default")
                    : "&e[AC] &f%player% &7reached punishment level &f%vl% &7for &f%check%&7.";
        }
        Component message = Messages.component(fill(raw, suspect, checkType, vl));
        plugin.getLogger().info("[Punishment] Notifying admins about " + suspect.getName());
        runOnGlobalRegion(() -> {
            for (Player admin : Bukkit.getOnlinePlayers()) {
                if (admin.hasPermission("bsanticheat.admin")) admin.sendMessage(message);
            }
        });
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

    /** Remove all tracked VL for a player (call on quit). */
    public void cleanup(UUID playerId) {
        data.remove(playerId);
    }

    /**
     * The violation level a point total represents.
     *
     * <p>Rounded, not truncated. Decay runs on every read and every flag and subtracts the
     * real time since the last one, so three violations a few seconds apart total 2.93 rather
     * than 3.00 — and truncating made that a 2. Two consequences, both seen live on mc-test
     * 2026-09-07: a tier fired one violation later than configured (tier 3 needed a fourth
     * flag), and a single violation showed as "no violations" in {@code /bsac info} a
     * millisecond after it was counted, which is exactly when an admin looks. Rounding makes
     * the Nth violation reach level N, while a level that has genuinely decayed away (0.4)
     * still reads as gone.
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
            // A fully decayed level arms every tier again — otherwise a player who reached a
            // tier once could never be punished by it again for the rest of the session.
            //
            // Only at ZERO, deliberately. Anything finer re-arms on rounding: decay runs on
            // every flag, so a level sitting at exactly 1.0 is 0.9999 a moment later and
            // floors to 0 while the player has in fact just violated again.
            if (vl.points <= 0.0) vl.punishedUpTo = 0;
        }
    }

    /**
     * Run the commands of every tier whose threshold lies in (before, after].
     */
    private void runTiers(Player player, String checkType, int before, int after) {
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
                    // administrative act and "did it run at all" was previously unanswerable
                    // from the log — the reason a report of "the kick does not happen" could
                    // not be told apart from "the tier was never reached".
                    plugin.getLogger().info("[Punishment] " + player.getName() + " reached VL "
                            + after + " for " + checkType + " -> " + command);
                    dispatch(player, command, checkType, after);
                }
            }
        }
    }

    /**
     * Run a punishment command from the console.
     *
     * <p>On the PLAYER's region thread, not the global one. A punishment command is about a
     * specific player — kick, ban, tempban all reach into that player's state — and on Folia
     * an entity may only be touched from the region that owns it. The global region is not
     * that region, and a command that trips Folia's thread check dies inside the command
     * dispatcher. On Paper both are the main thread, so this changes nothing there.
     *
     * <p>Falls back to the global region when the player is already gone (a tier whose first
     * command kicked them, and a second one that bans afterwards), because the command still
     * has to run and no longer has a region to run in.
     */
    private void dispatch(Player player, String command, String checkType, int vl) {
        if (command == null || command.isBlank()) return;

        // The plugin's own kick, rather than borrowing somebody else's /kick. Three things
        // that a console command cannot give us: the reason keeps every colour format (a
        // dispatched command hands its text to whichever plugin owns /kick, and vanilla's
        // passes it through as plain text), there is no dependency on such a plugin being
        // installed at all, and Player.kick takes a Component so nothing has to survive a
        // round trip through legacy strings.
        if (command.regionMatches(true, 0, INTERNAL_KICK, 0, INTERNAL_KICK.length())) {
            String reason = command.substring(INTERNAL_KICK.length()).trim();
            kick(player, reason, checkType, vl);
            return;
        }

        // The quiet step of a staggered ladder. Deliberately not "say": that broadcasts to
        // everyone, which tells the suspect they have been noticed and spams the server for a
        // verdict that may still turn out to be a false positive. This reaches holders of
        // bsanticheat.admin and nobody else.
        if (command.regionMatches(true, 0, INTERNAL_NOTIFY, 0, INTERNAL_NOTIFY.length())) {
            notifyAdmins(player, command.substring(INTERNAL_NOTIFY.length()).trim(), checkType, vl);
            return;
        }

        Runnable task = () -> {
            try {
                // The return value matters and was thrown away: dispatchCommand reports
                // false for a command the server does not know, which is what a typo in a
                // tier, or a punishment plugin that has not loaded, looks like from here.
                // Silently discarding it made "the kick does not happen" indistinguishable
                // from "the tier never fired".
                if (!runConsoleCommand(command)) {
                    plugin.getLogger().warning("[Punishment] The server rejected '" + command
                            + "' — unknown command, or the plugin providing it is not loaded.");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[Punishment] Failed to run command '" + command + "': " + e.getMessage());
            }
        };
        if (player.isOnline()) {
            runForPlayerRegion(player, task);
        } else {
            runOnGlobalRegion(task);
        }
    }

    /** Per-check violation-level state. */
    private static final class Vl {
        double points;
        long lastUpdate;
        /** Highest tier level already acted on, so a tier is served once per climb. */
        int punishedUpTo;
    }
}
