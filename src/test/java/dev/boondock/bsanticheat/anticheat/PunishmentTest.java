package dev.boondock.bsanticheat.anticheat;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The punishment path, end to end: a violation raises a VL, a VL crosses a tier, the tier's
 * console commands run.
 *
 * <p>This class had no tests at all — every scenario elsewhere replaces the ViolationManager
 * with a counter, because MockBukkit implements no region scheduler and the real one reaches
 * for it. So the one part of the plugin that acts ON a player rather than reporting about
 * them was the part nothing exercised, which is where a report of "the kick does not happen"
 * lands. The scheduler is the only thing stubbed here; the VL arithmetic, the tier matching
 * and the command building are the real ones.
 */
class PunishmentTest extends ScenarioBase {

    /** Records what would be dispatched, and runs it on the spot instead of on a region. */
    private static class RecordingViolations extends ViolationManager {
        final List<String> dispatched = new ArrayList<>();
        final List<String> kicks = new ArrayList<>();
        /** Every step in execution order, tagged with the thread kind it ran on. */
        final List<String> trace = new ArrayList<>();
        /** Simulates a player removed before their region task could run. */
        boolean playerGone;
        private String where = "?";

        RecordingViolations(org.bukkit.plugin.java.JavaPlugin plugin,
                            dev.boondock.bsanticheat.config.PluginConfig config) {
            super(plugin, config);
        }

        @Override
        void runOnGlobalRegion(Runnable r) {
            String prev = where;
            where = "global";
            r.run();
            where = prev;
        }

        @Override
        void runForPlayerRegion(Player player, Runnable r, Runnable retired) {
            if (playerGone) {
                retired.run();
                return;
            }
            String prev = where;
            where = "player";
            r.run();
            where = prev;
        }

        @Override
        void fireEvent(dev.boondock.bsanticheat.api.ViolationEvent event) {
            trace.add("event@" + where);
        }

        @Override
        boolean runConsoleCommand(String command) {
            dispatched.add(command);
            trace.add("cmd:" + command + "@" + where);
            return true;
        }

        @Override
        void doKick(Player player, net.kyori.adventure.text.Component message) {
            kicks.add(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(message));
            trace.add("kick@" + where);
        }

        int dispatchedCount() {
            return dispatched.size();
        }

        String lastCommand() {
            return dispatched.get(dispatched.size() - 1);
        }
    }

    private RecordingViolations vm;

    @BeforeEach
    void setUpPunishments() {
        plugin.getConfig().set("anticheat.punishments.enabled", true);
        // Decay OFF for the tier tests, on purpose. It runs on every flag and subtracts the
        // real time since the last one, so a level is 2.9999 rather than 3.0 unless the
        // violations happen inside the same millisecond — which made these tests pass or fail
        // on how fast the machine was. The decay has its own tests below, where time is the
        // subject rather than an accident.
        plugin.getConfig().set("anticheat.punishments.decay_seconds", 0);
        vm = new RecordingViolations(plugin, config);
    }

    private void tier(int threshold, String... commands) {
        plugin.getConfig().set("anticheat.punishments.tiers", null);
        plugin.getConfig().set("anticheat.punishments.tiers." + threshold, List.of(commands));
    }

    @Test
    @DisplayName("A tier fires when its VL is reached")
    void tierFiresAtThreshold() {
        tier(3, "say %player% hit %check% at %vl%");
        PlayerMock player = player(0.5, 64.0, 0.5);
        for (int i = 0; i < 2; i++) {
            assertEquals(0, countSay(), "below the tier nothing may run");
            vm.flag(player, "SPEED");
        }
        vm.flag(player, "SPEED");
        assertEquals(1, countSay(), "the third violation crosses VL 3");
    }

    @Test
    @DisplayName("Placeholders are filled in")
    void placeholdersAreReplaced() {
        tier(1, "say %player% %check% %vl%");
        PlayerMock player = player(0.5, 64.0, 0.5);
        vm.flag(player, "KILLAURA");
        assertEquals(1, vm.dispatchedCount(), "the tier has to run");
        assertTrue(vm.lastCommand().contains(player.getName()), "%player% must be filled in");
        assertTrue(vm.lastCommand().contains("KILLAURA"), "%check% must be filled in");
        assertTrue(vm.lastCommand().endsWith(" 1"), "%vl% must be filled in");
    }

    @Test
    @DisplayName("A tier fires once, not on every later violation")
    void tierDoesNotRepeat() {
        tier(1, "say once");
        PlayerMock player = player(0.5, 64.0, 0.5);
        vm.flag(player, "SPEED");
        int after = vm.dispatchedCount();
        vm.flag(player, "SPEED");
        vm.flag(player, "SPEED");
        assertEquals(after, vm.dispatchedCount(), "crossing happened once; VL 2 and 3 are not tiers");
    }

    @Test
    @DisplayName("VL is tracked per check, so two checks do not add up")
    void vlIsPerCheck() {
        tier(2, "say two");
        PlayerMock player = player(0.5, 64.0, 0.5);
        vm.flag(player, "SPEED");
        vm.flag(player, "REACH");
        assertEquals(0, vm.dispatchedCount(),
                "one violation each is VL 1 twice, not VL 2 — as documented in config.yml");
        vm.flag(player, "SPEED");
        assertEquals(1, vm.dispatchedCount(), "the second SPEED is what reaches the tier");
    }

    @Test
    @DisplayName("Punishments stay off while the master switch is off")
    void masterSwitchIsHonoured() {
        plugin.getConfig().set("anticheat.punishments.enabled", false);
        tier(1, "say nope");
        PlayerMock player = player(0.5, 64.0, 0.5);
        vm.flag(player, "SPEED");
        assertEquals(0, vm.dispatchedCount(), "the master switch has to hold");
    }

    @Test
    @DisplayName("Threads: event and @kick on the player's region, console commands on the global one")
    void stepsRunOnTheirThreads() {
        // Folia: the player may only be touched from their region; the console sender belongs
        // to the global region.
        tier(1, "say %player%", "@kick");
        PlayerMock player = player(0.5, 64.0, 0.5);
        vm.flag(player, "SPEED");
        assertEquals(List.of("event@player", "cmd:say " + player.getName() + "@global", "kick@player"),
                vm.trace, "each step on its own thread, in configured order");
    }

    @Test
    @DisplayName("The event fires before the punishment of the same violation")
    void eventPrecedesPunishment() {
        tier(1, "ban %player%");
        PlayerMock player = player(0.5, 64.0, 0.5);
        vm.flag(player, "FLY");
        assertEquals("event@player", vm.trace.get(0));
        assertEquals("cmd:ban " + player.getName() + "@global", vm.trace.get(1));
    }

    @Test
    @DisplayName("A command after @kick still runs once the player is gone")
    void commandAfterKickStillRuns() {
        // "@kick" then "ban": the kick removes the player, so the entity scheduler retires the
        // follow-up. The ban must not be dropped with it.
        tier(1, "@kick", "ban %player%");
        PlayerMock player = player(0.5, 64.0, 0.5);
        vm.playerGone = true;
        vm.flag(player, "FLY");
        assertEquals(List.of("event@global", "cmd:ban " + player.getName() + "@global"), vm.trace,
                "the event falls back to the global region, the kick is skipped, the ban runs");
    }

    @Test
    @DisplayName("A failing ViolationEvent does not swallow the punishment")
    void eventFailureDoesNotBlockPunishment() {
        // The event is information for other plugins; it must never take the tier with it.
        tier(1, "say still punished");
        RecordingViolations throwing = new RecordingViolations(plugin, config) {
            @Override
            void fireEvent(dev.boondock.bsanticheat.api.ViolationEvent event) {
                throw new IllegalStateException("listener failure");
            }
        };
        PlayerMock player = player(0.5, 64.0, 0.5);
        throwing.flag(player, "SPEED");
        assertEquals(1, throwing.dispatchedCount(), "the punishment must still run");
    }

    @Test
    @DisplayName("The level survives a quit")
    void levelSurvivesQuit() {
        tier(3, "say caught");
        PlayerMock player = player(0.5, 64.0, 0.5);
        vm.flag(player, "SPEED");
        vm.flag(player, "SPEED");
        vm.cleanup(player.getUniqueId());
        assertEquals(2, vm.getViolations(player.getUniqueId(), "SPEED"), "relogging must not reset the VL");
        vm.flag(player, "SPEED");
        assertEquals(1, vm.dispatchedCount(), "the third violation after the relog reaches the tier");
    }

    @Test
    @DisplayName("Fully decayed levels are purged")
    void decayedLevelsArePurged() {
        plugin.getConfig().set("anticheat.punishments.decay_seconds", 1);
        PlayerMock player = player(0.5, 64.0, 0.5);
        vm.flag(player, "SPEED");
        sleep(1100);
        vm.purgeDecayed(System.currentTimeMillis(), true);
        assertFalse(vm.isTracked(player.getUniqueId()), "a level at zero holds no state");
    }

    @Test
    @DisplayName("A tier re-arms once the level fell below half of it, not before")
    void tierRearmsWithHysteresis() {
        // Tier 4: fires at 4, re-arms only below 2. Decay is switched on only for the pauses.
        plugin.getConfig().set("anticheat.punishments.decay_seconds", 0);
        tier(4, "say four");
        PlayerMock player = player(0.5, 64.0, 0.5);
        for (int i = 0; i < 4; i++) vm.flag(player, "SPEED");
        assertEquals(1, vm.dispatchedCount());

        // Down to ~3 (still above half), back up to 4: no second run.
        plugin.getConfig().set("anticheat.punishments.decay_seconds", 1);
        sleep(1000);
        vm.flag(player, "SPEED");
        assertEquals(1, vm.dispatchedCount(), "a dip to just under the tier must not re-arm it");

        // Down below 2, then back up to 4: the tier fires again, although the level never hit 0.
        sleep(2600);
        assertTrue(vm.getViolations(player.getUniqueId(), "SPEED") > 0, "not a full decay");
        plugin.getConfig().set("anticheat.punishments.decay_seconds", 0);
        for (int i = 0; i < 3; i++) vm.flag(player, "SPEED");
        assertEquals(2, vm.dispatchedCount(), "below half the tier it is armed again");
    }

    @Test
    @DisplayName("A tier is armed again once the level has decayed away")
    void tierRearmsAfterFullDecay() {
        plugin.getConfig().set("anticheat.punishments.decay_seconds", 1);
        tier(1, "say again");
        PlayerMock player = player(0.5, 64.0, 0.5);
        vm.flag(player, "SPEED");
        assertEquals(1, vm.dispatchedCount());
        try {
            Thread.sleep(1200); // past decay_seconds, so the level is back to zero
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        vm.flag(player, "SPEED");
        assertEquals(2, vm.dispatchedCount(),
                "a level that decayed away has been served — the tier applies again");
    }

    @Test
    @DisplayName("@kick is handled by the plugin, not dispatched as a command")
    void internalKick() {
        tier(1, "@kick &cGone: %check% at VL %vl%");
        PlayerMock player = player(0.5, 64.0, 0.5);
        vm.flag(player, "SPEED");
        assertEquals(0, vm.dispatchedCount(), "nothing may be handed to the command dispatcher");
        assertEquals(1, vm.kicks.size(), "the plugin kicks the player itself");
        assertEquals("Gone: SPEED at VL 1", vm.kicks.get(0),
                "colour is applied and every placeholder is filled in");
    }

    @Test
    @DisplayName("@kick without a reason uses the translated default")
    void internalKickFallsBackToLanguageFile() {
        tier(1, "@kick");
        vm.setLanguage(lang);
        PlayerMock player = player(0.5, 64.0, 0.5);
        vm.flag(player, "REACH");
        assertEquals(1, vm.kicks.size());
        assertFalse(vm.kicks.get(0).isBlank(), "a kick always has to say something");
        assertFalse(vm.kicks.get(0).contains("%"), "no placeholder may survive into the message");
    }

    @Test
    @DisplayName("@notify reaches admins only, and fills in the language default")
    void internalNotify() {
        tier(1, "@notify");
        vm.setLanguage(lang);
        PlayerMock player = player(0.5, 64.0, 0.5);
        vm.flag(player, "KILLAURA");
        assertEquals(0, vm.dispatchedCount(), "not a console command");
        assertEquals(0, vm.kicks.size(), "and not a kick either");
    }

    @Test
    @DisplayName("Ordinary console commands still run unchanged")
    void ordinaryCommandsStillDispatch() {
        tier(1, "tempban %player% 1d %check%");
        PlayerMock player = player(0.5, 64.0, 0.5);
        vm.flag(player, "FLY");
        assertEquals(1, vm.dispatchedCount(), "anything without an @ prefix is a command");
        assertEquals("tempban " + player.getName() + " 1d FLY", vm.lastCommand());
        assertEquals(0, vm.kicks.size());
    }

    @Test
    @DisplayName("With decay running, the Nth violation reaches level N")
    void decayDoesNotDelayTiers() {
        // Decay runs on every flag and subtracts the elapsed time, so three violations total
        // 2.9999 rather than 3.0 — and truncating would turn that into a 2. Every configured
        // threshold would then fire one violation late, which for a kick tier of 25 means the
        // 26th alert.
        plugin.getConfig().set("anticheat.punishments.decay_seconds", 300);
        tier(3, "say caught");
        PlayerMock player = player(0.5, 64.0, 0.5);
        // A real millisecond between them, or the decay never engages and the test passes
        // whatever the arithmetic does.
        flagSlowly(player, 2);
        assertEquals(0, vm.dispatchedCount(), "two violations are not the tier yet");
        flagSlowly(player, 1);
        assertEquals(1, vm.dispatchedCount(), "the third violation is the third violation");
    }

    @Test
    @DisplayName("A counted violation is visible immediately")
    void freshViolationIsReported() {
        // The other half of the same bug: /bsac info said "no violations" a millisecond
        // after one was counted — precisely when an admin goes to look after an alert.
        plugin.getConfig().set("anticheat.punishments.decay_seconds", 300);
        PlayerMock player = player(0.5, 64.0, 0.5);
        flagSlowly(player, 1);
        sleep(2); // the read is what decays it below the whole number
        assertEquals(1, vm.getViolations(player.getUniqueId(), "SPEED"),
                "the violation just counted has to be readable back");
        assertEquals(1, vm.getAllViolations(player.getUniqueId()).getOrDefault("SPEED", 0),
                "and it has to appear in the summary /bsac info prints");
    }

    @Test
    @DisplayName("VL decays over time")
    void vlDecays() {
        plugin.getConfig().set("anticheat.punishments.decay_seconds", 1);
        PlayerMock player = player(0.5, 64.0, 0.5);
        vm.flag(player, "SPEED");
        vm.flag(player, "SPEED");
        assertEquals(2, vm.getViolations(player.getUniqueId(), "SPEED"));
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertTrue(vm.getViolations(player.getUniqueId(), "SPEED") < 2, "a VL has to decay away");
    }

    @Test
    @DisplayName("An empty tier list is reported, not passed over in silence")
    void emptyTiersAreReported() {
        plugin.getConfig().set("anticheat.punishments.tiers", null);
        PlayerMock player = player(0.5, 64.0, 0.5);
        vm.flag(player, "SPEED");
        assertEquals(0, vm.dispatchedCount());
        assertFalse(config.punishmentTiers().isEmpty() && vm.dispatchedCount() > 0);
    }

    /** Flags with a real gap between them, so the decay actually runs. */
    private void flagSlowly(PlayerMock player, int times) {
        for (int i = 0; i < times; i++) {
            vm.flag(player, "SPEED");
            sleep(2);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int countSay() {
        return vm.dispatchedCount();
    }
}
