package dev.boondock.bsanticheat.anticheat;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * X-Ray detection driven through real block-break sequences.
 *
 * <p>The central pair of scenarios is
 * "cave" versus "tunnel": both produce lots of ore and little stone, but only one of them is
 * knowledge the player should not have had.
 */
class XRayScenarioTest extends ScenarioBase {

    private XRayDetector detector;

    @BeforeEach
    void setUpDetector() {
        detector = new XRayDetector(plugin, config, null, lang);
        detector.setViolationManager(violations);
    }

    /**
     * Fill an area with solid rock so ore can be embedded in it. The extent must cover every
     * coordinate a scenario touches: ore placed outside it sits in open air, counts as
     * visible, and the scenario then proves nothing.
     */
    private void bedrock(Material rock) {
        // Reaches well beyond every coordinate a scenario touches, in all three axes, so no
        // scenario ever mines ore near the edge of the filled region: unfilled space is air,
        // which counts as an open face, and the ore would then be "visible" because of the
        // test harness rather than because of anything the scenario did.
        for (int x = -8; x <= 72; x++) {
            for (int y = 32; y <= 50; y++) {
                for (int z = -8; z <= 24; z++) world.getBlockAt(x, y, z).setType(rock);
            }
        }
    }

    private void bedrock() {
        bedrock(Material.DEEPSLATE);
    }

    private void mine(PlayerMock player, int x, int y, int z, Material type) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(type);
        detector.onBlockBreak(new BlockBreakEvent(block, player));
        block.setType(Material.CAVE_AIR); // the block is gone afterwards
    }

    @Test
    @DisplayName("Diamonds taken from a tunnel through solid rock raise XRAY")
    void hiddenDiamondsAreCaught() {
        bedrock();
        PlayerMock player = player(0.5, 41.0, 0.5);
        // Ten buried diamonds from separate deposits, each reached by digging — no face was
        // open before the player opened it.
        for (int i = 0; i < 12; i++) {
            mine(player, i * 5, 40, 0, Material.DEEPSLATE_DIAMOND_ORE);
        }
        assertTrue(violations.count("XRAY_THRESHOLD") > 0,
                "buried ore from scattered deposits is what X-Ray reveals");
    }

    @Test
    @DisplayName("The same diamonds on open cave walls raise nothing")
    void visibleDiamondsAreIgnored() {
        bedrock();
        PlayerMock player = player(0.5, 41.0, 0.5);
        // A cave system. Every ore has an open face that nobody dug.
        for (int i = 0; i < 12; i++) {
            world.getBlockAt(i * 5, 41, 0).setType(Material.CAVE_AIR); // open above
            mine(player, i * 5, 40, 0, Material.DEEPSLATE_DIAMOND_ORE);
        }
        assertEquals(0, violations.count("XRAY_THRESHOLD"),
                "ore on a cave wall was seen, not located");
    }

    @Test
    @DisplayName("One large vein does not raise XRAY however many blocks it holds")
    void oneVeinIsNotEnough() {
        bedrock();
        PlayerMock player = player(0.5, 41.0, 0.5);
        // A contiguous run of buried diamond — more blocks than the threshold, one deposit.
        for (int i = 0; i < 14; i++) {
            mine(player, i, 40, 0, Material.DEEPSLATE_DIAMOND_ORE);
        }
        assertEquals(0, violations.count("XRAY_THRESHOLD"),
                "a single vein is a lucky find, not knowledge of where ore is");
    }

    @Test
    @DisplayName("A handful of fat veins does not raise XRAY")
    void severalFatVeinsAreNotEnough() {
        // An OP cleans out three deepslate diamond deposits of 7, 5 and 2 blocks inside a
        // minute. Every
        // block counted as hidden — each one is exposed by breaking its neighbour — so the
        // block count reached 14 against a threshold of 10, and the three-deposit gate was
        // satisfied by there being three of them. Three lucky veins is not knowledge of where
        // ore is; ten scattered finds (the test above) is.
        bedrock();
        PlayerMock player = player(0.5, 41.0, 0.5);

        int[][] veinA = {{0,0,2},{1,0,2},{1,1,2},{1,1,1},{1,1,0},{1,2,1},{0,1,0}}; // 7 blocks
        int[][] veinB = {{0,0,0},{0,0,1},{0,1,0},{1,0,0},{1,0,1}};                 // 5 blocks
        int[][] veinC = {{0,0,0},{0,1,0}};                                         // 2 blocks
        // Smallest deposit first, so the third one is reached while the block count is at the
        // threshold rather than far past it. Both checks are then genuinely on the line: with
        // the cap removed the per-ore threshold trips on the tenth block, and the combined
        // rare count on the twelfth.
        for (int[] o : veinC) mine(player, 40 + o[0], 39 + o[1], o[2], Material.DEEPSLATE_DIAMOND_ORE);
        for (int[] o : veinA) mine(player, o[0], 39 + o[1], o[2], Material.DEEPSLATE_DIAMOND_ORE);
        for (int[] o : veinB) mine(player, 20 + o[0], 39 + o[1], o[2], Material.DEEPSLATE_DIAMOND_ORE);

        assertEquals(0, violations.count("XRAY_THRESHOLD"),
                "14 blocks out of three deposits is three finds, not fourteen");
        assertEquals(0, violations.count("XRAY_RARE_ORES"),
                "the combined rare count is a raw count too and must be capped the same way");
    }

    @Test
    @DisplayName("Enough separate deposits still raise XRAY even when each is fat")
    void manyFatVeinsStillFlag() {
        // The other half: the cap must not become a hiding place. Five deposits of three
        // blocks each is five separate finds, which is what the threshold is counting.
        bedrock();
        PlayerMock player = player(0.5, 41.0, 0.5);
        for (int v = 0; v < 6; v++) {
            for (int b = 0; b < 3; b++) {
                mine(player, v * 10, 39 + b, 0, Material.DEEPSLATE_DIAMOND_ORE);
            }
        }
        assertTrue(violations.count("XRAY_THRESHOLD") > 0,
                "six separate deposits is knowledge of where ore is");
    }

    /**
     * Take the "searching" exemption out of the picture so the SHAPE veto is what decides.
     *
     * <p>Both cannot be exercised at once in a test: the profile needs 300 stone breaks
     * before it says anything, while 60 inside the ratio window already make the player
     * "searching", and MockBukkit mines everything in the same millisecond. On a real server
     * the two windows are 300s and 60s wide, and the gap between them is exactly the case
     * these vetoes exist for — a player who dug for minutes and then spent one minute pulling
     * ore out of what they had exposed.
     */
    private void onlyShapeDecides() {
        plugin.getConfig().set("anticheat.xray_min_stone_for_ratio", 1_000_000);
    }

    /** Dig a branch mine on one level: parallel corridors, the honest strip-miner profile. */
    private void branchMine(PlayerMock player, int level, int branches, int length) {
        for (int b = 0; b < branches; b++) {
            for (int x = 0; x < length; x++) {
                mine(player, x, level, b * 3, Material.DEEPSLATE);
            }
        }
    }

    @Test
    @DisplayName("A strip miner is vetoed even with scattered hidden diamonds")
    void stripMiningProfileVetoes() {
        // The exonerating counterpart of the fat-veins case. This player would be flagged by the
        // raw count — twelve buried diamonds from twelve separate deposits — but the shape of
        // their digging is a branch mine on one level, and the ore comes out of that level.
        bedrock();
        onlyShapeDecides();
        PlayerMock player = player(0.5, 41.0, 0.5);
        branchMine(player, 43, 6, 60);
        for (int i = 0; i < 12; i++) {
            mine(player, i * 5, 42, 18, Material.DEEPSLATE_DIAMOND_ORE);
        }
        assertEquals(0, violations.count("XRAY_THRESHOLD"),
                "a branch mine on one level is what honest strip mining looks like");
    }

    @Test
    @DisplayName("The same digging does NOT veto ore taken far off the level")
    void oreOffTheLevelIsStillJudged() {
        // The bypass: strip-mine honestly, then collect ore from levels the corridors never
        // reach. The profile still looks perfect, so only the ore-band requirement stops the
        // veto from covering exactly the detours worth hiding.
        bedrock();
        onlyShapeDecides();
        PlayerMock player = player(0.5, 41.0, 0.5);
        branchMine(player, 43, 6, 60);
        for (int i = 0; i < 12; i++) {
            mine(player, i * 5, 38, 18, Material.DEEPSLATE_DIAMOND_ORE); // five levels down
        }
        assertTrue(violations.count("XRAY_THRESHOLD") > 0,
                "ore off the corridor level is not covered by the strip-mining veto");
    }

    @Test
    @DisplayName("Ore found while visibly digging is judged by ratio, not by count")
    void searchingPlayerIsNotCountFlagged() {
        bedrock();
        PlayerMock player = player(0.5, 41.0, 0.5);
        // Move enough stone to count as searching, then find scattered buried diamonds.
        for (int i = 0; i < 80; i++) {
            mine(player, i % 20, 42, i / 20, Material.DEEPSLATE);
        }
        for (int i = 0; i < 12; i++) {
            mine(player, i * 5, 40, 5, Material.DEEPSLATE_DIAMOND_ORE);
        }
        assertEquals(0, violations.count("XRAY_THRESHOLD"),
                "a player shifting that much rock is searching, which X-Ray removes the need for");
    }

    @Test
    @DisplayName("Nether mining counts netherrack as spoil")
    void netherrackCountsAsSpoil() {
        PlayerMock player = player(0.5, 41.0, 0.5);
        bedrock(Material.NETHERRACK);
        // Digging netherrack for debris must count as spoil, or the threshold of 3 fires
        // on its own.
        for (int i = 0; i < 80; i++) {
            mine(player, i % 20, 42, i / 20, Material.NETHERRACK);
        }
        for (int i = 0; i < 5; i++) {
            mine(player, i * 5, 40, 5, Material.ANCIENT_DEBRIS);
        }
        assertEquals(0, violations.count("XRAY_THRESHOLD"),
                "netherrack is spoil, so debris hunting reads as searching");
    }

    @Test
    @DisplayName("One fat vein does not tip the ore-to-stone ratio")
    void ratioCountsDepositsNotBlocks() {
        // 61 stone makes the player "searching"; one buried 10-block diamond vein is one find.
        // Counted per block that was 10/61 = 16% > 15%.
        bedrock();
        PlayerMock player = player(0.5, 41.0, 0.5);
        for (int i = 0; i < 61; i++) {
            mine(player, i % 20, 44, i / 20, Material.DEEPSLATE);
        }
        for (int i = 0; i < 10; i++) {
            mine(player, i, 38, 12, Material.DEEPSLATE_DIAMOND_ORE);
        }
        assertEquals(0, violations.count("XRAY_RATIO"), "one deposit is one payoff");
    }

    @Test
    @DisplayName("Scattered finds while digging still trip the ratio")
    void ratioStillFlagsScatteredFinds() {
        bedrock();
        PlayerMock player = player(0.5, 41.0, 0.5);
        for (int i = 0; i < 61; i++) {
            mine(player, i % 20, 44, i / 20, Material.DEEPSLATE);
        }
        for (int i = 0; i < 10; i++) {
            mine(player, i * 5, 38, 12, Material.DEEPSLATE_DIAMOND_ORE);
        }
        assertTrue(violations.count("XRAY_RATIO") > 0, "ten separate deposits per 61 stone is not luck");
    }

    @Test
    @DisplayName("A tunnel dug by an exempt account does not make the ore visible")
    void exemptAccountTunnelStillCountsAsFresh() {
        // Two-account collusion: a creative (or bypassed/Bedrock) account opens the rock, the
        // survival account takes the ore. The exempt account's breaks must still be recorded.
        bedrock();
        PlayerMock digger = player(0.5, 41.0, 0.5);
        digger.setGameMode(org.bukkit.GameMode.CREATIVE);
        PlayerMock collector = player(0.5, 41.0, 0.5);
        for (int i = 0; i < 12; i++) {
            mine(digger, i * 5, 41, 0, Material.DEEPSLATE); // open the face above each ore
            mine(collector, i * 5, 40, 0, Material.DEEPSLATE_DIAMOND_ORE);
        }
        assertTrue(violations.count("XRAY_THRESHOLD") > 0,
                "a face opened moments ago by anyone is not an open cave wall");
    }

    @Test
    @DisplayName("Remembered breaks are capped per player and never skip another player")
    void breakMemoryIsBoundedPerPlayer() {
        long now = System.currentTimeMillis();
        java.util.UUID heavy = java.util.UUID.randomUUID();
        int cap = config.xrayTimewindowSeconds() * 20;
        for (int i = 0; i < cap + 500; i++) {
            detector.recordBreak(heavy, "scenario:" + i + ":40:0", now);
        }
        assertEquals(cap, detector.recentlyBrokenSize(), "the heavy breaker only displaces their own oldest");
        detector.recordBreak(java.util.UUID.randomUUID(), "scenario:0:0:0", now);
        assertEquals(cap + 1, detector.recentlyBrokenSize(), "another player's break is still recorded");
    }

    @Test
    @DisplayName("Expired breaks are evicted by time")
    void breakMemoryExpires() {
        java.util.UUID id = java.util.UUID.randomUUID();
        long old = System.currentTimeMillis() - (config.xrayTimewindowSeconds() + 5) * 1000L;
        detector.recordBreak(id, "scenario:1:40:0", old);
        detector.recordBreak(id, "scenario:2:40:0", System.currentTimeMillis());
        assertEquals(1, detector.recentlyBrokenSize(), "the stale entry leaves when the next one arrives");
    }

    /** Make the scenario world a restricted one, the way an admin would: edit, reload. */
    private void restrictScenarioWorld() throws Exception {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "config.yml");
        java.nio.file.Files.writeString(file.toPath(), java.nio.file.Files.readString(file.toPath())
                .replace("restricted_worlds: []", "restricted_worlds: [scenario]"));
        config.reload();
        assertTrue(config.isRestrictedWorld("scenario"), "fixture check: the world is restricted");
    }

    @Test
    @DisplayName("Restricted world: common ores are not watched by default")
    void restrictedWorldIgnoresCommonOres() throws Exception {
        restrictScenarioWorld();
        bedrock();
        PlayerMock player = player(0.5, 41.0, 0.5);
        for (int i = 0; i < 20; i++) mine(player, i, 40, 0, Material.DEEPSLATE_COPPER_ORE);
        for (int i = 0; i < 10; i++) mine(player, i, 40, 6, Material.COAL_ORE);
        assertEquals(0, violations.count("RESTRICTED_ZONE"), "coal and copper veins are not evidence");
    }

    @Test
    @DisplayName("Restricted world: one alert per deposit, none for visible ore")
    void restrictedWorldCountsDeposits() throws Exception {
        restrictScenarioWorld();
        bedrock();
        PlayerMock player = player(0.5, 41.0, 0.5);
        for (int i = 0; i < 5; i++) mine(player, i, 40, 0, Material.DEEPSLATE_DIAMOND_ORE);
        assertEquals(1, violations.count("RESTRICTED_ZONE"), "a five-block vein is one find");

        mine(player, 30, 40, 0, Material.DEEPSLATE_DIAMOND_ORE);
        assertEquals(2, violations.count("RESTRICTED_ZONE"), "a separate deposit is a new find");

        world.getBlockAt(50, 41, 0).setType(Material.CAVE_AIR); // open cave wall
        mine(player, 50, 40, 0, Material.DEEPSLATE_DIAMOND_ORE);
        assertEquals(2, violations.count("RESTRICTED_ZONE"), "ore on view was seen, not located");
    }

    @Test
    @DisplayName("Self-placed ore is ignored entirely")
    void selfPlacedOreIsIgnored() {
        bedrock();
        PlayerMock player = player(0.5, 41.0, 0.5);
        // An ore tower the player built themselves, then took down.
        for (int i = 0; i < 14; i++) {
            Block block = world.getBlockAt(0, 60 + i, 0);
            block.setType(Material.DEEPSLATE_DIAMOND_ORE);
            detector.onBlockPlace(new org.bukkit.event.block.BlockPlaceEvent(
                    block, block.getState(), world.getBlockAt(0, 59 + i, 0),
                    player.getInventory().getItemInMainHand(), player, true,
                    org.bukkit.inventory.EquipmentSlot.HAND));
        }
        for (int i = 0; i < 14; i++) {
            mine(player, 0, 60 + i, 0, Material.DEEPSLATE_DIAMOND_ORE);
        }
        assertEquals(0, violations.count("XRAY_THRESHOLD"),
                "breaking your own decoration is not mining");
    }
}
