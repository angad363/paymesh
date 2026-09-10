package com.paymesh.reporting.infrastructure.events;

import com.paymesh.reporting.application.RecordReportFactService;
import com.paymesh.reporting.infrastructure.events.ReportFactExtractor.Extracted;
import com.paymesh.shared.outbox.application.EventHandler;
import com.paymesh.shared.outbox.domain.OutboxEvent;

/**
 * Reporting's consumer. Six beans, one class, because the six differ only in a string -- the same
 * shape {@code WebhookFanOutHandler} and {@code NotificationEventHandler} take.
 *
 * <h2>THE THREE {@link EventHandler} RULES, AND HOW THIS KEEPS THEM</h2>
 *
 * <ol>
 *   <li><b>No transaction of its own.</b> Neither this nor {@link RecordReportFactService} opens
 *       one; both run inside the dispatcher's, alongside the inbox row.</li>
 *   <li><b>Idempotent anyway.</b> {@code source_event_id} is the fact table's PRIMARY KEY, so a
 *       second event id describing the same fact is the only way to double-count -- and
 *       {@code saveIfAbsent} checks before writing.</li>
 *   <li><b>Throws to retry.</b> Nothing here is caught. An event whose payload cannot be read
 *       leaves {@code published_at} null, the relay retries, and -- since the payload is still
 *       unreadable -- eventually dead-letters it (ADR-025), which is the correct end for an event
 *       that can never be projected.</li>
 * </ol>
 *
 * <p>The merchant comes from the ENVELOPE, not the payload, and so does the timestamp. The
 * envelope's merchant was copied from the aggregate by the producer; a payload field is data.
 */
public final class ReportFactHandler implements EventHandler {

    private final String eventType;
    private final String consumerName;
    private final RecordReportFactService record;

    /**
     * @param eventType also the tail of the consumer name, so the inbox key is
     *     {@code reporting.payment.succeeded}. STABLE: renaming it re-opens the entire backlog to
     *     this consumer, which would re-project every event PayMesh has ever emitted -- and since
     *     the fact table is keyed on the event id rather than the consumer, most of those inserts
     *     would be refused as duplicates and the rest would be a projection nobody asked for.
     */
    public ReportFactHandler(String eventType, RecordReportFactService record) {
        if (!ReportFactExtractor.canExtract(eventType)) {
            throw new IllegalArgumentException(
                "Reporting has no extraction for " + eventType
                    + "; a handler for it would fail on every event"
            );
        }

        this.eventType = eventType;
        this.consumerName = "reporting." + eventType;
        this.record = record;
    }

    @Override
    public String consumerName() {
        return consumerName;
    }

    @Override
    public String eventType() {
        return eventType;
    }

    @Override
    public void handle(OutboxEvent event) {
        Extracted extracted = ReportFactExtractor.extract(event.eventType(), event.payload());

        record.record(
            event.merchantId(),
            event.eventId().value(),
            event.eventType(),
            extracted.subjectId(),
            extracted.orderId(),
            extracted.currency(),
            extracted.amountMinor(),
            event.occurredAt()
        );
    }
}
