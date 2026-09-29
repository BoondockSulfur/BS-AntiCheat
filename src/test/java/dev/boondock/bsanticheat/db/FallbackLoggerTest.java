package dev.boondock.bsanticheat.db;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ordering and flushing of the file the database falls back to during an outage. */
class FallbackLoggerTest {

    @TempDir
    Path tmp;

    private JavaPlugin plugin;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("BSAntiCheat");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static List<String> entries(Path file) throws IOException {
        return Files.readAllLines(file).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#"))
                .map(l -> l.split(" \\| ")[3])
                .toList();
    }

    @Test
    @DisplayName("A failed write keeps the entries, and in their original order")
    void failedWriteKeepsOrder() throws Exception {
        Path file = tmp.resolve("fallback.log");
        // A directory where the file should be makes every write fail.
        Files.createDirectory(file);
        FallbackLogger logger = new FallbackLogger(plugin, file.toString());

        logger.log("t", 1, "first", 1_000L);
        logger.log("t", 1, "second", 2_000L);
        assertThrows(IOException.class, logger::flush);
        assertEquals(2, logger.queued(), "nothing may be lost");

        logger.log("t", 1, "third", 3_000L);
        Files.delete(file);
        logger.flush();

        assertEquals(List.of("first", "second", "third"), entries(file),
                "the failed batch goes back ahead of what was logged meanwhile");
        assertEquals(0, logger.queued());
    }

    @Test
    @DisplayName("A few entries reach the file without shutdown or the size threshold")
    void smallBatchIsFlushedByTheTimer() throws Exception {
        Path file = tmp.resolve("fallback.log");
        FallbackLogger logger = new FallbackLogger(plugin, file.toString());
        logger.start(1);
        logger.log("t", 1, "only", 1_000L);
        assertTrue(logger.queued() < FallbackLogger.FLUSH_THRESHOLD, "fixture check: below the size trigger");

        long deadline = System.currentTimeMillis() + 10_000L;
        while (logger.queued() > 0 && System.currentTimeMillis() < deadline) {
            MockBukkit.getMock().getScheduler().performOneTick();
            Thread.sleep(50);
        }
        assertEquals(0, logger.queued(), "the periodic flush has taken the entry");
        // Waits for a write still in progress; the queue is empty, so it adds nothing itself.
        logger.flush();
        assertEquals(List.of("only"), entries(file), "written by the periodic flush, before any shutdown");
        // No shutdown(): MockBukkit cannot cancel the timer; unmock() stops it.
    }

    @Test
    @DisplayName("Shutdown writes everything still queued")
    void shutdownFlushes() throws Exception {
        Path file = tmp.resolve("sub/fallback.log");
        FallbackLogger logger = new FallbackLogger(plugin, file.toString());
        logger.log("t", 1, "a", 1_000L);
        logger.log("t", 1, "b", 2_000L);
        logger.shutdown();
        assertEquals(List.of("a", "b"), entries(file));
        assertTrue(new File(file.toString()).exists());
    }
}
