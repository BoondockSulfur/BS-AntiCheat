package dev.boondock.bsanticheat.db;

/**
 * One queued log row.
 *
 * @param timeMs when the violation was DETECTED, not when the row reaches the database.
 *               Entries are batched and flushed every {@code DB_FLUSH_INTERVAL_SECONDS}, so
 *               letting the {@code ts} column default to {@code CURRENT_TIMESTAMP} stamped
 *               every row in a batch with the same flush time. Alerts from one incident then
 *               all carried an identical timestamp minutes off the event, which is precisely
 *               when the column is needed: correlating an alert against the server log.
 */
public record LogEntry(String type, double value, String description, long timeMs) {}
