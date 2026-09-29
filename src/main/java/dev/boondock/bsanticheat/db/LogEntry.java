package dev.boondock.bsanticheat.db;

import java.util.UUID;

/**
 * One queued log row.
 *
 * @param timeMs   when the violation was DETECTED, not when the row reaches the database.
 *                 Entries are batched and flushed every {@code DB_FLUSH_INTERVAL_SECONDS}, so
 *                 letting the {@code ts} column default to {@code CURRENT_TIMESTAMP} stamped
 *                 every row in a batch with the same flush time. Alerts from one incident then
 *                 all carried an identical timestamp minutes off the event, which is precisely
 *                 when the column is needed: correlating an alert against the server log.
 * @param playerId the player the row is about, or null when it could not be determined.
 *                 Deleting a player's rows goes by this, not by the name in the description,
 *                 which changes on a rename and can later belong to somebody else.
 */
public record LogEntry(String type, double value, String description, long timeMs, UUID playerId) {}
