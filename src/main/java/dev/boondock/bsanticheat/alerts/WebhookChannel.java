package dev.boondock.bsanticheat.alerts;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Send queue and rate limiter for ONE webhook URL.
 *
 * <p>Discord limits a webhook to roughly 30 requests per minute, counted per URL. Every
 * {@link DiscordWebhook} sending to the same URL therefore shares one channel (see
 * {@link #forUrl}); two independent limiters on one URL would together send twice the
 * allowed rate. A 429 answer is honoured: the same message is retried after the delay
 * Discord asks for, a bounded number of times.
 *
 * <p>All sending runs on the executor handed in (an async scheduler thread in production),
 * one drain at a time; callers only enqueue.
 */
final class WebhookChannel {

    /** One HTTP attempt. Implementations must not retry on their own. */
    interface Sender {
        Response send(String url, String json) throws IOException;
    }

    /**
     * Outcome of one attempt.
     *
     * @param status       HTTP status code
     * @param retryAfterMs delay Discord asked for (429 or an exhausted bucket), or -1
     */
    record Response(int status, long retryAfterMs) {}

    /** Sleeps for the given milliseconds; replaceable in tests. */
    interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    /** Spacing between two requests to one URL: 60 s / 2.1 s = 28 requests per minute. */
    static final long DEFAULT_SPACING_MS = dev.boondock.bsanticheat.util.Constants.DISCORD_MIN_REQUEST_DELAY_MS;
    /** Attempts per message before it is given up (429s and transient failures). */
    static final int MAX_ATTEMPTS = 4;
    /** Upper bound on a single wait, whatever a response header claims. */
    static final long MAX_WAIT_MS = 60_000L;

    private static final ConcurrentMap<String, WebhookChannel> CHANNELS = new ConcurrentHashMap<>();

    private final String url;
    private final int capacity;
    private final long spacingMs;
    private final Sender sender;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private final Logger logger;

    private final Deque<String> queue = new ArrayDeque<>();
    private boolean draining;
    /** Earliest time the next request may be sent (ms, {@link #clock}). */
    private volatile long nextAllowedAt;
    /** Messages dropped since the last warning cycle; the warning is logged once per cycle. */
    private int dropped;
    /** Unexpected failures (scheduler refusal, unchecked sender exceptions) are logged once. */
    private volatile boolean unexpectedFailureLogged;

    WebhookChannel(String url, int capacity, long spacingMs, Sender sender,
                   LongSupplier clock, Sleeper sleeper, Logger logger) {
        this.url = url;
        this.capacity = capacity;
        this.spacingMs = spacingMs;
        this.sender = sender;
        this.clock = clock;
        this.sleeper = sleeper;
        this.logger = logger;
    }

    /** The channel shared by every sender to {@code url}; created on first use. */
    static WebhookChannel forUrl(String url, int capacity, Sender sender, Logger logger) {
        return CHANNELS.computeIfAbsent(url, u -> new WebhookChannel(u, capacity, DEFAULT_SPACING_MS,
                sender, System::currentTimeMillis, Thread::sleep, logger));
    }

    /**
     * Queue a message and make sure a drain is running.
     *
     * <p>Never throws into the caller: a scheduler that refuses the drain (plugin disabling)
     * leaves the message queued for the next successful enqueue.
     *
     * @param executor runs the drain when none is active
     * @return false when the queue was full and the message was dropped
     */
    boolean enqueue(String json, Consumer<Runnable> executor) {
        synchronized (this) {
            if (queue.size() >= capacity) {
                if (dropped++ == 0) {
                    logger.warning("[Discord] Alert queue full (" + capacity + "), dropping alerts until"
                            + " it drains. Discord allows about 30 messages per minute per webhook.");
                }
                return false;
            }
            queue.addLast(json);
            if (draining) return true;
            draining = true;
        }
        try {
            executor.accept(this::drain);
        } catch (RuntimeException e) {
            synchronized (this) { draining = false; }
            logUnexpectedOnce("could not schedule the send task", e);
        }
        return true;
    }

    /**
     * Send until the queue is empty. Runs on the executor's thread. Every exit releases the
     * drain flag, so an unexpected exception cannot leave the channel stuck as "draining".
     */
    void drain() {
        boolean released = false;
        try {
            drainLoop();
            released = true;
        } catch (RuntimeException e) {
            logUnexpectedOnce("send loop aborted", e);
        } finally {
            if (!released) {
                synchronized (this) { draining = false; }
            }
        }
    }

    /** Returns only after releasing the drain flag itself. */
    private void drainLoop() {
        int attempts = 0;
        while (true) {
            String json;
            synchronized (this) {
                json = queue.peekFirst();
                if (json == null) {
                    draining = false;
                    if (dropped > 0) {
                        logger.warning("[Discord] " + dropped + " alert(s) were dropped while the queue was full.");
                        dropped = 0;
                    }
                    return;
                }
            }

            long wait = nextAllowedAt - clock.getAsLong();
            if (wait > 0) {
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    synchronized (this) { draining = false; }
                    return;
                }
            }

            // Stamped on every attempt, success or not: a failure must not let the next
            // request go out immediately.
            long sentAt = clock.getAsLong();
            nextAllowedAt = sentAt + spacingMs;
            attempts++;

            boolean done;
            try {
                Response r = sender.send(url, json);
                if (r.retryAfterMs() > 0) {
                    nextAllowedAt = Math.max(nextAllowedAt, sentAt + Math.min(r.retryAfterMs(), MAX_WAIT_MS));
                }
                if (r.status() >= 200 && r.status() < 300) {
                    done = true;
                } else if (r.status() == 429 || r.status() >= 500) {
                    done = giveUpAfter(attempts, "status " + r.status());
                } else {
                    // Other 4xx: the request itself is wrong (deleted webhook, bad payload);
                    // repeating it cannot help.
                    logger.warning("[Discord] Webhook rejected the alert (status " + r.status() + ").");
                    done = true;
                }
            } catch (IOException e) {
                done = giveUpAfter(attempts, e.getMessage());
            } catch (RuntimeException e) {
                // Not a transient network error; retrying the same message would repeat it.
                logUnexpectedOnce("alert dropped", e);
                done = true;
            }

            if (done) {
                attempts = 0;
                synchronized (this) { queue.pollFirst(); }
            }
        }
    }

    private boolean giveUpAfter(int attempts, String reason) {
        if (attempts < MAX_ATTEMPTS) return false;
        logger.warning("[Discord] Webhook failed after " + attempts + " attempts (" + reason + "), alert dropped.");
        return true;
    }

    private void logUnexpectedOnce(String what, RuntimeException e) {
        if (unexpectedFailureLogged) return;
        unexpectedFailureLogged = true;
        logger.log(java.util.logging.Level.WARNING, "[Discord] Webhook " + what + ": " + e, e);
    }

    synchronized int queued() {
        return queue.size();
    }

    /** Testing only: forget all shared channels. */
    static void resetForTests() {
        CHANNELS.clear();
    }
}
