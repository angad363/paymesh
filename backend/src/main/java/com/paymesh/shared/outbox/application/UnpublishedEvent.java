package com.paymesh.shared.outbox.application;

import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;
import java.util.Map;

/**
 * One claimed {@code outbox_events} row, EXACTLY AS IT IS STORED AND NOT YET VALIDATED.
 *
 * <h2>Why the port does not simply return {@link OutboxEvent}</h2>
 *
 * Open item 2 in {@code docs/project-status.md} describes the bug this shape exists to avoid, in the
 * two sweepers that already have it: they map every candidate row through the aggregate <em>inside
 * the repository call</em>, which is <b>outside</b> the per-item try/catch that exists to stop one
 * bad row killing the run. A row that fails to map therefore throws out of the whole pass, and
 * because candidates come back oldest-first it sits at the head of every subsequent batch and
 * silently disables the job forever.
 * <p>
 * The relay must not reproduce that. So this record carries raw columns with <b>no validation at
 * all</b> -- a blank event id, a merchant id that will not parse and a zero version all survive
 * construction -- and {@link #toEvent()} is called by the relay <em>inside</em> its per-item
 * try/catch, where a malformed row costs one iteration and nothing else.
 * <p>
 * It lives in {@code application} rather than {@code infrastructure} because it is what the
 * {@link OutboxReader} port returns, and a port's own package must be able to name its return type.
 * It carries no JPA type and no framework annotation.
 */
/**
 * @param attemptCount how many delivery attempts have already FAILED for this row (V21). Carried so
 *     the relay can say, without a second query, whether the failure it is about to record is the
 *     one that exhausts the retry budget -- the difference between a WARN that will resolve itself
 *     and an ERROR nobody else will ever raise. Like every other component here it is unvalidated:
 *     a negative value survives construction and is simply a number the relay compares.
 */
public record UnpublishedEvent(
    String eventId,
    String merchantId,
    String aggregateType,
    String aggregateId,
    String eventType,
    int eventVersion,
    Map<String, Object> payload,
    Instant occurredAt,
    int attemptCount
) {

    /**
     * The validating step, and THE ONLY PLACE THIS ROW IS ALLOWED TO THROW.
     *
     * @throws IllegalArgumentException when the stored row cannot form a legal envelope
     */
    public OutboxEvent toEvent() {
        return new OutboxEvent(
            EventId.from(eventId),
            MerchantId.from(merchantId),
            aggregateType,
            aggregateId,
            eventType,
            eventVersion,
            payload,
            occurredAt
        );
    }
}
