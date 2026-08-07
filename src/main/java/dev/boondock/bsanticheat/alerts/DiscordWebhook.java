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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Discord Webhook integration for sending anticheat alerts.
 */
public class DiscordWebhook {

    private final Plugin plugin;
    private final PluginConfig config;
    private final LanguageManager lang;

    private final AtomicLong lastRequestTime = new AtomicLong(0);
    private final ConcurrentLinkedQueue<WebhookRequest> requestQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean processorRunning = false;

    public DiscordWebhook(Plugin plugin, PluginConfig config, LanguageManager lang) {
        this.plugin = plugin;
        this.config = config;
        this.lang = lang;
    }

    /**
     * Alert types for BSAntiCheat webhook notifications.
     */
    public enum AlertType {
        XRAY("XRay Detection"),
        MOVEMENT("Movement Detection");

        private final String displayName;

        AlertType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
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

        if (requestQueue.size() >= Constants.DISCORD_MAX_QUEUE_SIZE) {
            plugin.getLogger().warning("[Discord] Alert queue full, dropping: " + title);
            return;
        }

        requestQueue.offer(new WebhookRequest(webhookUrl, json));
        startQueueProcessor();
    }

    private synchronized void startQueueProcessor() {
        if (processorRunning) return;
        processorRunning = true;
        dev.boondock.bsanticheat.util.Scheduler.runAsync(plugin, this::processQueue);
    }

    private void processQueue() {
        try {
            while (!requestQueue.isEmpty()) {
                WebhookRequest request = requestQueue.poll();
                if (request == null) break;

                long now = System.currentTimeMillis();
                long timeSince = now - lastRequestTime.get();
                if (timeSince < Constants.DISCORD_MIN_REQUEST_DELAY_MS) {
                    try {
                        Thread.sleep(Constants.DISCORD_MIN_REQUEST_DELAY_MS - timeSince);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                try {
                    sendWebhookSync(request.webhookUrl(), request.json());
                    lastRequestTime.set(System.currentTimeMillis());
                } catch (IOException e) {
                    plugin.getLogger().warning("[Discord] Webhook failed: " + e.getMessage());
                }
            }
        } finally {
            // Clearing the flag and the re-check must both happen under the same lock that
            // startQueueProcessor() takes, otherwise an alert queued in between is seen by
            // neither the running processor nor a new one — and sits there until the next
            // alert arrives. The finally also guarantees the flag is cleared on a throw,
            // which would otherwise wedge the queue permanently.
            finishQueueProcessor();
        }
    }

    /** Release the processor slot and restart it if work arrived while we were finishing. */
    private synchronized void finishQueueProcessor() {
        processorRunning = false;
        if (!requestQueue.isEmpty()) {
            processorRunning = true;
            dev.boondock.bsanticheat.util.Scheduler.runAsync(plugin, this::processQueue);
        }
    }

    private String buildEmbedJson(AlertType type, String title, String description, double value) {
        int color = getColorForType(type);
        String timestamp = Instant.now().toString();
        String valueLabel = lang.get("alert.discord_value");
        return String.format("""
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

    private void sendWebhookSync(String webhookUrl, String json) throws IOException {
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
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("Discord API returned status " + responseCode);
            }
        } finally {
            connection.disconnect();
        }
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
            .replaceAll("[\u0000-\u001F]", "");
    }

    private record WebhookRequest(String webhookUrl, String json) {}
}
