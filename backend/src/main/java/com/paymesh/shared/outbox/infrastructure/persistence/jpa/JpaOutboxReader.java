package com.paymesh.shared.outbox.infrastructure.persistence.jpa;

import com.paymesh.shared.outbox.application.OutboxReader;
import com.paymesh.shared.outbox.application.UnpublishedEvent;
import com.paymesh.shared.outbox.domain.EventId;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * PostgreSQL-backed implementation of the relay's read/stamp port.
 * <p>
 * NOTE WHAT IS NOT HERE: any conversion to {@code OutboxEvent}. The rows are handed over exactly as
 * stored, because validating them here would put the failure OUTSIDE the relay's per-item try/catch
 * -- the bug open item 2 describes in both existing sweeps, where one unmappable row disables a job
 * permanently. See {@code UnpublishedEvent}.
 * <p>
 * There is no {@code @Transactional}. {@code findUnpublished} is a plain read; {@code markPublished}
 * is {@code @Modifying} and joins the transaction the relay opened around it.
 */
public final class JpaOutboxReader implements OutboxReader {

    private static final int MAX_ERROR_LENGTH = 2000;

    private final SpringDataOutboxRepository events;

    public JpaOutboxReader(SpringDataOutboxRepository events) {
        this.events = events;
    }

    @Override
    public List<UnpublishedEvent> findUnpublished(int limit) {
        return events.findUnpublished(limit).stream()
            .map(JpaOutboxReader::toRow)
            .toList();
    }

    @Override
    public void markPublished(EventId eventId, Instant publishedAt) {
        events.markPublished(eventId.value(), publishedAt);
    }

    @Override
    public void recordFailedAttempt(String eventId, Instant attemptedAt, String error, int maxAttempts) {
        events.recordFailedAttempt(eventId, attemptedAt, truncate(error), maxAttempts);
    }

    @Override
    public BacklogHealth backlogHealth() {
        return new BacklogHealth(events.oldestUnpublishedOccurredAt(), events.countDeadLettered());
    }

    /**
     * {@code last_error} is TEXT and takes anything, so this is not a schema requirement -- it is a
     * refusal to let a pathological exception message (a serialized payload echoed back, a driver
     * error carrying a whole statement) turn the outbox into the place large blobs accumulate. The
     * head of a message is the part that names the cause; the tail is context the log already has.
     */
    private static String truncate(String error) {
        if (error == null) {
            return null;
        }

        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    /**
     * Column-for-column, with no validation and <b>no parsing of row content</b> -- that is the
     * contract, and the second half of it is what this class originally broke. (The one conversion
     * here, {@link #toInstant}, is a JDBC type dispatch rather than a read of what the row says;
     * see its javadoc for why that distinction is load-bearing.)
     * <p>
     * The candidate query returns columns rather than entities specifically so nothing here
     * deserializes: {@code payload} arrives as the raw {@code ::text} of the JSONB column and stays
     * a string until {@code UnpublishedEvent.toEvent} parses it inside the relay's per-item try. A
     * mapper call in this method would be outside that boundary, which is exactly the shape open
     * item 2 is about.
     */
    private static UnpublishedEvent toRow(Object[] row) {
        return new UnpublishedEvent(
            (String) row[0],
            (String) row[1],
            (String) row[2],
            (String) row[3],
            (String) row[4],
            ((Number) row[5]).intValue(),
            (String) row[6],
            toInstant(row[7]),
            ((Number) row[8]).intValue()
        );
    }

    /**
     * {@code occurred_at} is {@code timestamptz}. A native query hands that back as whatever the
     * driver prefers -- {@link OffsetDateTime} on the PostgreSQL driver, {@link Timestamp} on
     * others -- and neither is an {@link Instant}, so the conversion is explicit rather than a cast
     * that works until the day it does not.
     *
     * <p><b>This throws, and it runs outside the relay's per-item try, which looks like exactly the
     * mistake this class was just fixed for.</b> It is not, and the difference is worth stating: the
     * JDBC type of a column is a property of the driver and the schema, identical for every row in
     * the result set. A row cannot poison it. If this branch is ever reached it means a driver or
     * dialect upgrade changed the mapping, in which case every row fails and the honest outcome is a
     * loud failure of the whole pass rather than dead-lettering the entire backlog one row at a time
     * for a defect that has nothing to do with the rows.
     */
    private static Instant toInstant(Object value) {
        return switch (value) {
            case Instant instant -> instant;
            case OffsetDateTime offset -> offset.toInstant();
            case Timestamp timestamp -> timestamp.toInstant();
            case null -> null;
            default -> throw new IllegalStateException(
                "Unexpected occurred_at type " + value.getClass()
            );
        };
    }
}
