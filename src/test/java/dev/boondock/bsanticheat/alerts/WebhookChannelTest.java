package dev.boondock.bsanticheat.alerts;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rate limiting of the Discord webhook, without a network: a fake sender records when each
 * attempt was made on a fake clock that only moves when the channel sleeps.
 */
class WebhookChannelTest {

    private static final String URL = "https://discord.com/api/webhooks/1/test";

    private long now = 1_000_000L;
    private final List<Long> attemptTimes = new ArrayList<>();
    private final List<String> sentBodies = new ArrayList<>();
    private final Deque<Object> script = new ArrayDeque<>();
    private final List<String> warnings = new ArrayList<>();
    private final Logger logger = Logger.getAnonymousLogger();

    {
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord r) {
                if (r.getLevel() == Level.WARNING) warnings.add(r.getMessage());
            }
            @Override public void flush() {}
            @Override public void close() {}
        });
    }

    @AfterEach
    void reset() {
        WebhookChannel.resetForTests();
    }

    /** Sender that answers from {@link #script} (a Response or an IOException), 204 when empty. */
    private WebhookChannel.Response send(String url, String json) throws IOException {
        attemptTimes.add(now);
        sentBodies.add(json);
        Object next = script.poll();
        if (next instanceof IOException e) throw e;
        if (next instanceof RuntimeException e) throw e;
        if (next instanceof WebhookChannel.Response r) return r;
        return new WebhookChannel.Response(204, -1);
    }

    private WebhookChannel channel(int capacity) {
        return new WebhookChannel(URL, capacity, 2100L, this::send, () -> now, ms -> now += ms, logger);
    }

    /** Queues without running the drain, so the test controls when it happens. */
    private static void enqueueAll(WebhookChannel ch, String... bodies) {
        for (String b : bodies) ch.enqueue(b, task -> {});
    }

    @Test
    @DisplayName("Requests to one URL are spaced by at least 2.1 s (at most ~28 per minute)")
    void spacingBetweenRequests() {
        WebhookChannel ch = channel(50);
        enqueueAll(ch, "a", "b", "c", "d");
        ch.drain();

        assertEquals(List.of("a", "b", "c", "d"), sentBodies);
        for (int i = 1; i < attemptTimes.size(); i++) {
            assertTrue(attemptTimes.get(i) - attemptTimes.get(i - 1) >= 2100,
                    "gap " + i + " was " + (attemptTimes.get(i) - attemptTimes.get(i - 1)));
        }
    }

    @Test
    @DisplayName("A 429 is retried with the same message after Retry-After")
    void rateLimitIsHonoured() {
        script.add(new WebhookChannel.Response(429, 7_500));
        WebhookChannel ch = channel(50);
        enqueueAll(ch, "a", "b");
        ch.drain();

        assertEquals(List.of("a", "a", "b"), sentBodies, "the rejected message is sent again, not skipped");
        assertTrue(attemptTimes.get(1) - attemptTimes.get(0) >= 7_500, "waited for Retry-After");
        assertEquals(0, ch.queued());
    }

    @Test
    @DisplayName("A failed attempt still counts for the spacing")
    void failureDoesNotResetTheLimiter() {
        script.add(new IOException("connection reset"));
        WebhookChannel ch = channel(50);
        enqueueAll(ch, "a");
        ch.drain();

        assertEquals(2, attemptTimes.size());
        assertTrue(attemptTimes.get(1) - attemptTimes.get(0) >= 2100,
                "the retry after a failure must not fire immediately");
    }

    @Test
    @DisplayName("Retries are bounded; a message that keeps failing is dropped")
    void retriesAreBounded() {
        for (int i = 0; i < 10; i++) script.add(new WebhookChannel.Response(429, 1000));
        WebhookChannel ch = channel(50);
        enqueueAll(ch, "a");
        ch.drain();

        assertEquals(WebhookChannel.MAX_ATTEMPTS, attemptTimes.size());
        assertEquals(0, ch.queued());
    }

    @Test
    @DisplayName("A permanent client error is not retried")
    void clientErrorIsNotRetried() {
        script.add(new WebhookChannel.Response(404, -1));
        WebhookChannel ch = channel(50);
        enqueueAll(ch, "a", "b");
        ch.drain();

        assertEquals(List.of("a", "b"), sentBodies);
    }

    @Test
    @DisplayName("A full queue drops with one warning, then reports the count once")
    void fullQueueWarnsOnce() {
        WebhookChannel ch = channel(2);
        enqueueAll(ch, "a", "b");
        assertFalse(ch.enqueue("c", task -> {}));
        assertFalse(ch.enqueue("d", task -> {}));
        assertFalse(ch.enqueue("e", task -> {}));
        assertEquals(1, warnings.size(), "one warning for the overflow, not one per message");

        ch.drain();
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(1).contains("3"), "the summary names how many were dropped");
    }

    @Test
    @DisplayName("Only one drain runs; later messages join it")
    void singleDrain() {
        WebhookChannel ch = channel(50);
        List<Runnable> started = new ArrayList<>();
        ch.enqueue("a", started::add);
        ch.enqueue("b", started::add);
        assertEquals(1, started.size());
        started.get(0).run();
        assertEquals(List.of("a", "b"), sentBodies);

        ch.enqueue("c", started::add);
        assertEquals(2, started.size(), "a finished drain is restarted by the next message");
    }

    @Test
    @DisplayName("All senders to one URL share one channel")
    void channelIsSharedPerUrl() {
        WebhookChannel a = WebhookChannel.forUrl(URL, 50, this::send, logger);
        WebhookChannel b = WebhookChannel.forUrl(URL, 50, this::send, logger);
        WebhookChannel other = WebhookChannel.forUrl(URL + "x", 50, this::send, logger);
        assertSame(a, b);
        assertFalse(a == other);
    }

    @Test
    @DisplayName("Rate-limit headers are read in seconds and converted")
    void retryAfterParsing() {
        assertEquals(1500, DiscordWebhook.retryAfterMs(429, "1.5", null, null));
        assertEquals(2000, DiscordWebhook.retryAfterMs(429, null, "0", "2"));
        assertEquals(5000, DiscordWebhook.retryAfterMs(429, null, null, null), "a bare 429 still backs off");
        assertEquals(800, DiscordWebhook.retryAfterMs(204, null, "0", "0.8"), "exhausted bucket on success");
        assertEquals(-1, DiscordWebhook.retryAfterMs(204, null, "4", "0.8"));
    }

    @Test
    @DisplayName("An unchecked sender exception drops that message but not the channel")
    void uncheckedSenderExceptionDoesNotWedgeChannel() {
        WebhookChannel ch = channel(50);
        script.add(new IllegalStateException("boom"));
        enqueueAll(ch, "a", "b");
        ch.drain();
        assertEquals(List.of("a", "b"), sentBodies);
        assertEquals(0, ch.queued());

        // The drain flag was released: a later enqueue starts a new drain.
        List<Runnable> started = new ArrayList<>();
        ch.enqueue("c", started::add);
        assertEquals(1, started.size());
    }

    @Test
    @DisplayName("A refused scheduler does not throw into the caller and does not wedge the channel")
    void schedulerRefusalIsContained() {
        WebhookChannel ch = channel(50);
        assertTrue(ch.enqueue("a", task -> { throw new IllegalStateException("disabled"); }));
        List<Runnable> started = new ArrayList<>();
        ch.enqueue("b", started::add);
        assertEquals(1, started.size(), "the next enqueue schedules a drain again");
        started.get(0).run();
        assertEquals(List.of("a", "b"), sentBodies);
    }
}
