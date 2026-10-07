package dev.boondock.bsanticheat.anticheat;

import dev.boondock.bsanticheat.config.PluginConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NoFall: the server measures the fall itself and expects fall damage after a landing well
 * past the safe distance. A vanilla fall distance reset in mid-air is the other half.
 *
 * <p>Falls are driven tick by tick from a one-block platform down onto a floor, with vanilla
 * gravity, so the free-fall and hover checks see an ordinary fall.
 */
class NoFallTest extends ScenarioBase {

    private MovementChecker checker;
    private static final int FLOOR_Y = 60;

    @BeforeEach
    void setUpChecker() {
        plugin.getConfig().set("anticheat.nofall_detection", true);
        config = new PluginConfig(plugin);
        checker = new MovementChecker(plugin, config, null, lang);
        checker.setViolationManager(violations);
        checker.setPistonTracker(new PistonTracker());
        floor(FLOOR_Y, Material.STONE);
    }

    private enum Claim { VANILLA, RESET_MID_AIR }

    private void move(PlayerMock player, Location from, Location to) {
        player.teleport(from);
        checker.onPlayerMove(new PlayerMoveEvent(player, from, to));
    }

    /**
     * Stand on a platform {@code height} blocks above the floor, step off and fall onto the
     * floor (or into whatever lies there). Returns where the player came to rest.
     */
    private Location fall(PlayerMock player, int height, Claim claim) throws InterruptedException {
        return fall(player, height, claim, null);
    }

    /** As above; {@code midFall} runs a few ticks into the fall. */
    private Location fall(PlayerMock player, int height, Claim claim, Runnable midFall) throws InterruptedException {
        int platformY = FLOOR_Y + height;
        setBlock(0, platformY, 0, Material.STONE);
        Location top = loc(0.5, platformY + 1, 0.5);
        checker.onPlayerTeleport(new PlayerTeleportEvent(player, player.getLocation(), top));
        player.teleport(top);
        clearGrace();
        // Two steps on the platform: a baseline, then an ordinary landing the server sees.
        Location a = loc(0.6, platformY + 1, 0.5);
        Location b = loc(0.7, platformY + 1, 0.5);
        move(player, top, a);
        Thread.sleep(50);
        move(player, a, b);
        Thread.sleep(50);
        // Off the edge, clear of the platform's footprint.
        Location prev = loc(1.5, platformY + 1, 0.5);
        move(player, b, prev);
        Thread.sleep(50);
        double v = 0.0;
        double y = prev.getY();
        double fallen = 0.0;
        double rest = FLOOR_Y + 1;
        int tick = 0;
        while (y > rest) {
            if (++tick == 5 && midFall != null) midFall.run();
            v = (v - 0.08) * 0.98;
            double next = Math.max(rest, y + v);
            fallen += y - next;
            y = next;
            player.setFallDistance(claim == Claim.VANILLA ? (float) fallen : 0f);
            Location to = loc(1.5, y, 0.5);
            move(player, prev, to);
            prev = to;
            Thread.sleep(50);
        }
        setBlock(0, platformY, 0, Material.AIR);
        return prev;
    }

    @SuppressWarnings("deprecation")
    private void fallDamage(PlayerMock player, boolean cancelled) {
        EntityDamageEvent event = new EntityDamageEvent(player, EntityDamageEvent.DamageCause.FALL, 6.0);
        event.setCancelled(cancelled);
        checker.onFallDamage(event);
    }

    private void resolve(PlayerMock player) {
        checker.resolvePendingLanding(player, System.currentTimeMillis() + 1000);
    }

    @Test
    @DisplayName("Landing from nine blocks without fall damage, twice, raises NOFALL")
    void landingWithoutDamageIsCaught() throws Exception {
        PlayerMock player = player(0.5, FLOOR_Y + 1, 0.5);
        for (int i = 0; i < 2; i++) {
            fall(player, 9, Claim.VANILLA);
            resolve(player);
        }
        assertEquals(1, violations.count("NOFALL"));
    }

    @Test
    @DisplayName("A single landing without damage does not raise NOFALL yet")
    void oneLandingIsNotEnough() throws Exception {
        PlayerMock player = player(0.5, FLOOR_Y + 1, 0.5);
        fall(player, 9, Claim.VANILLA);
        resolve(player);
        assertEquals(0, violations.count("NOFALL"));
    }

    @Test
    @DisplayName("Landing with fall damage raises nothing")
    void landingWithDamageIsQuiet() throws Exception {
        PlayerMock player = player(0.5, FLOOR_Y + 1, 0.5);
        for (int i = 0; i < 3; i++) {
            fall(player, 9, Claim.VANILLA);
            fallDamage(player, false);
            resolve(player);
        }
        assertEquals(0, violations.count("NOFALL"));
    }

    @Test
    @DisplayName("Fall damage cancelled by another plugin still counts as damage")
    void cancelledDamageCounts() throws Exception {
        PlayerMock player = player(0.5, FLOOR_Y + 1, 0.5);
        for (int i = 0; i < 3; i++) {
            fall(player, 9, Claim.VANILLA);
            fallDamage(player, true);
            resolve(player);
        }
        assertEquals(0, violations.count("NOFALL"));
    }

    @Test
    @DisplayName("A short drop within the safe distance raises nothing")
    void shortDropIsQuiet() throws Exception {
        PlayerMock player = player(0.5, FLOOR_Y + 1, 0.5);
        for (int i = 0; i < 3; i++) {
            fall(player, 4, Claim.VANILLA);
            resolve(player);
        }
        assertEquals(0, violations.count("NOFALL"));
    }

    @Test
    @DisplayName("Landing in water raises nothing")
    void waterLandingIsQuiet() throws Exception {
        for (int x = 0; x <= 3; x++) {
            for (int z = -1; z <= 1; z++) {
                setBlock(x, FLOOR_Y + 1, z, Material.WATER);
                setBlock(x, FLOOR_Y + 2, z, Material.WATER);
            }
        }
        PlayerMock player = player(0.5, FLOOR_Y + 3, 0.5);
        for (int i = 0; i < 3; i++) {
            fall(player, 9, Claim.VANILLA);
            resolve(player);
        }
        assertEquals(0, violations.count("NOFALL"));
    }

    @Test
    @DisplayName("Landing on hay raises nothing")
    void hayLandingIsQuiet() throws Exception {
        for (int x = 0; x <= 3; x++) {
            for (int z = -1; z <= 1; z++) setBlock(x, FLOOR_Y, z, Material.HAY_BLOCK);
        }
        PlayerMock player = player(0.5, FLOOR_Y + 1, 0.5);
        for (int i = 0; i < 3; i++) {
            fall(player, 9, Claim.VANILLA);
            resolve(player);
        }
        assertEquals(0, violations.count("NOFALL"));
    }

    @Test
    @DisplayName("Feather Falling IV covers a fall it reduces to little damage")
    void featherFallingSmallFallIsQuiet() throws Exception {
        PlayerMock player = player(0.5, FLOOR_Y + 1, 0.5);
        ItemStack boots = new ItemStack(Material.DIAMOND_BOOTS);
        boots.addUnsafeEnchantment(Enchantment.FEATHER_FALLING, 4);
        player.getInventory().setBoots(boots);
        for (int i = 0; i < 3; i++) {
            fall(player, 8, Claim.VANILLA);
            resolve(player);
        }
        assertEquals(0, violations.count("NOFALL"));
    }

    @Test
    @DisplayName("Feather Falling does not excuse a long fall without damage")
    void featherFallingLongFallIsCaught() throws Exception {
        PlayerMock player = player(0.5, FLOOR_Y + 1, 0.5);
        ItemStack boots = new ItemStack(Material.DIAMOND_BOOTS);
        boots.addUnsafeEnchantment(Enchantment.FEATHER_FALLING, 4);
        player.getInventory().setBoots(boots);
        for (int i = 0; i < 2; i++) {
            fall(player, 14, Claim.VANILLA);
            resolve(player);
        }
        assertEquals(1, violations.count("NOFALL"));
    }

    @Test
    @DisplayName("Slow Falling exempts the landing")
    void slowFallingIsQuiet() throws Exception {
        PlayerMock player = player(0.5, FLOOR_Y + 1, 0.5);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 2400, 0));
        for (int i = 0; i < 3; i++) {
            fall(player, 9, Claim.VANILLA);
            resolve(player);
        }
        assertEquals(0, violations.count("NOFALL"));
    }

    @Test
    @DisplayName("A knockback during the fall makes it unjudgeable")
    void knockbackDuringFallIsQuiet() throws Exception {
        PlayerMock player = player(0.5, FLOOR_Y + 1, 0.5);
        // A wind charge mid-fall: an explosion knockback that deals no damage.
        Runnable windCharge = () -> checker.onEntityKnockback(new io.papermc.paper.event.entity.EntityKnockbackEvent(
                player, io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.EXPLOSION,
                new org.bukkit.util.Vector(0, 1, 0)));
        for (int i = 0; i < 3; i++) {
            fall(player, 9, Claim.VANILLA, windCharge);
            resolve(player);
        }
        assertEquals(0, violations.count("NOFALL"));
    }

    @Test
    @DisplayName("fall_damage_multiplier 0 exempts the landing")
    void noFallDamageAttributeIsQuiet() throws Exception {
        PlayerMock player = player(0.5, FLOOR_Y + 1, 0.5);
        var inst = player.getAttribute(org.bukkit.attribute.Attribute.FALL_DAMAGE_MULTIPLIER);
        org.junit.jupiter.api.Assumptions.assumeTrue(inst != null, "attribute not modelled by the mock server");
        inst.setBaseValue(0.0);
        for (int i = 0; i < 3; i++) {
            fall(player, 9, Claim.VANILLA);
            resolve(player);
        }
        assertEquals(0, violations.count("NOFALL"));
    }

    @Test
    @DisplayName("A fall distance reset in mid-air raises NOFALL")
    void midAirResetIsCaught() throws Exception {
        PlayerMock player = player(0.5, FLOOR_Y + 1, 0.5);
        for (int i = 0; i < 2; i++) {
            fall(player, 12, Claim.RESET_MID_AIR);
            // Damage arrived anyway, so only the mid-air reset is evidence.
            fallDamage(player, false);
            resolve(player);
        }
        assertTrue(violations.count("NOFALL") >= 1, "vanilla fall distance stayed at zero while falling");
    }

    @Test
    @DisplayName("Off by default: nothing is judged")
    void offByDefault() throws Exception {
        plugin.getConfig().set("anticheat.nofall_detection", false);
        config = new PluginConfig(plugin);
        checker = new MovementChecker(plugin, config, null, lang);
        checker.setViolationManager(violations);
        PlayerMock player = player(0.5, FLOOR_Y + 1, 0.5);
        for (int i = 0; i < 2; i++) {
            fall(player, 9, Claim.VANILLA);
            resolve(player);
        }
        assertEquals(0, violations.count("NOFALL"));
    }
}
