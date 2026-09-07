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
        Player lastRegion;

        RecordingViolations(org.bukkit.plugin.java.JavaPlugin plugin,
                            dev.boondock.bsanticheat.config.PluginConfig config) {
            super(plugin, config);
        }

        @Override
        void runOnGlobalRegion(Runnable r) {
            r.run();
        }

        @Override
        void runForPlayerRegion(Player player, Runnable r) {
            lastRegion = player;
            r.run();
        }

        @Override
        boolean runConsoleCommand(String command) {
            dispatched.add(command);
            return true;
        }

        @Override
        void doKick(Player player, net.kyori.adventure.text.Component message) {
            kicks.add(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(message));
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
    @DisplayName("A punishment command runs on the target's own region")
    void punishmentRunsOnThePlayersRegion() {
        // A kick reaches into that player's state, and on Folia an entity may only be touched
        // from the region that owns it. The global region is not that region.
        tier(1, "say %player%");
        PlayerMock player = player(0.5, 64.0, 0.5);
        vm.flag(player, "SPEED");
        assertEquals(player, vm.lastRegion, "the command belongs on the player's region thread");
    }

    @Test
    @DisplayName("A failing ViolationEvent does not swallow the punishment")
    void eventFailureDoesNotBlockPunishment() {
        // The event is information for other plugins; it used to sit on the punishment's
        // critical path, so anything that made it throw took the tier below it with it.
        tier(1, "say still punished");
        RecordingViolations throwing = new RecordingViolations(plugin, config) {
            @Override
            void runOnGlobalRegion(Runnable r) {
                throw new IllegalStateException("no region scheduler");
            }
        };
        PlayerMock player = player(0.5, 64.0, 0.5);
        throwing.flag(player, "SPEED");
        assertEquals(1, throwing.dispatchedCount(), "the punishment must still run");
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
        // Live on mc-test 2026-09-07: tier 2 needed three flags and tier 3 needed four.
        // Decay runs on every flag and subtracts the elapsed time, so three violations total
        // 2.9999 rather than 3.0 — and truncating turned that into a 2. Every configured
        // threshold therefore fired one violation late, which for a kick tier of 25 means the
        // 26th alert.
        plugin.getConfig().set("anticheat.punishments.decay_seconds", 300);
        tier(3, "say caught");
        PlayerMock player = player(0.5, 64.0, 0.5);
        // A real millisecond between them, or the decay never engages and the test passes
        // whatever the arithmetic does — which is how it first passed against the bug.
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
