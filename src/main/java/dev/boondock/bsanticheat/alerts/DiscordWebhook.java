package dev.boondock.bsanticheat.alerts;

import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.lang.LanguageManager;
import dev.boondock.bsanticheat.util.Constants;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Discord Webhook integration for sending anticheat alerts.
 *
 * <p>Queueing and rate limiting live in {@link WebhookChannel}, shared per webhook URL, so
 * every instance of this class sending to the same URL stays within one limit.
 */
public class DiscordWebhook {

    private final Plugin plugin;
    private final PluginConfig config;
    private final LanguageManager lang;

    public DiscordWebhook(Plugin plugin, PluginConfig config, LanguageManager lang) {
        this.plugin = plugin;
        this.config = config;
        this.lang = lang;
    }

    /**
     * Alert types for BSAntiCheat webhook notifications.
     */
    public enum AlertType {
        XRAY,
        MOVEMENT
    }

    private boolean isValidDiscordWebhookUrl(String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isEmpty()) return false;
        try {
            URL url = new URL(webhookUrl);
            if (!"https".equalsIgnoreCase(url.getProtocol())) return false;
            String host = url.getHost().toLowerCase();
            if (!host.equals("discord.com") && !host.equals("discordapp.com") &&
                !host.endsWith(".discord.com") && !host.endsWith(".discordapp.com")) return false;
            return url.getPath().startsWith("/api/webhooks/");
        } catch (MalformedURLException e) {
            return false;
        }
    }

    public void sendAlert(AlertType type, String title, String description, double value) {
        if (!config.discordEnabled()) return;

        String webhookUrl = config.discordWebhookUrl();
        if (webhookUrl == null || webhookUrl.isEmpty()) return;

        if (!isValidDiscordWebhookUrl(webhookUrl)) {
            plugin.getLogger().severe("[Discord] Invalid webhook URL detected!");
            return;
        }

        String typeKey = type.name().toLowerCase();
        if (!config.discordAlertType(typeKey)) return;

        String json = buildEmbedJson(type, title, description, value);

        WebhookChannel channel = WebhookChannel.forUrl(webhookUrl, Constants.DISCORD_MAX_QUEUE_SIZE,
                this::sendWebhookSync, plugin.getLogger());
        // The drain sleeps between requests, so it runs on an async thread, never on a
        // region or Netty thread.
        channel.enqueue(json, task -> dev.boondock.bsanticheat.util.Scheduler.runAsync(plugin, task));
    }

    private String buildEmbedJson(AlertType type, String title, String description, double value) {
        int color = getColorForType(type);
        String timestamp = Instant.now().toString();
        String valueLabel = lang.get("alert.discord_value");
        return String.format(java.util.Locale.ROOT, """
            {
              "embeds": [{
                "title": "%s",
                "description": "%s\\n\\n**%s:** %.2f",
                "color": %d,
                "footer": {"text": "BSAntiCheat"},
                "timestamp": "%s"
              }]
            }
            """, escapeJson(title), escapeJson(description), escapeJson(valueLabel), value, color, timestamp);
    }

    private int getColorForType(AlertType type) {
        return switch (type) {
            case XRAY -> 0xFFFF55;
            case MOVEMENT -> 0xFF5555;
        };
    }

    private WebhookChannel.Response sendWebhookSync(String webhookUrl, String json) throws IOException {
        URL url = new URL(webhookUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "BSAntiCheat/" + plugin.getDescription().getVersion());
            // Without timeouts a hanging Discord/network connection blocks the single
            // queue-processor thread forever and all further alerts are dropped.
            connection.setConnectTimeout(Constants.DISCORD_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(Constants.DISCORD_READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int responseCode = connection.getResponseCode();
            return new WebhookChannel.Response(responseCode, retryAfterMs(
                    responseCode,
                    connection.getHeaderField("Retry-After"),
                    connection.getHeaderField("X-RateLimit-Remaining"),
                    connection.getHeaderField("X-RateLimit-Reset-After")));
        } finally {
            connection.disconnect();
        }
    }

    /**
     * How long Discord asks us to wait, in ms, or -1. A 429 carries {@code Retry-After}
     * (seconds, possibly fractional); a success that used up the bucket carries
     * {@code X-RateLimit-Remaining: 0} and {@code X-RateLimit-Reset-After}.
     */
    static long retryAfterMs(int status, String retryAfter, String remaining, String resetAfter) {
        if (status == 429) {
            long ms = secondsToMs(retryAfter);
            if (ms < 0) ms = secondsToMs(resetAfter);
            // A 429 without a usable header still has to back off.
            return ms >= 0 ? ms : 5000L;
        }
        if ("0".equals(remaining == null ? null : remaining.trim())) {
            return secondsToMs(resetAfter);
        }
        return -1;
    }

    private static long secondsToMs(String seconds) {
        if (seconds == null || seconds.isBlank()) return -1;
        try {
            double s = Double.parseDouble(seconds.trim());
            return s >= 0 && !Double.isNaN(s) ? (long) Math.ceil(s * 1000.0) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
            .replaceAll("[\u0000-\u001F]", "");
    }

}
