package com.paymesh.audit.infrastructure;

import com.paymesh.audit.application.RecordAuditEventService;
import com.paymesh.audit.domain.AuditEvent;
import com.paymesh.audit.domain.AuditEventId;
import com.paymesh.shared.audit.AuditEntry;
import com.paymesh.shared.audit.AuditRecorder;

import java.time.Clock;
import java.time.Instant;

/**
 * The single implementation of the shared {@link AuditRecorder} port (ADR-035).
 *
 * <h2>THE ONE PLACE PLAINTEXT BECOMES A HASH</h2>
 *
 * A privileged service hands over an {@link AuditEntry} with plaintext {@code before}, {@code after}
 * and {@code ip}; this adapter hashes them (see {@link AuditHashing}) before building the
 * {@link AuditEvent}, so nothing downstream -- the domain, the row, a CSV export -- ever holds the
 * value. A caller cannot skip the hashing, because there is no other path from an entry to a row.
 *
 * <h2>NO TRANSACTION, BY DESIGN</h2>
 *
 * The append inherits the caller's transaction. That is the whole point of the recorder living
 * behind a synchronous port rather than an event: the audit row commits with the action it records
 * (ADR-035), so there is no committed suspension whose audit event rolled back, and no audit event
 * for a suspension that did not commit.
 */
public final class AuditRecorderAdapter implements AuditRecorder {

    private final RecordAuditEventService record;
    private final Clock clock;

    public AuditRecorderAdapter(RecordAuditEventService record, Clock clock) {
        this.record = record;
        this.clock = clock;
    }

    @Override
    public void record(AuditEntry entry) {
        AuditEvent event = AuditEvent.record(
            AuditEventId.generate(),
            entry.actorType(),
            entry.actorId(),
            entry.merchantId(),
            entry.action(),
            entry.resourceType(),
            entry.resourceId(),
            entry.reason(),
            AuditHashing.sha256(entry.before()),
            AuditHashing.sha256(entry.after()),
            AuditHashing.sha256(entry.ip()),
            Instant.now(clock)
        );

        record.record(event);
    }
}
