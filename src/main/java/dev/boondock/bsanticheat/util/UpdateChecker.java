package dev.boondock.bsanticheat.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Update checker for Modrinth.
 * Checks asynchronously for new plugin versions on server start.
 *
 * Features:
 * - Uses Modrinth API v2 (no API key required)
 * - Proper HTTP connection management
 * - Semantic version comparison
 * - Asynchronous operation (non-blocking)
 */
public class UpdateChecker {

    // Constants
    private static final String MODRINTH_API_BASE = "https://api.modrinth.com/v2";
    private static final String PROJECT_SLUG = "bs-anticheat";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final String USER_AGENT_FORMAT = "BSAntiCheat/%s (Modrinth Update Checker)";

    private final JavaPlugin plugin;
    private final String currentVersion;

    public UpdateChecker(JavaPlugin plugin) {
        this.plugin = plugin;
        this.currentVersion = plugin.getDescription().getVersion();
    }

    /**
     * Check for updates asynchronously.
     * @return CompletableFuture with update result
     */
    public CompletableFuture<UpdateResult> checkForUpdates() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return checkModrinth();
            } catch (IOException e) {
                plugin.getLogger().warning("[UpdateChecker] Network error: " + e.getMessage());
                return new UpdateResult(false, currentVersion, "Network error: " + e.getMessage());
            } catch (JsonSyntaxException e) {
                plugin.getLogger().warning("[UpdateChecker] Invalid JSON response: " + e.getMessage());
                return new UpdateResult(false, currentVersion, "Invalid API response");
            } catch (Exception e) {
                plugin.getLogger().warning("[UpdateChecker] Unexpected error: " + e.getMessage());
                return new UpdateResult(false, currentVersion, "Check failed: " + e.getMessage());
            }
        });
    }

    /**
     * Check for updates on Modrinth.
     * API Documentation: https://docs.modrinth.com/api-spec/
     *
     * @return Update result with version information
     * @throws IOException if network error occurs
     * @throws JsonSyntaxException if JSON parsing fails
     */
    private UpdateResult checkModrinth() throws IOException, JsonSyntaxException {
        String apiUrl = MODRINTH_API_BASE + "/project/" + PROJECT_SLUG + "/version";
        String userAgent = String.format(java.util.Locale.ROOT, USER_AGENT_FORMAT, currentVersion);

        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            // Configure connection
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return new UpdateResult(false, currentVersion,
                    "Modrinth API returned HTTP " + responseCode);
            }

            // Read response with try-with-resources
            String responseBody;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                responseBody = response.toString();
            }

            // Parse JSON response
            JsonArray versions = JsonParser.parseString(responseBody).getAsJsonArray();
            if (versions.isEmpty()) {
                return new UpdateResult(false, currentVersion, "No versions found on Modrinth");
            }

            // Find latest stable release version
            String latestVersionNumber = findLatestVersion(versions);

            if (latestVersionNumber == null) {
                return new UpdateResult(false, currentVersion, "No stable releases found");
            }

            // Compare versions
            boolean updateAvailable = isNewerVersion(latestVersionNumber, currentVersion);

            return new UpdateResult(
                updateAvailable,
                latestVersionNumber,
                updateAvailable ? "Update available on Modrinth" : "Up to date"
            );

        } finally {
            // Always disconnect
            conn.disconnect();
        }
    }

    /**
     * Find the latest stable release version from Modrinth versions array.
     * Filters out beta/alpha versions and returns the most recent release.
     *
     * @param versions JsonArray of version objects from Modrinth API
     * @return Latest stable version number, or null if none found
     */
    private String findLatestVersion(JsonArray versions) {
        for (int i = 0; i < versions.size(); i++) {
            JsonObject version = versions.get(i).getAsJsonObject();

            // Skip entries missing required fields
            if (!version.has("version_number") || version.get("version_number").isJsonNull()) {
                continue;
            }

            // Check if this is a release version (not beta/alpha)
            String versionType = version.has("version_type")
                ? version.get("version_type").getAsString()
                : "release";

            if ("release".equalsIgnoreCase(versionType)) {
                return version.get("version_number").getAsString();
            }
        }

        // If no release found, fall back to the first version that actually carries a
        // number (usually the latest). The same guard as in the loop above: without it this
        // line dereferences a missing field on exactly the malformed entry the loop skipped,
        // and the NPE turns a "no stable release" into a generic "check failed".
        for (int i = 0; i < versions.size(); i++) {
            JsonObject version = versions.get(i).getAsJsonObject();
            if (version.has("version_number") && !version.get("version_number").isJsonNull()) {
                return version.get("version_number").getAsString();
            }
        }

        return null;
    }

    /**
     * Compare two semantic versions.
     * Returns true if newVersion is newer than currentVersion.
     *
     * @param newVersion The new version string (e.g., "2.1.0")
     * @param currentVersion The current version string (e.g., "2.0.0")
     * @return true if newVersion > currentVersion
     */
    static boolean isNewerVersion(String newVersion, String currentVersion) {
        if (newVersion == null || currentVersion == null) return false;
        Parsed n = Parsed.of(newVersion);
        Parsed c = Parsed.of(currentVersion);
        if (n == null || c == null) {
            // Not a version number at all: never announce an update on a guess.
            return false;
        }
        int len = Math.max(n.numbers.length, c.numbers.length);
        for (int i = 0; i < len; i++) {
            long a = i < n.numbers.length ? n.numbers[i] : 0;
            long b = i < c.numbers.length ? c.numbers[i] : 0;
            if (a != b) return a > b;
        }
        // Same numbers: a release is newer than a pre-release of it (1.0.7 > 1.0.7-SNAPSHOT),
        // never the other way round.
        return !n.preRelease && c.preRelease;
    }

    /**
     * A version string reduced to its numeric core. Accepts a leading {@code v}/{@code V}
     * (tags such as "v1.0.7"), ignores build metadata after {@code +} and records whether
     * a pre-release suffix ({@code -SNAPSHOT}, {@code -beta.2}) was present.
     */
    private record Parsed(long[] numbers, boolean preRelease) {
        static Parsed of(String raw) {
            String s = raw.trim();
            if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
            int plus = s.indexOf('+');
            if (plus >= 0) s = s.substring(0, plus);
            boolean pre = false;
            int dash = s.indexOf('-');
            if (dash >= 0) {
                pre = true;
                s = s.substring(0, dash);
            }
            if (s.isEmpty()) return null;
            String[] parts = s.split("\\.");
            long[] numbers = new long[parts.length];
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].isEmpty() || !parts[i].chars().allMatch(Character::isDigit)) return null;
                try {
                    numbers[i] = Long.parseLong(parts[i]);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return new Parsed(numbers, pre);
        }
    }

    /**
     * Result of update check.
     */
    public static class UpdateResult {
        private final boolean updateAvailable;
        private final String latestVersion;
        private final String message;

        public UpdateResult(boolean updateAvailable, String latestVersion, String message) {
            this.updateAvailable = updateAvailable;
            this.latestVersion = latestVersion;
            this.message = message;
        }

        public boolean isUpdateAvailable() {
            return updateAvailable;
        }

        public String getLatestVersion() {
            return latestVersion;
        }

        public String getMessage() {
            return message;
        }
    }
}
