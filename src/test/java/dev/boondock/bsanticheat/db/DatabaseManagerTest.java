package dev.boondock.bsanticheat.db;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.util.Constants;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Log rows are tied to the player's UUID; deleting goes by UUID, not by a reusable name. */
class DatabaseManagerTest {

    @TempDir
    Path tmp;

    private ServerMock server;
    private DatabaseManager db;
    private Path dbFile;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin("BSAntiCheat");
        dbFile = tmp.resolve("anticheat.db");
        plugin.getConfig().set("database.sqlite_file", dbFile.toString());
        plugin.getConfig().set("database.fallback_file_logging", false);
        db = new DatabaseManager(plugin, new PluginConfig(plugin));
    }

    @AfterEach
    void tearDown() {
        try {
            // MockBukkit cannot cancel async tasks; the pool is still closed by shutdown().
            if (db != null) db.shutdown();
        } catch (org.mockbukkit.mockbukkit.exception.UnimplementedOperationException expected) {
            // see above
        } finally {
            MockBukkit.unmock();
        }
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile);
    }

    private int count(String where) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM anticheat_logs WHERE " + where)) {
            return rs.getInt(1);
        }
    }

    @Test
    @DisplayName("Rows carry the UUID they were logged with, never one guessed from the text")
    void uuidComesFromTheCaller() throws Exception {
        db.init();
        PlayerMock steve = server.addPlayer("Steve");
        server.addPlayer("XRay");
        db.logAsync(steve.getUniqueId(), "anticheat_xray", 1.0, "[XRay?] Steve: threshold exceeded");
        db.logAsync("anticheat_system", 1.0, "XRay: no player attached");
        db.flushNow();

        assertEquals(1, count("player_uuid = '" + steve.getUniqueId() + "' AND log_type = 'anticheat_xray'"));
        assertEquals(1, count("player_uuid IS NULL AND log_type = 'anticheat_system'"),
                "a player-less row is not attributed to a player whose name appears in it");
    }

    @Test
    @DisplayName("clear --db deletes by UUID: renamed rows go, a namesake's rows stay")
    void deleteGoesByUuid() throws Exception {
        UUID current = UUID.randomUUID();
        UUID formerHolder = UUID.randomUUID();
        db.init();
        db.logAsync(current, "anticheat_speed", 1, "OldName: before the rename");
        db.logAsync(current, "anticheat_fly", 1, "Steve: after the rename");
        db.logAsync(formerHolder, "anticheat_speed", 1, "Steve: someone who had the name before");
        db.flushNow();

        int deleted = db.deleteAntiCheatLogs(current, "Steve", "anticheat_speed")
                + db.deleteAntiCheatLogs(current, "Steve", "anticheat_fly");

        assertEquals(2, deleted);
        assertEquals(1, count("player_uuid = '" + formerHolder + "'"), "the namesake keeps their rows");
    }

    @Test
    @DisplayName("Rows from before the UUID column are still matched by name")
    void legacyRowsFallBackToName() throws Exception {
        // A database created by an older version: no player_uuid column yet.
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE anticheat_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "ts TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, log_type VARCHAR(32) NOT NULL, "
                    + "value DOUBLE, description VARCHAR(255))");
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO anticheat_logs (log_type, value, description) VALUES (?, 1, ?)")) {
                for (String[] row : List.of(
                        new String[]{"anticheat_speed", "Steve: legacy"},
                        new String[]{"anticheat_xray", "[XRay?] Steve: legacy xray"},
                        new String[]{"anticheat_speed", "Steven: other player"})) {
                    ps.setString(1, row[0]);
                    ps.setString(2, row[1]);
                    ps.executeUpdate();
                }
            }
        }
        db.init();   // adds the column; must not fail on an existing table
        db.migrateSchema();   // a second start: the column exists, nothing to do

        UUID steve = UUID.randomUUID();
        int deleted = db.deleteAntiCheatLogs(steve, "Steve", Constants.LOG_TYPE_PREFIX);
        assertEquals(2, deleted);
        assertEquals(1, count("description LIKE 'Steven:%'"));
    }
}
