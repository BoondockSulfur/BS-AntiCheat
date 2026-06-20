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
        String userAgent = String.format(USER_AGENT_FORMAT, currentVersion);

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

        // If no release found, fall back to first version (usually latest)
        if (!versions.isEmpty()) {
            return versions.get(0).getAsJsonObject().get("version_number").getAsString();
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
    private boolean isNewerVersion(String newVersion, String currentVersion) {
        // Simple string comparison first (fast path for exact match)
        if (newVersion.equals(currentVersion)) {
            return false;
        }

        try {
            // Parse semantic versions (MAJOR.MINOR.PATCH)
            String[] newParts = newVersion.split("\\.");
            String[] currentParts = currentVersion.split("\\.");

            // Compare each part (major, minor, patch)
            for (int i = 0; i < Math.max(newParts.length, currentParts.length); i++) {
                int newPart = i < newParts.length ? parseVersionPart(newParts[i]) : 0;
                int currentPart = i < currentParts.length ? parseVersionPart(currentParts[i]) : 0;

                if (newPart > currentPart) {
                    return true;
                } else if (newPart < currentPart) {
                    return false;
                }
            }

            return false; // Versions are equal
        } catch (NumberFormatException e) {
            // Fallback to string comparison if version format is non-standard
            return newVersion.compareTo(currentVersion) > 0;
        }
    }

    /**
     * Parse a single version part, removing any non-numeric suffixes.
     * Examples: "2" -> 2, "1-beta" -> 1, "0-SNAPSHOT" -> 0
     */
    private int parseVersionPart(String part) {
        // Remove any non-numeric suffixes (e.g., "1-beta", "2-SNAPSHOT")
        String numericPart = part.split("-")[0];
        return Integer.parseInt(numericPart);
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
